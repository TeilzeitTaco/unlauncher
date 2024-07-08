package com.sduduzog.slimlauncher

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
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
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
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

private const val TAG = "DEMAYA"
private const val MAX_LOG_OCCURRENCES_FOR_SOFT_LOCK = 10

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
            Log.d(TAG, "ACTION_USAGE_ACCESS_SETTINGS")
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            maybeStartService()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onResume() {
        super.onResume()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        systemUiManager.setSystemUiVisibility()
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
            override fun onLongPress(e: MotionEvent) {
                // Open Options
                val recyclerView = findViewById<RecyclerView>(R.id.app_drawer_fragment_list)
                val homeView = findViewById<View>(R.id.home_fragment)

                if (homeView != null && recyclerView != null) {
                    if (isVisible(recyclerView)) {
                        recyclerView.performLongClick()
                    } else {
                        // we are in the homeFragment
                        findNavController(
                            homeView
                        ).navigate(R.id.action_homeFragment_to_optionsFragment, null)
                    }
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
    "com.instagram.android",
    "org.schabi.newpipe",
    "com.reddit.frontpage",
    // these aren't a problem yet:
    // "co.hinge.app", "com.bumble.app", "com.tinder",
)
fun isForbiddenApp(packageName: String): Boolean {
    return forbiddenPackageNames.contains(packageName)
}

fun isFastTrackApp(packageName: String): Boolean {
    val l = packageName.lowercase()
    return l.contains("noteless") || l.contains("camera") || l.contains("settings")
}

fun shouldBeSoftForbidden(packageName: String): Boolean {
    if (packageName.contains("system") || packageName.contains("google") ||
        packageName.contains("unlauncher") || packageName.contains("settings") ||
        isFastTrackApp(packageName))
        return false  // never ban the launcher, lol

    val now = Date()
    return OverlayService.activityLog.count { m ->
        (packageName == m.packageName) &&
                TimeUnit.MILLISECONDS.toHours(now.time - m.date.time) < 12
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
    private lateinit var view: TextView

    class LogMessage(val date: Date, val packageName: String)

    companion object {
        private var resumeAlpha = 0f
        private var allowedPackage: String? = null
        fun resetTimer(forPackage: String) {
            allowedPackage = forPackage
            resumeAlpha = 0f
        }

        private var running = false
        fun isRunning() = running

        var onAppSwitchedListener: Runnable? = null

        val activityLog = ArrayList<LogMessage>()
    }

    private val updateHandler = Handler(Looper.getMainLooper())
    inner class OverlayUpdater : Runnable {
        var stop = false
        override fun run() {
            resumeAlpha = min(resumeAlpha + 0.000_175f, 1f)
            view.alpha = resumeAlpha
            view.text = (0..6000).map {
                "草半豆東亭種婆的躲更蛋地才細水連葉花升金速法情同任連寺品文優高満支隊撲女諤芸九".random()
            }.joinToString(separator = "", prefix = "")
            if (keyguardManager.isDeviceLocked) {
                view.visibility = View.INVISIBLE
                stop = true
            }
            if (!stop) {
                updateHandler.postDelayed(this, 30)
            }
        }
    }
    private var lastOverlayUpdater: OverlayUpdater? = null

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP_MR1)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName ?: return
        val packageNameString = event.packageName.toString()
        if (packageNameString.contains("inputmethod") ||
            packageNameString.contains("system") ||
            event.packageName.equals("com.jkuester.unlauncher")) {
            return  // keyboard etc.
        }

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

            if (lastOverlayUpdater == null || lastOverlayUpdater?.stop == true) {
                lastOverlayUpdater = OverlayUpdater().also {
                    updateHandler.post(it)
                }
            }
        } else {
            lastOverlayUpdater?.stop = true
            view.visibility = View.INVISIBLE
        }
    }

    override fun onInterrupt() {
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP_MR1)
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(S_TAG, super.toString())
        running = true

        keyguardManager = applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        view = TextView(this.applicationContext).apply {
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
        val params = WindowManager.LayoutParams(
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

        (getSystemService(WINDOW_SERVICE) as WindowManager).apply {
            addView(view, params)
        }
    }
}
