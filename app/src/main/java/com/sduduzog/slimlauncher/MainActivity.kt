package com.sduduzog.slimlauncher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.navigation.NavController
import androidx.navigation.Navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import com.sduduzog.slimlauncher.utils.BaseFragment
import com.sduduzog.slimlauncher.utils.HomeWatcher
import com.sduduzog.slimlauncher.utils.IPublisher
import com.sduduzog.slimlauncher.utils.ISubscriber
import com.sduduzog.slimlauncher.utils.SystemUiManager
import com.sduduzog.slimlauncher.utils.WallpaperManager
import dagger.hilt.android.AndroidEntryPoint
import java.lang.reflect.Method
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.absoluteValue
import kotlin.math.min
import kotlin.random.Random

private const val TAG = "DEMAYA"
private const val MAX_LOG_OCCURRENCES_FOR_SOFT_LOCK = 12

@AndroidEntryPoint
class MainActivity :
    AppCompatActivity(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    HomeWatcher.OnHomePressedListener,
    IPublisher {

    private val wallpaperManager = WallpaperManager(this)

    @Inject
    lateinit var systemUiManager: SystemUiManager
    private lateinit var settings: SharedPreferences
    private lateinit var navigator: NavController
    private lateinit var homeWatcher: HomeWatcher

    var mayaImageView: ImageView? = null
    var mayaExitButton: ImageButton? = null
    var mayaTextView: TextView? = null

    private val subscribers: MutableSet<BaseFragment> = mutableSetOf()

    override fun attachSubscriber(s: ISubscriber) {
        subscribers.add(s as BaseFragment)
    }

    override fun detachSubscriber(s: ISubscriber) {
        subscribers.remove(s as BaseFragment)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun dispatchBack() {
        for (s in subscribers) if (s.onBack()) return
        completeBackAction()
    }

    private fun dispatchHome() {
        for (s in subscribers) s.onHome()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)

        // absurd spaghetti code
        mayaExitButton = findViewById(R.id.maya_exit_button)
        mayaTextView = findViewById(R.id.maya_text_view)
        mayaImageView = findViewById<ImageView>(R.id.maya_image_view).apply {
            setOnTouchListener { _, _ -> true }  // capture touch events
        }

        settings = getSharedPreferences(getString(R.string.prefs_settings), MODE_PRIVATE).apply {
            registerOnSharedPreferenceChangeListener(this@MainActivity)
        }
        val navHostFragment = supportFragmentManager.findFragmentById(
            R.id.nav_host_fragment
        ) as NavHostFragment
        navigator = navHostFragment.navController
        homeWatcher = HomeWatcher.createInstance(this)
        homeWatcher.setOnHomePressedListener(this)

        // get permissions & start overlay service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val appOps = applicationContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), applicationContext.packageName)
            if (mode != AppOpsManager.MODE_ALLOWED) {
                Log.d(TAG, "ACTION_USAGE_ACCESS_SETTINGS")
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }

            maybeStartService()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onResume() {
        super.onResume()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        systemUiManager.setSystemUiVisibility()
        OverlayService.overlayUpdaterStop = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            maybeStartService()
        }
    }

    override fun onStart() {
        super.onStart()
        homeWatcher.startWatch()
    }

    override fun onStop() {
        super.onStop()
        homeWatcher.stopWatch()
    }

    override fun onDestroy() {
        super.onDestroy()
        settings.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) systemUiManager.setSystemUiVisibility()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, s: String?) {
        if (s.equals(getString(R.string.prefs_settings_key_theme), true)) {
            recreate()
        }
        if (s.equals(getString(R.string.prefs_settings_key_toggle_status_bar), true)) {
            systemUiManager.setSystemUiVisibility()
        }
    }

    override fun onApplyThemeResource(
        theme: Resources.Theme?,
        @StyleRes resid: Int,
        first: Boolean
    ) {
        super.onApplyThemeResource(theme, resid, first)
        wallpaperManager.onApplyThemeResource(theme, resid)
    }

    override fun setTheme(resId: Int) {
        super.setTheme(getUserSelectedThemeRes())
    }

    @StyleRes
    fun getUserSelectedThemeRes(): Int {
        settings = getSharedPreferences(getString(R.string.prefs_settings), MODE_PRIVATE)
        val active = settings.getInt(getString(R.string.prefs_settings_key_theme), 0)
        return resolveTheme(active)
    }

    override fun onBackPressed() {
        dispatchBack()
    }

    override fun onHomePressed() {
        dispatchHome()
        navigator.popBackStack(R.id.homeFragment, false)
    }

    companion object {
        @StyleRes
        fun resolveTheme(i: Int): Int {
            return when (i) {
                1 -> R.style.AppThemeDark
                2 -> R.style.AppGreyTheme
                3 -> R.style.AppTealTheme
                4 -> R.style.AppCandyTheme
                5 -> R.style.AppPinkTheme
                6 -> R.style.AppThemeLight
                7 -> R.style.AppDarculaTheme
                8 -> R.style.AppGruvBoxDarkTheme
                9 -> R.style.AppFleshNetworkTheme  // evil
                else -> R.style.AppTheme
            }
        }
    }

    private fun completeBackAction() {
        super.onBackPressed()
    }

    private fun isVisible(view: View): Boolean {
        if (!view.isShown) {
            return false
        }

        val actualPosition = Rect()
        view.getGlobalVisibleRect(actualPosition)
        val screen = Rect(
            0,
            0,
            Resources.getSystem().displayMetrics.widthPixels,
            Resources.getSystem().displayMetrics.heightPixels
        )
        return actualPosition.intersect(screen)
    }

    private val gestureDetector = GestureDetector(
        baseContext,
        object : SimpleOnGestureListener() {
            private fun isEventOnTopOfView(event: MotionEvent, view: View) =
                doesViewContainPoint(view, event.rawX.toInt(), event.rawY.toInt())

            private fun doesViewContainPoint(view: View, rx: Int, ry: Int): Boolean {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                val x = location[0]
                val y = location[1]

                return !(rx < x || rx > x + view.width || ry < y || ry > y + view.height)
            }

            @RequiresApi(Build.VERSION_CODES.Q)
            override fun onLongPress(e: MotionEvent) {
                // Open Options
                val recyclerView = findViewById<RecyclerView>(R.id.app_drawer_fragment_list)
                if (recyclerView != null && isVisible(recyclerView)) {
                    recyclerView.performLongClick()
                } else {
                    // switch to ksana mode
                    val dateView = findViewById<View>(R.id.home_fragment_date)
                    if (dateView != null && isEventOnTopOfView(e, dateView))
                        return

                    // show next verse
                    val verseView = findViewById<View>(R.id.home_bible_quote)
                    if (verseView != null && isEventOnTopOfView(e, verseView))
                        return

                    // open in gallery
                    val wallpaperView = findViewById<View>(R.id.home_wallpaper_box)
                    if (wallpaperView != null && isEventOnTopOfView(e, wallpaperView))
                        return

                    // we are in the homeFragment & didn't long-click the date view etc.
                    // (which would switch to ksana mode instead of opening settings)
                    val vibrator = getSystemService(Vibrator::class.java)
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))

                    val homeView = findViewById<View>(R.id.home_fragment) ?: return
                    findNavController(homeView).navigate(R.id.action_homeFragment_to_optionsFragment, null)
                }
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val homeView = findViewById<MotionLayout>(R.id.home_fragment)
                if (homeView != null) {
                    val homeScreen = homeView.constraintSetIds[0]
                    val isFlingFromHomeScreen = homeView.currentState == homeScreen
                    val isFlingDown = velocityY > 0 && velocityY > velocityX.absoluteValue
                    if (isFlingDown && isFlingFromHomeScreen) {
                        expandStatusBar()
                    }
                }
                return super.onFling(e1, e2, velocityX, velocityY)
            }
        }
    )

    @SuppressLint("WrongConstant") // statusbar is an internal API
    private fun expandStatusBar() {
        try {
            getSystemService("statusbar")?.let { service ->
                val statusbarManager = Class.forName("android.app.StatusBarManager")
                val expand: Method = statusbarManager.getMethod("expandNotificationsPanel")
                expand.invoke(service)
            }
        } catch (e: Exception) {
            // Do nothing. There does not seem to be any official way with the Android SKD to open the status bar.
            // https://stackoverflow.com/questions/5029354/how-can-i-programmatically-open-close-notifications-in-android
            // This hack may break on future versions of Android (or even just not work for specific manufacturer variants).
            // So, if anything goes wrong, we will just do nothing.
            Log.e(
                "MainActivity",
                "Error trying to expand the notifications panel.",
                e
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun maybeStartService() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            Log.d(TAG, "ACTION_MANAGE_OVERLAY_PERMISSION")
            startActivity(intent)
        }

        else {
            Log.d(TAG, "Demaya running")
            startService(Intent(this, OverlayService::class.java))
        }
    }
}


private val forbiddenPackageNames = arrayListOf(
    "com.google.android.youtube",  // obviously
    "com.instagram.android",  // yeah
    "com.zhiliaoapp.musically",  // this is tiktok for some reason
    "org.schabi.newpipe",  // youtube clone
    "com.reddit.frontpage",
    "com.github.libretube",
    "org.wikipedia",  // yep, autism
    "org.fdroid.fdroid", "com.android.vending",  // app stores, as they can be used to bypass our lock

    // these aren't a problem yet:
    // "co.hinge.app", "com.bumble.app", "com.tinder",
)
fun isForbiddenApp(packageName: String): Boolean {
    return forbiddenPackageNames.contains(packageName) || packageName.contains("poker")
}

fun isFastTrackApp(packageName: String): Boolean {
    val l = packageName.lowercase()
    return l.contains("noteless") || l.contains("camera") ||
            l.contains("settings") || l.contains("whatsapp") ||
            l.contains("apps.maps") ||  // google maps
            l.contains(".oebb") ||  // railway tickets etc.
            l.contains("railanddrive") ||
            l.contains("moshbit.studo")  // university
}

fun shouldBeSoftForbidden(packageName: String): Boolean {
    if (packageName.contains("system") ||
        packageName.contains("google") ||
        packageName.contains("unlauncher") ||
        packageName.contains("settings") ||
        packageName.contains("photos") ||
        packageName.contains("xodo") ||  // .pdf viewer I need for uni
        packageName.contains("android.files") ||  // me.zhanghai.android.files

        // neither soft-bannable nor fast-track:
        // because I still want the slight launch delay on these.
        packageName.contains("spotify") ||
        // packageName.contains("wikipedia") ||
        packageName.contains("firefox") ||
        packageName.contains("grindr") ||  // incredibly badly programmed app
        isFastTrackApp(packageName))
        return false  // never ban the launcher etc., lol

    val now = Date()
    return OverlayService.activityLog.count { m ->
        (packageName == m.packageName) &&
                TimeUnit.MILLISECONDS.toHours(now.time - m.date.time) < 6
    } > MAX_LOG_OCCURRENCES_FOR_SOFT_LOCK
}

fun isForbiddenOrSoftForbiddenApp(packageName: String): Pair<Boolean, Boolean> {
    val forbidden = isForbiddenApp(packageName)
    val softForbidden = if (forbidden) false else shouldBeSoftForbidden(packageName)
    return Pair(forbidden || softForbidden, softForbidden)
}


private const val S_TAG = "DEMAYA_SERVICE"

class OverlayService : AccessibilityService() {
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var vibrator: Vibrator
    private lateinit var view: TextView

    class LogMessage(val date: Date, val packageName: String)

    companion object {
        private var resumeAlpha = 0f
        private var allowedPackage: String? = null

        val activityLog = ArrayList<LogMessage>()  // TODO: add code to cull this occasionally

        var onAppSwitchedListener: Runnable? = null
        var overlayUpdaterStop = false

        fun resetTimer(forPackage: String) {
            allowedPackage = forPackage
            resumeAlpha = 0f
        }

        fun isRunning(context: Context): Boolean {
            val thisService = context.packageName
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).forEach {
                if (it.resolveInfo.serviceInfo.packageName == thisService) {
                    return true
                }
            }
            return false
        }
    }

    private val updateHandler = Handler(Looper.getMainLooper())
    inner class OverlayUpdater : Runnable {
        override fun run() {
            resumeAlpha = min(resumeAlpha + 0.000_250f, 1f)
            if (resumeAlpha > 0.90f && allowedPackage != null) {
                // implemented ksana7312.53642268220: Attempt to kill the app.
                val am = applicationContext.getSystemService(ACTIVITY_SERVICE) as ActivityManager
                am.killBackgroundProcesses(allowedPackage)
            }

            view.alpha = resumeAlpha
            view.text = (0..6000).map {
                "草半豆東亭種婆的躲更蛋地才細水連葉花升金速法情同任連寺品文優高満支隊撲女諤芸九".random()
            }.joinToString(separator = "", prefix = "")
            if (keyguardManager.isDeviceLocked) {
                overlayUpdaterStop = true
            }
            if (!overlayUpdaterStop) {
                updateHandler.postDelayed(this, 33)
            }
            else {
                view.visibility = View.INVISIBLE
            }

            if (Random.nextFloat() > (1 - (resumeAlpha / 2.5))) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            }
        }
    }
    private var lastOverlayUpdater: OverlayUpdater? = null

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP_MR1)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName ?: return
        val packageNameString = event.packageName.toString()
        if (packageNameString.contains("inputmethod") ||
            packageNameString.contains("system")) {
            return  // keyboard etc.
        }

        // only show blocker if we are on the home screen
        if (packageNameString.contains("unlauncher")) {
            blockerView?.visibility = View.VISIBLE
            return
        }
        blockerView?.visibility = View.GONE

        if (activityLog.isEmpty() || activityLog.last().packageName != packageNameString)
            activityLog.add(LogMessage(Date(), packageNameString))
        onAppSwitchedListener?.run()

        val (forbidden, _) = isForbiddenOrSoftForbiddenApp(packageNameString)
        Log.d(S_TAG, "packageName: ${packageNameString}, forbidden: $forbidden, time: ${event.eventTime}")
        if (forbidden) {
            view.visibility = View.VISIBLE
            if (allowedPackage != null && packageNameString != allowedPackage) {
                // no cheating! user opened e.g. instagram, and then switched to newpipe via tray.
                resumeAlpha = 1f
            }

            if (lastOverlayUpdater == null || overlayUpdaterStop) {
                overlayUpdaterStop = false
                lastOverlayUpdater = OverlayUpdater().also {
                    updateHandler.post(it)
                }
            }

        // this allows us to block the app tray
        } else if (!packageNameString.contains("nexuslauncher")) {
            overlayUpdaterStop = true
            view.visibility = View.INVISIBLE
        }
    }

    override fun onInterrupt() {
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP_MR1)
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(S_TAG, super.toString())

        keyguardManager = applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        vibrator = applicationContext.getSystemService(Vibrator::class.java)

        addMayaView()
        addPillBlockerView()
    }

    private fun addMayaView() {
        view = TextView(applicationContext).apply {
            alpha = 0f
            visibility = View.INVISIBLE
            setBackgroundColor(Color.RED)
            setTextColor(Color.BLACK)
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            textSize = 32F
        }

        // https://developer.android.com/about/versions/12/behavior-changes-all?hl=de#untrusted-touch-events-affected-apps
        // https://www.reddit.com/r/tasker/comments/xkhm3q/overlay_scene_is_always_transparent/
        // adb shell settings put global maximum_obscuring_opacity_for_touch 1
        (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view,
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.RGBA_8888
            ).apply {
                gravity = Gravity.START or Gravity.TOP
                x = 0
                y = 0
            }
        )
    }

    private var blockerView: TextView? = null

    private fun addPillBlockerView() {
        // this hides the navigation pill on my google pixel 7a
        blockerView = TextView(applicationContext).apply {
            setBackgroundColor(Color.BLACK)
            text = "flesh-network"
            setTextColor(0)
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            height = 40  // this is precise
        }

        (getSystemService(WINDOW_SERVICE) as WindowManager).addView(blockerView,
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.RGBA_8888
            ).apply {
                gravity = Gravity.START or Gravity.BOTTOM
                x = 0
                y = 0
            }
        )
    }
}
