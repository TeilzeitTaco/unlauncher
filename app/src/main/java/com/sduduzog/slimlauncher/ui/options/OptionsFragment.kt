package com.sduduzog.slimlauncher.ui.options

import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.navigation.Navigation
import com.sduduzog.slimlauncher.OverlayService
import com.sduduzog.slimlauncher.R
import com.sduduzog.slimlauncher.databinding.OptionsFragmentBinding
import com.sduduzog.slimlauncher.datasource.UnlauncherDataSource
import com.sduduzog.slimlauncher.ui.dialogs.ChangeThemeDialog
import com.sduduzog.slimlauncher.ui.dialogs.ChooseAlignmentDialog
import com.sduduzog.slimlauncher.ui.dialogs.ChooseClockTypeDialog
import com.sduduzog.slimlauncher.ui.dialogs.ChooseTimeFormatDialog
import com.sduduzog.slimlauncher.utils.BaseFragment
import com.sduduzog.slimlauncher.utils.createTitleAndSubtitleText
import com.sduduzog.slimlauncher.utils.isActivityDefaultLauncher
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.IOException
import java.text.SimpleDateFormat
import javax.inject.Inject


@AndroidEntryPoint
class OptionsFragment : BaseFragment() {
    @Inject
    lateinit var unlauncherDataSource: UnlauncherDataSource

    override fun getFragmentView(): ViewGroup = OptionsFragmentBinding.bind(
        requireView()
    ).optionsFragment

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.options_fragment, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        val optionsFragment = OptionsFragmentBinding.bind(requireView())
        optionsFragment.optionsFragmentDeviceSettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_SETTINGS)
            launchActivity(it, intent)
        }
        optionsFragment.optionsFragmentBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        optionsFragment.optionsFragmentDeviceSettings.setOnLongClickListener {
            val intent = Intent(Settings.ACTION_HOME_SETTINGS)
            launchActivity(it, intent)
            true
        }
        optionsFragment.optionsFragmentChangeTheme.setOnClickListener {
            val changeThemeDialog = ChangeThemeDialog.getThemeChooser()
            changeThemeDialog.showNow(childFragmentManager, "THEME_CHOOSER")
        }
        optionsFragment.optionsFragmentChooseTimeFormat.setOnClickListener {
            val chooseTimeFormatDialog = ChooseTimeFormatDialog.getInstance()
            chooseTimeFormatDialog.showNow(childFragmentManager, "TIME_FORMAT_CHOOSER")
        }
        optionsFragment.optionsFragmentChooseClockType.setOnClickListener {
            val chooseClockTypeDialog = ChooseClockTypeDialog.getInstance()
            chooseClockTypeDialog.showNow(childFragmentManager, "CLOCK_TYPE_CHOOSER")
        }
        optionsFragment.optionsFragmentChooseAlignment.setOnClickListener {
            val chooseAlignmentDialog = ChooseAlignmentDialog.getInstance()
            chooseAlignmentDialog.showNow(childFragmentManager, "ALIGNMENT_CHOOSER")
        }
        optionsFragment.optionsFragmentToggleStatusBar.setOnClickListener {
            val settings = requireContext().getSharedPreferences(
                getString(R.string.prefs_settings),
                MODE_PRIVATE
            )
            val isHidden = settings.getBoolean(
                getString(R.string.prefs_settings_key_toggle_status_bar),
                false
            )
            settings.edit {
                putBoolean(getString(R.string.prefs_settings_key_toggle_status_bar), !isHidden)
            }
        }
        optionsFragment.optionsFragmentCustomiseApps.setOnClickListener(
            Navigation.createNavigateOnClickListener(
                R.id.action_optionsFragment_to_customiseAppsFragment
            )
        )
        optionsFragment.optionsFragmentCustomizeQuickButtons.setOnClickListener(
            Navigation.createNavigateOnClickListener(
                R.id.action_optionsFragment_to_customiseQuickButtonsFragment
            )
        )
        optionsFragment.optionsFragmentCustomizeAppDrawer.setOnClickListener(
            Navigation.createNavigateOnClickListener(
                R.id.action_optionsFragment_to_customiseAppDrawerFragment
            )
        )

        val ok = OkHttpClient()

        fun waitToast() {
            Toast.makeText(context, "Contacting server...", Toast.LENGTH_LONG).show()
        }

        fun errorOnUi(callback: () -> (Unit)) {
            activity?.runOnUiThread {
                Toast.makeText(context ?: return@runOnUiThread,
                    "Error connecting to server!", Toast.LENGTH_LONG).show()
                callback()
            }
        }

        fun showLastNumbers(callback: () -> (Unit)) {
            waitToast()
            val request = Request.Builder()
                .url("https://flesh-network.ddns.net/raw_last_numbers")
                .build()

            ok.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    errorOnUi(callback)
                }

                override fun onResponse(call: okhttp3.Call, response: Response) {
                    val result = response.body?.string() ?: ""
                    activity?.runOnUiThread {
                        AlertDialog.Builder(requireContext(), R.style.AppFleshNetworkDialogTheme)
                            .apply {
                                setPositiveButton("Affirm") { _, _ -> callback() }
                                setTitle("Last Series Numbers")
                                setMessage("The last document numbers are:\n\n$result")
                                create()
                                show()
                            }
                    }
                }
            })
        }

        fun doRawQuery(callback: () -> (Unit)) {
            val input = EditText(requireContext()).apply {
                setSingleLine()
                hint = "Enter query..."
            }
            AlertDialog.Builder(requireContext(), R.style.AppFleshNetworkDialogTheme)
                .setTitle("Perform Raw Query")
                .setMessage("The server will be queried with the supplied query string.")
                .setView(input)
                .setPositiveButton(
                    "Affirm"
                ) { _, _ ->
                    val query = input.text.trim()
                    query.ifEmpty {
                        callback()
                        return@setPositiveButton
                    }

                    waitToast()
                    val request = Request.Builder()
                        .url("https://flesh-network.ddns.net/raw_query/$query")
                        .build()

                    ok.newCall(request).enqueue(object : Callback {
                        override fun onFailure(call: okhttp3.Call, e: IOException) {
                            errorOnUi(callback)
                        }

                        override fun onResponse(call: okhttp3.Call, response: Response) {
                            val result = (response.body?.string()?.trim() ?: "").ifEmpty { "(no results)" }
                            activity?.runOnUiThread {
                                AlertDialog.Builder(requireContext(), R.style.AppFleshNetworkDialogTheme)
                                    .apply {
                                        setPositiveButton("Affirm") { _, _ -> callback() }
                                        setTitle("Raw Query Results")
                                        setMessage("The results of the query \"$query\" are:\n\n${result}")
                                        create()
                                        show()
                                    }
                            }
                        }
                    })
                }
                .setNegativeButton("Cancel") { _, _ -> callback() }.show()
        }

        fun resolveTag(callback: () -> (Unit)) {
            val input = EditText(requireContext()).apply {
                setSingleLine()
                hint = "Enter tag..."
            }
            AlertDialog.Builder(requireContext(), R.style.AppFleshNetworkDialogTheme)
                .setTitle("Resolve Tag")
                .setMessage("The server will be asked to resolve the supplied tag.")
                .setView(input)
                .setPositiveButton(
                    "Affirm"
                ) { _, _ ->
                    val tag = input.text.trim().toString().lowercase()
                    tag.ifEmpty {
                        callback()
                        return@setPositiveButton
                    }

                    Intent(Intent.ACTION_VIEW).also {
                        it.setData(Uri.parse("https://flesh-network.ddns.net/resolve/$tag"))
                        startActivity(it)
                    }
                }
                .setNegativeButton("Cancel") { _, _ -> callback() }.show()
        }

        fun showFnOptions() {
            AlertDialog.Builder(requireContext(), R.style.AppFleshNetworkDialogTheme).setItems(
                arrayOf("1. Perform Raw Query", "2. Show Last Numbers", "3. Resolve Tag")
            ) { _, which ->
                when (which) {
                    0 -> doRawQuery { showFnOptions() }
                    1 -> showLastNumbers { showFnOptions() }
                    2 -> resolveTag { showFnOptions() }
                }
            }.setPositiveButton("Exit") { _, _ -> }
                .setTitle("Perform Flesh-Network Action")
                .create().show()
        }

        // fn ops, implemented ksana7312.52508602384
        optionsFragment.optionsFragmentShowFnOptions.setOnClickListener {
            showFnOptions()
        }

        val sdf = SimpleDateFormat("MM/dd HH:mm:ss")
        optionsFragment.optionsFragmentShowLog.setOnClickListener {
            AlertDialog.Builder(requireContext(), R.style.AppFleshNetworkDialogTheme).apply {
                setPositiveButton("Affirm") { _, _ -> }
                setTitle("Activity Log")
                setMessage("Applications viewed this session in sequence (most recent first):\n\n" + OverlayService.activityLog.reversed()
                    .joinToString(separator = "\n") { m ->
                        "${sdf.format(m.date)}:\n${
                            m.packageName.replace("com.", "").replace("google.android.", "")
                        }"
                    }.ifEmpty { "(none)" })
                create()
                show()
            }
        }

        var f = 0
        optionsFragment.fleuron.setOnClickListener {
            optionsFragment.fleuron.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            optionsFragment.fleuron.text = "❀"
            if (f++ == 0) return@setOnClickListener

            optionsFragment.fleuron.textScaleX += 0.333f
            if (f == 33) {
                Toast.makeText(requireContext(),"don't gild the lily...", Toast.LENGTH_LONG).show()
                val handler = Handler(requireContext().mainLooper)
                handler.post(object : Runnable {
                    override fun run() {
                        optionsFragment.fleuron.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        optionsFragment.fleuron.textScaleX -= 0.333f
                        if (--f > 1) {
                            handler.postDelayed(this, 50)
                        }
                    }
                })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // setting up the switch text, since changing the default launcher re-starts the activity
        // this should able to adapt to it.
        setupAutomaticDeviceWallpaperSwitch()
    }

    private fun setupAutomaticDeviceWallpaperSwitch() {
        val prefsRepo = unlauncherDataSource.corePreferencesRepo
        val appIsDefaultLauncher = isActivityDefaultLauncher(activity)
        val optionsFragment = OptionsFragmentBinding.bind(requireView())
        setupDeviceWallpaperSwitchText(optionsFragment, appIsDefaultLauncher)
        optionsFragment.optionsFragmentAutoDeviceThemeWallpaper.isEnabled = appIsDefaultLauncher

        prefsRepo.liveData().observe(viewLifecycleOwner) {
            // always uncheck once app isn't default launcher
            optionsFragment.optionsFragmentAutoDeviceThemeWallpaper
                .isChecked = appIsDefaultLauncher && !it.keepDeviceWallpaper
        }
        optionsFragment.optionsFragmentAutoDeviceThemeWallpaper
            .setOnCheckedChangeListener { _, checked ->
                prefsRepo.updateKeepDeviceWallpaper(!checked)
            }
    }

    /**
     * Adds a hint text underneath the default text when app is not the default launcher.
     */
    private fun setupDeviceWallpaperSwitchText(
        optionsFragment: OptionsFragmentBinding,
        appIsDefaultLauncher: Boolean
    ) {
        val text = if (appIsDefaultLauncher) {
            getText(R.string.customize_app_drawer_fragment_auto_theme_wallpaper_text)
        } else {
            buildSwitchTextWithHint()
        }
        optionsFragment.optionsFragmentAutoDeviceThemeWallpaper.text = text
    }

    private fun buildSwitchTextWithHint(): CharSequence {
        val titleText = getText(R.string.customize_app_drawer_fragment_auto_theme_wallpaper_text)
        // have a title text and a subtitle text to indicate that adapting the
        // wallpaper can only be done when app it the default launcher
        val subTitleText = getText(
            R.string.customize_app_drawer_fragment_auto_theme_wallpaper_subtext_no_default_launcher
        )
        return createTitleAndSubtitleText(requireContext(), titleText, subTitleText)
    }
}
