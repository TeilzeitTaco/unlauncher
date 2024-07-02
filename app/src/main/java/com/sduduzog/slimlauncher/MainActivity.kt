package com.sduduzog.slimlauncher

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.core.app.NotificationCompat
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
import java.util.SortedMap
import java.util.TreeMap
import javax.inject.Inject
import kotlin.math.absoluteValue
import kotlin.math.min

private const val TAG = "DEMAYA"

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)

        // absurd spaghetti code
        mayaImageView = findViewById(R.id.maya_image_view)
        mayaExitButton = findViewById(R.id.maya_exit_button)
        mayaTextView = findViewById(R.id.maya_text_view)

        settings = getSharedPreferences(getString(R.string.prefs_settings), MODE_PRIVATE)
        settings.registerOnSharedPreferenceChangeListener(this)
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
                9 -> R.style.AppFleshNetworkTheme
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

private const val CHANNEL_ID = "Demaya Channel"


class OverlayService : Service() {
    private var view: TextView? = null
    private var backgroundCheckRunning = true

    inner class OverlayServiceBinder : Binder() {
        var backgroundCheck by ::backgroundCheckRunning

        @RequiresApi(Build.VERSION_CODES.LOLLIPOP_MR1)
        fun activateOverlay() {
            Log.d(TAG, "activateOverlay")

            val handler = Handler(this@OverlayService.mainLooper)
            object : Runnable {
                private var resume = 0
                private var resumeAlpha = 0f

                override fun run() {
                    if (!backgroundCheckRunning) {
                        Log.d(TAG, "!backgroundCheckRunning")
                        return
                    }

                    val foregroundApp = appInForeground(this@OverlayService).lowercase()
                    if (!(foregroundApp.contains("instagram") || foregroundApp.contains("newpipe"))) {
                        // user exited blasphemous app
                        view!!.visibility = View.INVISIBLE
                        if (resume++ < 350) {
                            // check less frequently for a while afterwards,
                            // to prevent home/reopen via tray cheese. But this
                            // is a bit... meh, for I often watch videos > 45 minutes,
                            // which allows me to cheese it anyways.
                            Log.d(TAG, "resume $resume")
                            handler.postDelayed(this, 2_500)
                        }  // else don't post
                    }

                    else {
                        resume = 0
                        view!!.apply {
                            visibility = View.VISIBLE
                            alpha = min(resumeAlpha + 0.000_3f, 1f).also {
                                resumeAlpha = it
                            }
                            text = (0..6000).map {
                                "草半豆東亭種婆的躲更蛋地才細水連葉花升".random()
                            }.joinToString(separator = "", prefix = "")
                        }

                        handler.postDelayed(this, 25)
                    }
                }
            }.run()
        }
    }

    override fun onBind(p0: Intent?) = OverlayServiceBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        view?.apply {
            alpha = 0f
            visibility = View.INVISIBLE
            setBackgroundColor(Color.RED)
            setTextColor(Color.BLACK)
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            textSize = 32F
        }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Overlay notification",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Demaya")
                .setContentText("Working to get you out...")
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .build()

            startForeground(1, notification)
        }

        view = TextView(this.applicationContext)

        // https://developer.android.com/about/versions/12/behavior-changes-all?hl=de#untrusted-touch-events-affected-apps
        // https://www.reddit.com/r/tasker/comments/xkhm3q/overlay_scene_is_always_transparent/
        // adb shell settings put global maximum_obscuring_opacity_for_touch 1
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else
                WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.RGBA_8888
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 0
            y = 0
        }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        wm.addView(view, params)
    }
}

@RequiresApi(Build.VERSION_CODES.LOLLIPOP_MR1)
fun appInForeground(context: Context): String {
    val time = System.currentTimeMillis()
    val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val appList = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 1000, time)!!
    val mySortedMap: SortedMap<Long, UsageStats> = TreeMap()
    for (usageStats in appList) {
        mySortedMap[usageStats.lastTimeUsed] = usageStats
    }
    return mySortedMap[mySortedMap.lastKey()]!!.packageName
}
