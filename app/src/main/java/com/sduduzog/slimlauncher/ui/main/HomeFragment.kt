package com.sduduzog.slimlauncher.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.View.OnClickListener
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.annotation.RequiresApi
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.impl.utils.executor.CameraXExecutors
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.motion.widget.MotionLayout.TransitionListener
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import com.jkuester.unlauncher.datastore.ClockType
import com.jkuester.unlauncher.datastore.SearchBarPosition
import com.jkuester.unlauncher.datastore.UnlauncherApp
import com.sduduzog.slimlauncher.MainActivity
import com.sduduzog.slimlauncher.OverlayService
import com.sduduzog.slimlauncher.R
import com.sduduzog.slimlauncher.adapters.AppDrawerAdapter
import com.sduduzog.slimlauncher.adapters.HomeAdapter
import com.sduduzog.slimlauncher.databinding.HomeFragmentBottomBinding
import com.sduduzog.slimlauncher.databinding.HomeFragmentContentBinding
import com.sduduzog.slimlauncher.databinding.HomeFragmentDefaultBinding
import com.sduduzog.slimlauncher.datasource.UnlauncherDataSource
import com.sduduzog.slimlauncher.datasource.quickbuttonprefs.QuickButtonPreferencesRepository
import com.sduduzog.slimlauncher.isFastTrackApp
import com.sduduzog.slimlauncher.isForbiddenOrSoftForbiddenApp
import com.sduduzog.slimlauncher.models.HomeApp
import com.sduduzog.slimlauncher.models.MainViewModel
import com.sduduzog.slimlauncher.ui.dialogs.RenameAppDisplayNameDialog
import com.sduduzog.slimlauncher.utils.BaseFragment
import com.sduduzog.slimlauncher.utils.OnLaunchAppListener
import com.sduduzog.slimlauncher.utils.gravity
import com.sduduzog.slimlauncher.utils.isSystemApp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.random.Random


private const val INVOCATION_MAX = 32
private const val INVOCATION_DELAY: Long = 4_500

@AndroidEntryPoint
class HomeFragment : BaseFragment(), OnLaunchAppListener {
    @Inject
    lateinit var unlauncherDataSource: UnlauncherDataSource

    private val viewModel: MainViewModel by viewModels()

    private lateinit var receiver: BroadcastReceiver
    private lateinit var appDrawerAdapter: AppDrawerAdapter
    private lateinit var uninstallAppLauncher: ActivityResultLauncher<Intent>

    private var isKsanaMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uninstallAppLauncher = registerForActivityResult(StartActivityForResult()) { refreshApps() }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val coreRepository = unlauncherDataSource.corePreferencesRepo
        return if (coreRepository.get().searchBarPosition == SearchBarPosition.bottom) {
            HomeFragmentBottomBinding.inflate(layoutInflater, container, false).root
        } else {
            HomeFragmentDefaultBinding.inflate(layoutInflater, container, false).root
        }
    }

    private lateinit var adapter1: HomeAdapter
    private lateinit var adapter2: HomeAdapter
    private var homeAppList: List<HomeApp> = ArrayList()

    private fun distributeApps() {
        if (homeAppList.size > 3) {
            // for the effect when swiping up, we use two recycler views
            adapter1.setItems(homeAppList.subList(0, 3))
            adapter2.setItems(homeAppList.subList(3, homeAppList.size))
        } else {
            adapter1.setItems(homeAppList)
        }
        lifecycleScope.launch {  // Set the home apps in the Unlauncher data
            unlauncherDataSource.unlauncherAppsRepo.setHomeApps(homeAppList)
        }
    }

    private fun getNewHomeAppList(): List<HomeApp> {
        if (homeAppList.isEmpty())
            return homeAppList

        val newList = homeAppList.shuffled()
        for (app in homeAppList)
            if (homeAppList.indexOf(app) == newList.indexOf(app))
                return getNewHomeAppList()  // we always want *all* apps to move

        return newList
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter1 = HomeAdapter(this, unlauncherDataSource)
        adapter2 = HomeAdapter(this, unlauncherDataSource)
        val homeFragmentContent = HomeFragmentContentBinding.bind(view).apply {
            homeFragmentList.adapter = adapter1
            homeFragmentListExp.adapter = adapter2
        }

        viewModel.apps.observe(viewLifecycleOwner) { list ->
            list?.let { apps ->
                homeAppList = apps
                distributeApps()
            }
        }

        appDrawerAdapter = AppDrawerAdapter(
            AppDrawerListener(),
            viewLifecycleOwner,
            unlauncherDataSource
        )

        setEventListeners()

        homeFragmentContent.appDrawerFragmentList.adapter = appDrawerAdapter

        unlauncherDataSource.corePreferencesRepo.liveData().observe(
            viewLifecycleOwner
        ) { corePreferences ->
            homeFragmentContent.appDrawerEditText
                .visibility = if (corePreferences.showSearchBar) View.VISIBLE else View.GONE

            val clockType = corePreferences.clockType
            homeFragmentContent.homeFragmentTime
                .visibility = if (clockType == ClockType.digital) View.VISIBLE else View.GONE
            homeFragmentContent.homeFragmentAnalogTime
                .visibility = if (clockType == ClockType.analog) View.VISIBLE else View.GONE
            homeFragmentContent.homeFragmentBinTime
                .visibility = if (clockType == ClockType.binary) View.VISIBLE else View.GONE
            homeFragmentContent.homeFragmentDate
                .visibility = if (clockType != ClockType.none) View.VISIBLE else View.GONE
        }
    }

    override fun onStart() {
        super.onStart()
        receiver = ClockReceiver()
        activity?.registerReceiver(receiver, IntentFilter(Intent.ACTION_TIME_TICK))
    }

    override fun getFragmentView(): ViewGroup = HomeFragmentDefaultBinding.bind(
        requireView()
    ).homeFragment

    private fun shuffleHomeApps() {
        // shuffle home apps to avoid muscle memory
        homeAppList = getNewHomeAppList()
        distributeApps()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun getCurrentKsanaTag(): String {
        // this is the flesh-network blog ksana system (implemented ksana7287.46930444425)
        val day0 = Instant.parse("2004-10-11T22:00:00Z")
        val now = Instant.now()
        val days = day0.until(now, ChronoUnit.DAYS)
        val ms = day0.until(now, ChronoUnit.MILLIS) % (60 * 60 * 24 * 1000)
        return "ksana${days + 1}.${ms.toString().padStart(8, '0')}"
    }

    @SuppressLint("SetTextI18n")
    @RequiresApi(Build.VERSION_CODES.O)
    private fun updateKsana() {
        val view = view
        if (view != null) {
            val homeFragmentContent = HomeFragmentContentBinding.bind(view)
            homeFragmentContent.homeFragmentDate.text = "the current instant\n" +
                    "of your life is:\n${getCurrentKsanaTag()}"
        }
    }

    @SuppressLint("SetTextI18n")
    @RequiresApi(Build.VERSION_CODES.O)
    private fun updateClockAndDate() {
        updateClock()
        if (isKsanaMode) {
            updateKsana()
        } else {
            updateDate()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onResume() {
        super.onResume()
        updateClockAndDate()
        shuffleHomeApps()

        refreshApps()
        if (!::appDrawerAdapter.isInitialized) {
            appDrawerAdapter.setAppFilter()
        }

        // scroll back to the top if user returns to this fragment
        val appDrawerFragmentList = HomeFragmentContentBinding.bind(
            requireView()
        ).appDrawerFragmentList
        val layoutManager = appDrawerFragmentList.layoutManager as LinearLayoutManager
        if (layoutManager.findFirstCompletelyVisibleItemPosition() != 0) {
            appDrawerFragmentList.scrollToPosition(0)
        }
    }

    private fun refreshApps() {
        val installedApps = getInstalledApps()
        lifecycleScope.launch(Dispatchers.IO) {
            unlauncherDataSource.unlauncherAppsRepo.setApps(installedApps)
            viewModel.filterHomeApps(installedApps)
        }
    }

    override fun onStop() {
        super.onStop()
        activity?.unregisterReceiver(receiver)
        resetAppDrawerEditText()
    }

    private val ksanaUpdater = object : Runnable {
        override fun run() {
            if (isKsanaMode) {
                updateKsana()
                ksanaHandler.postDelayed(this, 20)
            }
        }
    }

    private val ksanaHandler = Handler(Looper.getMainLooper())

    @RequiresApi(Build.VERSION_CODES.S)
    private fun setEventListeners() {
        val launchShowAlarms = OnClickListener {
            try {
                val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                launchActivity(it, intent)
            } catch (e: ActivityNotFoundException) {
                e.printStackTrace()
                // Do nothing, we've failed :(
            }
        }

        val homeFragmentContent = HomeFragmentContentBinding.bind(requireView()).apply {
            homeFragmentTime.setOnClickListener(launchShowAlarms)
            homeFragmentAnalogTime.setOnClickListener(launchShowAlarms)
            homeFragmentBinTime.setOnClickListener(launchShowAlarms)
            homeFragmentDate.setOnLongClickListener {
                homeFragmentDate.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

                isKsanaMode = !isKsanaMode
                if (isKsanaMode) {
                    ksanaUpdater.run()
                } else {
                    updateDate()  // clear ksana mode
                    // there is a race condition where there is still a ksana update
                    // posted, but after a short delay, that certainly shouldn't be the case.
                    ksanaHandler.postDelayed(::updateDate, 75)
                }
                true
            }
            homeFragmentDate.setOnClickListener {
                if (isKsanaMode) {
                    // if the ksana label is tapped, take a picture.
                    val date = SimpleDateFormat("dd.MM.YYYY").format(Date())
                    takePhoto("${date}-${getCurrentKsanaTag()}.jpg")
                    return@setOnClickListener
                }

                try {
                    val builder = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
                    val intent = Intent(Intent.ACTION_VIEW, builder.build()).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    launchActivity(it, intent)
                } catch (e: ActivityNotFoundException) {
                    // Do nothing, we've failed :(
                }
            }
        }

        unlauncherDataSource.quickButtonPreferencesRepo.liveData()
            .observe(viewLifecycleOwner) { prefs ->
                val leftButtonIcon = QuickButtonPreferencesRepository.RES_BY_ICON.getValue(
                    prefs.leftButton.iconId
                )
                homeFragmentContent.homeFragmentCall.setImageResource(leftButtonIcon)
                if (leftButtonIcon != R.drawable.ic_empty) {
                    homeFragmentContent.homeFragmentCall.setOnClickListener { view ->
                        try {
                            val pm = context?.packageManager!!
                            val intent = Intent(Intent.ACTION_DIAL)
                            val componentName = intent.resolveActivity(pm)
                            if (componentName == null) {
                                launchActivity(view, intent)
                            } else {
                                pm.getLaunchIntentForPackage(componentName.packageName)?.let {
                                    launchActivity(view, it)
                                } ?: run { launchActivity(view, intent) }
                            }
                        } catch (e: Exception) {
                            // Do nothing
                        }
                    }
                }

                val centerButtonIcon = QuickButtonPreferencesRepository.RES_BY_ICON.getValue(
                    prefs.centerButton.iconId
                )
                homeFragmentContent.homeFragmentOptions.setImageResource(centerButtonIcon)
                if (centerButtonIcon != R.drawable.ic_empty) {
                    homeFragmentContent.homeFragmentOptions.setOnClickListener(
                        Navigation.createNavigateOnClickListener(
                            R.id.action_homeFragment_to_optionsFragment
                        )
                    )
                }

                val rightButtonIcon = QuickButtonPreferencesRepository.RES_BY_ICON.getValue(
                    prefs.rightButton.iconId
                )
                homeFragmentContent.homeFragmentCamera.setImageResource(rightButtonIcon)
                if (rightButtonIcon != R.drawable.ic_empty) {
                    homeFragmentContent.homeFragmentCamera.setOnClickListener {
                        try {
                            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                            launchActivity(it, intent)
                        } catch (e: Exception) {
                            // Do nothing
                        }
                    }
                }
            }

        homeFragmentContent.appDrawerEditText.addTextChangedListener(
            appDrawerAdapter.searchBoxListener
        )

        // apply gravity to search box
        unlauncherDataSource.corePreferencesRepo.liveData().observe(viewLifecycleOwner) { corePrefs ->
            homeFragmentContent.appDrawerEditText.gravity = corePrefs.alignmentFormat.gravity()
        }

        val homeFragment = HomeFragmentDefaultBinding.bind(requireView()).root
        homeFragmentContent.appDrawerEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && appDrawerAdapter.itemCount > 0) {
                val firstApp = appDrawerAdapter.getFirstApp()
                launchApp(firstApp.packageName, firstApp.className, firstApp.userSerial)
                homeFragment.transitionToStart()
                true
            } else {
                false
            }
        }

        homeFragment.setTransitionListener(object : TransitionListener {
            override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {
                val inputMethodManager = requireContext().getSystemService(
                    Activity.INPUT_METHOD_SERVICE
                ) as InputMethodManager

                when (currentId) {
                    motionLayout?.startState -> {
                        // hide the keyboard and remove focus from the EditText when swiping back up
                        resetAppDrawerEditText()
                        inputMethodManager.hideSoftInputFromWindow(requireView().windowToken, 0)
                    }

                    motionLayout?.endState -> {
                        val preferences = unlauncherDataSource.corePreferencesRepo.get()
                        // Check for preferences to open the keyboard
                        // only if the search field is shown
                        if (preferences.showSearchBar && preferences.activateKeyboardInDrawer) {
                            homeFragmentContent.appDrawerEditText.requestFocus()
                            // show the keyboard and set focus to the EditText when swiping down
                            inputMethodManager.showSoftInput(
                                homeFragmentContent.appDrawerEditText,
                                InputMethodManager.SHOW_IMPLICIT
                            )
                        }
                    }
                }
            }

            override fun onTransitionTrigger(
                motionLayout: MotionLayout?,
                triggerId: Int,
                positive: Boolean,
                progress: Float
            ) {
                // do nothing
            }

            override fun onTransitionStarted(
                motionLayout: MotionLayout?,
                startId: Int,
                endId: Int
            ) {
                // do nothing
            }

            override fun onTransitionChange(
                motionLayout: MotionLayout?,
                startId: Int,
                endId: Int,
                progress: Float
            ) {
                // do nothing
            }
        })
    }

    private fun updateClock() {
        val homeFragmentContent = HomeFragmentContentBinding.bind(requireView())
        when (unlauncherDataSource.corePreferencesRepo.get().clockType) {
            ClockType.digital -> updateClockDigital()
            ClockType.analog -> homeFragmentContent.homeFragmentAnalogTime.updateClock()
            ClockType.binary -> homeFragmentContent.homeFragmentBinTime.updateClock()
            else -> {}
        }
    }

    private fun updateClockDigital() {
        val timeFormat = context?.getSharedPreferences(
            getString(R.string.prefs_settings),
            Context.MODE_PRIVATE
        )
            ?.getInt(getString(R.string.prefs_settings_key_time_format), 0)
        val fWatchTime = when (timeFormat) {
            1 -> SimpleDateFormat("H:mm", Locale.getDefault())
            2 -> SimpleDateFormat("h:mm aa", Locale.getDefault())
            else -> DateFormat.getTimeFormat(context)
        }
        val homeFragmentContent = HomeFragmentContentBinding.bind(requireView())
        homeFragmentContent.homeFragmentTime.text = fWatchTime.format(Date())
    }

    private fun updateDate() {
        val fWatchDate = SimpleDateFormat("'day' D 'of' YYYY,\n'that is' EEEE,\n'the' d'ord' 'of' MMMM", Locale.ENGLISH)
        val now = Date()
        val str = fWatchDate.format(now)
        val final = str.replace("ord", if (now.date == 11) "th" /* 11th not 11st */ else
            arrayOf("th", "st", "nd", "rd", "th", "th", "th", "th", "th", "th")[now.date % 10])

        val homeFragmentContent = HomeFragmentContentBinding.bind(requireView())
        homeFragmentContent.homeFragmentDate.text = final
    }

    override fun onLaunch(app: HomeApp, view: View) {
        // these are the apps on the home screen, not in the drawer
        launchAppRestricted(app.packageName, app.activityName, app.userSerial)
    }

    override fun onBack(): Boolean {
        val homeFragment = HomeFragmentDefaultBinding.bind(requireView()).root
        shuffleHomeApps()
        homeFragment.transitionToStart()
        return true
    }

    override fun onHome() {
        val homeFragment = HomeFragmentDefaultBinding.bind(requireView()).root
        shuffleHomeApps()
        homeFragment.transitionToStart()
    }

    inner class ClockReceiver : BroadcastReceiver() {
        @RequiresApi(Build.VERSION_CODES.O)
        override fun onReceive(ctx: Context?, intent: Intent?) {
            updateClockAndDate()
        }
    }

    private fun launchApp(packageName: String, activityName: String, userSerial: Long) {
        try {
            val manager = requireContext().getSystemService(Context.USER_SERVICE) as UserManager
            val launcher = requireContext().getSystemService(
                Context.LAUNCHER_APPS_SERVICE
            ) as LauncherApps

            val componentName = ComponentName(packageName, activityName)
            val userHandle = manager.getUserForSerialNumber(userSerial)

            launcher.startMainActivity(componentName, userHandle, view?.clipBounds, null)
        } catch (e: Exception) {
            // Do no shit yet
        }
    }

    private fun resetAppDrawerEditText() {
        HomeFragmentContentBinding.bind(requireView()).appDrawerEditText.apply {
            clearComposingText()
            setText("")
            clearFocus()
        }
    }

    private var appsClickable = true
    private fun launchAppRestricted(packageName: String, className: String, userSerial: Long) {
        if (!appsClickable) {
            Toast.makeText(context, "we are already busy...", Toast.LENGTH_SHORT).show()
            return
        }

        // some apps should be speedy (and always be launchable without inhibition)
        if (isFastTrackApp(packageName)) {
            launchApp(packageName, className, userSerial)
            return
        }
        else if (!OverlayService.isRunning()) {
            Toast.makeText(context, "service not running...", Toast.LENGTH_LONG).show()
            return
        } else if (Random.nextFloat() < 0.8)
            return  // haha

        val homeFragment = HomeFragmentDefaultBinding.bind(requireView()).root
        val handler = Handler(Looper.getMainLooper())

        // schizophrenia
        val (forbidden, wasSoftForbidden) = isForbiddenOrSoftForbiddenApp(packageName)
        if (forbidden) {
            val mainActivity = activity as MainActivity
            val mayaImageView = mainActivity.mayaImageView!!
            val mayaExitButton = mainActivity.mayaExitButton!!
            val mayaTextView = mainActivity.mayaTextView!!
            fun setBlockerVisibility(visibility: Int) {
                mayaImageView.visibility = visibility
                mayaExitButton.visibility = visibility
                mayaTextView.visibility = visibility
            }

            setBlockerVisibility(View.VISIBLE)
            Toast.makeText(context, if (wasSoftForbidden) "you've had too much..." else "launching forbidden application...", Toast.LENGTH_LONG).show()
            homeFragment.transitionToStart()

            var continueInvocation = true
            mayaExitButton.setOnClickListener {
                if (continueInvocation) {
                    Toast.makeText(context, "you will be free...", Toast.LENGTH_SHORT).show()
                    continueInvocation = false
                }
            }

            var i = 0
            var invocationFailRate = 0.17f
            OverlayService.onAppSwitchedListener = Runnable { invocationFailRate += 0.0425f }  // slightly punish user for not waiting at home screen
            handler.postDelayed(object : Runnable {
                private val vibrator = requireContext().getSystemService(Vibrator::class.java)
                private val keyguardManager = requireContext().getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

                @RequiresApi(Build.VERSION_CODES.LOLLIPOP_MR1)
                override fun run() {
                    if (i++ < INVOCATION_MAX) {
                        // screen locked, abort invocation
                        if (keyguardManager.isDeviceLocked) {
                            setBlockerVisibility(View.INVISIBLE)
                            return
                        }

                        if (continueInvocation) {
                            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                            Toast.makeText(context, "it is forbidden (${i}/${INVOCATION_MAX})...", Toast.LENGTH_LONG).show()
                            handler.postDelayed(this, INVOCATION_DELAY + (i * 30))
                            return
                        }

                        // cancel
                        setBlockerVisibility(View.INVISIBLE)
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                        Toast.makeText(context, "you are free...", Toast.LENGTH_LONG).show()
                        return
                    }

                    Toast.makeText(context,
                        "success rate: ${((1 - invocationFailRate) * 100).toString().split(".")[0]}%",
                        Toast.LENGTH_LONG).show()

                    handler.postDelayed({
                        // hide goddess
                        setBlockerVisibility(View.INVISIBLE)

                        // be annoying
                        if (Random.nextFloat() < invocationFailRate) {
                            Toast.makeText(context, "failed...", Toast.LENGTH_LONG).show()
                            return@postDelayed
                        }

                        // start brainrot
                        OverlayService.resetTimer(packageName)
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
                        Toast.makeText(context, "you will wither...", Toast.LENGTH_LONG).show()

                        // launch blasphemous app
                        launchApp(packageName, className, userSerial)
                        homeFragment.transitionToStart()
                    }, 3_000)
                }
            }, INVOCATION_DELAY)
        }

        else {
            appsClickable = false
            Toast.makeText(context, "invoking ${packageName}...", Toast.LENGTH_SHORT).show()
            homeFragment.transitionToStart()
            handler.postDelayed({
                Toast.makeText(context, "take care...", Toast.LENGTH_LONG).show()
            }, 4_000)
            handler.postDelayed({
                appsClickable = true
                launchApp(packageName, className, userSerial)
            }, 7_500)
        }
    }

    inner class AppDrawerListener {
        @SuppressLint("DiscouragedPrivateApi")
        fun onAppLongClicked(app: UnlauncherApp, view: View): Boolean {
            val popupMenu = PopupMenu(context, view)
            popupMenu.inflate(R.menu.app_long_press_menu)
            hideUninstallOptionIfSystemApp(app, popupMenu)

            popupMenu.setOnMenuItemClickListener { item: MenuItem? ->
                when (item!!.itemId) {
                    R.id.open -> {
                        onAppClicked(app)
                    }
                    R.id.info -> {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.addCategory(Intent.CATEGORY_DEFAULT)
                        intent.data = Uri.parse("package:" + app.packageName)
                        startActivity(intent)
                    }
                    R.id.hide -> {
                        unlauncherDataSource.unlauncherAppsRepo.updateDisplayInDrawer(app, false)
                        Toast.makeText(
                            context,
                            "Unhide under Unlauncher's Options > Customize Drawer > Visible Apps",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    R.id.rename -> {
                        RenameAppDisplayNameDialog.getInstance(
                            app,
                            unlauncherDataSource.unlauncherAppsRepo
                        ).show(childFragmentManager, "AppListAdapter")
                    }
                    R.id.uninstall -> {
                        val intent = Intent(Intent.ACTION_DELETE)
                        intent.data = Uri.parse("package:" + app.packageName)
                        uninstallAppLauncher.launch(intent)
                    }
                }
                true
            }

            val fieldMPopup = PopupMenu::class.java.getDeclaredField("mPopup")
            fieldMPopup.isAccessible = true
            val mPopup = fieldMPopup.get(popupMenu)
            mPopup.javaClass
                .getDeclaredMethod("setForceShowIcon", Boolean::class.java)
                .invoke(mPopup, true)

            popupMenu.show()
            return true
        }

        private fun hideUninstallOptionIfSystemApp(app: UnlauncherApp, popupMenu: PopupMenu) {
            val pm = requireContext().packageManager
            val info = pm.getApplicationInfo(app.packageName, 0)
            if (info.isSystemApp()) {
                val uninstallMenuItem = popupMenu.menu.findItem(R.id.uninstall)
                uninstallMenuItem.isVisible = false
            }
        }

        fun onAppClicked(app: UnlauncherApp) {
            // these are the apps in the app drawer
            launchAppRestricted(app.packageName, app.className, app.userSerial)
        }
    }

    @Throws(IOException::class)
    fun openMediaStream(context: Context, mimeType: String, displayName: String): OutputStream {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/lifetime")
        }

        val resolver = context.contentResolver
        var uri: Uri? = null

        try {
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Failed to create new MediaStore record.")

            return resolver.openOutputStream(uri) ?: throw IOException("Failed to open output stream.")
        } catch (e: IOException) {
            uri?.let { orphanUri ->
                // Don't leave an orphan entry in the MediaStore
                resolver.delete(orphanUri, null, null)
            }
            throw e
        }
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private var imageCapture: ImageCapture? = null

    private fun takePhoto(displayName: String) {
        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.CAMERA), 10)
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            imageCapture = ImageCapture.Builder()
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetResolution(Size(2160, 3840))
                .build().also {
                    cameraProvider.bindToLifecycle(viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, it)
                }

            val stream = openMediaStream(requireContext(), "image/jpeg", displayName)
            val outputFileOptions = ImageCapture.OutputFileOptions.Builder(stream).build()
            imageCapture!!.takePicture(outputFileOptions, CameraXExecutors.mainThreadExecutor(),
                object : ImageCapture.OnImageSavedCallback {
                    @RequiresApi(Build.VERSION_CODES.M)
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        cameraProvider.unbindAll()
                        val vibrator = requireContext().getSystemService(Vibrator::class.java)
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                        Toast.makeText(requireContext(), "you won't unsee...", Toast.LENGTH_SHORT).show()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cameraProvider.unbindAll()  // release resources
                        Log.e("CAMERA", exception.stackTraceToString())
                        Toast.makeText(requireContext(), "error taking image!", Toast.LENGTH_LONG).show()
                    }
                })
        }, CameraXExecutors.mainThreadExecutor())
    }
}
