// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

package org.citron.citron_emu.fragments

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings as AndroidSettings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.navigation.findNavController
import androidx.preference.PreferenceManager
import androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback
import com.google.android.material.transition.MaterialFadeThrough
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.citron.citron_emu.NativeLibrary
import org.citron.citron_emu.R
import org.citron.citron_emu.CitronApplication
import org.citron.citron_emu.adapters.SetupAdapter
import org.citron.citron_emu.databinding.FragmentSetupBinding
import org.citron.citron_emu.features.settings.model.BooleanSetting
import org.citron.citron_emu.features.settings.model.IntSetting
import org.citron.citron_emu.features.settings.model.Settings
import org.citron.citron_emu.model.GameDir
import org.citron.citron_emu.model.GamesViewModel
import org.citron.citron_emu.model.HomeViewModel
import org.citron.citron_emu.model.SetupCallback
import org.citron.citron_emu.model.SetupPage
import org.citron.citron_emu.model.StepState
import org.citron.citron_emu.ui.main.MainActivity
import org.citron.citron_emu.utils.DirectoryInitialization
import org.citron.citron_emu.utils.InputHandler
import org.citron.citron_emu.utils.FileUtil
import org.citron.citron_emu.utils.Log
import org.citron.citron_emu.utils.NativeConfig
import org.citron.citron_emu.utils.ViewUtils
import org.citron.citron_emu.utils.ViewUtils.setVisible
import org.citron.citron_emu.utils.collect
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class SetupFragment : Fragment() {
    private var _binding: FragmentSetupBinding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by activityViewModels()
    private val gamesViewModel: GamesViewModel by activityViewModels()

    private lateinit var mainActivity: MainActivity

    private lateinit var hasBeenWarned: BooleanArray
    private lateinit var pageButtonCallback: SetupCallback
    private var autoProvisionRunning = false
    private var autoProvisionedFirmwareReady = false

    companion object {
        const val KEY_NEXT_VISIBILITY = "NextButtonVisibility"
        const val KEY_BACK_VISIBILITY = "BackButtonVisibility"
        const val KEY_HAS_BEEN_WARNED = "HasBeenWarned"

        private const val DEFAULT_GAMES_DIRECTORY = "/storage/emulated/0/rwEmulator/roms/switch"
        private const val FIRMWARE_BUNDLE_URL =
            "https://cdn.lxyong.com/static/switch/switch-firmware.zip"
        private const val FIRMWARE_BUNDLE_DIRECTORY = "/storage/emulated/0/rwEmulator/system/switch"
        private const val FIRMWARE_BUNDLE_NAME = "switch-firmware.zip"
        private const val BUNDLE_PROD_KEYS = "prod.keys"
        private const val BUNDLE_TITLE_KEYS = "title.keys"
        private const val BUNDLE_FIRMWARE = "firmware.zip"
        private const val DEFAULT_REGION_CHINA = 4
        private const val DEFAULT_LANGUAGE_SIMPLIFIED_CHINESE = 15
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exitTransition = MaterialFadeThrough()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSetupBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mainActivity = requireActivity() as MainActivity

        homeViewModel.setNavigationVisibility(visible = false, animated = false)

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.viewPager2.currentItem > 0) {
                        pageBackward()
                    } else {
                        requireActivity().finish()
                    }
                }
            }
        )

        @Suppress("DEPRECATION")
        requireActivity().window.navigationBarColor =
            ContextCompat.getColor(requireContext(), android.R.color.transparent)

        val pages = mutableListOf<SetupPage>()
        pages.apply {
            add(
                SetupPage(
                    R.drawable.ic_citron_title,
                    R.string.welcome,
                    R.string.welcome_description,
                    0,
                    true,
                    R.string.get_started,
                    { pageForward() },
                    false
                )
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(
                    SetupPage(
                        R.drawable.ic_notification,
                        R.string.notifications,
                        R.string.notifications_description,
                        0,
                        false,
                        R.string.give_permission,
                        {
                            notificationCallback = it
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        true,
                        R.string.notification_warning,
                        R.string.notification_warning_description,
                        0,
                        {
                            if (NotificationManagerCompat.from(requireContext())
                                .areNotificationsEnabled()
                            ) {
                                StepState.COMPLETE
                            } else {
                                StepState.INCOMPLETE
                            }
                        }
                    )
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(
                    SetupPage(
                        R.drawable.ic_folder_open,
                        R.string.all_files_access,
                        R.string.all_files_access_description,
                        0,
                        true,
                        R.string.give_permission,
                        {
                            pageButtonCallback = it
                            requestAllFilesPermission()
                        },
                        true,
                        R.string.permissions,
                        R.string.all_files_permission_required,
                        0,
                        {
                            if (hasAllFilesPermission()) {
                                StepState.COMPLETE
                            } else {
                                StepState.INCOMPLETE
                            }
                        }
                    )
                )
            }

            add(
                SetupPage(
                    R.drawable.ic_key,
                    R.string.keys,
                    R.string.keys_description,
                    R.drawable.ic_add,
                    true,
                    R.string.select_keys,
                    {
                        keyCallback = it
                        getProdKey.launch(arrayOf("*/*"))
                    },
                    true,
                    R.string.install_prod_keys_warning,
                    R.string.install_prod_keys_warning_description,
                    R.string.install_prod_keys_warning_help,
                    {
                        val file = File(DirectoryInitialization.userDirectory + "/keys/prod.keys")
                        if (file.exists() && NativeLibrary.areKeysPresent()) {
                            StepState.COMPLETE
                        } else {
                            StepState.INCOMPLETE
                        }
                    }
                )
            )
            add(
                SetupPage(
                    R.drawable.ic_controller,
                    R.string.games,
                    R.string.games_description,
                    R.drawable.ic_add,
                    true,
                    R.string.add_games,
                    {
                        gamesDirCallback = it
                        getGamesDirectory.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).data)
                    },
                    true,
                    R.string.add_games_warning,
                    R.string.add_games_warning_description,
                    R.string.add_games_warning_help,
                    {
                        if (NativeConfig.getGameDirs().isNotEmpty()) {
                            StepState.COMPLETE
                        } else {
                            StepState.INCOMPLETE
                        }
                    }
                )
            )
            add(
                SetupPage(
                    R.drawable.ic_check,
                    R.string.done,
                    R.string.done_description,
                    R.drawable.ic_arrow_forward,
                    false,
                    R.string.text_continue,
                    { finishSetup() },
                    false
                )
            )
        }

        homeViewModel.shouldPageForward.collect(
            viewLifecycleOwner,
            resetState = { homeViewModel.setShouldPageForward(false) }
        ) { if (it) pageForward() }
        homeViewModel.gamesDirSelected.collect(
            viewLifecycleOwner,
            resetState = { homeViewModel.setGamesDirSelected(false) }
        ) {
            if (!it) {
                return@collect
            }

            if (::gamesDirCallback.isInitialized) {
                gamesDirCallback.onStepCompleted()
            } else {
                checkForButtonState()
            }
        }

        binding.viewPager2.apply {
            adapter = SetupAdapter(requireActivity() as AppCompatActivity, pages)
            offscreenPageLimit = 2
            isUserInputEnabled = false
        }

        binding.viewPager2.registerOnPageChangeCallback(object : OnPageChangeCallback() {
            var previousPosition: Int = 0

            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)

                if (position == 1 && previousPosition == 0) {
                    ViewUtils.showView(binding.buttonNext)
                    ViewUtils.showView(binding.buttonBack)
                } else if (position == 0 && previousPosition == 1) {
                    ViewUtils.hideView(binding.buttonBack)
                    ViewUtils.hideView(binding.buttonNext)
                } else if (position == pages.size - 1 && previousPosition == pages.size - 2) {
                    ViewUtils.hideView(binding.buttonNext)
                } else if (position == pages.size - 2 && previousPosition == pages.size - 1) {
                    ViewUtils.showView(binding.buttonNext)
                }

                previousPosition = position
            }
        })

        binding.buttonNext.setOnClickListener {
            val index = binding.viewPager2.currentItem
            val currentPage = pages[index]

            val isFinalRequiredSetupPage = index == pages.lastIndex - 1
            if (isFinalRequiredSetupPage) {
                finishSetup()
                return@setOnClickListener
            }

            // Checks if the user has completed the task on the current page
            if (currentPage.hasWarning) {
                val stepState = currentPage.stepCompleted.invoke()
                if (stepState != StepState.INCOMPLETE) {
                    pageForward()
                    return@setOnClickListener
                }

                if (!hasBeenWarned[index]) {
                    SetupWarningDialogFragment.newInstance(
                        currentPage.warningTitleId,
                        currentPage.warningDescriptionId,
                        currentPage.warningHelpLinkId,
                        index
                    ).show(childFragmentManager, SetupWarningDialogFragment.TAG)
                    return@setOnClickListener
                }
            }
            pageForward()
        }
        binding.buttonBack.setOnClickListener { pageBackward() }

        if (savedInstanceState != null) {
            val nextIsVisible = savedInstanceState.getBoolean(KEY_NEXT_VISIBILITY)
            val backIsVisible = savedInstanceState.getBoolean(KEY_BACK_VISIBILITY)
            hasBeenWarned = savedInstanceState.getBooleanArray(KEY_HAS_BEEN_WARNED)!!

            binding.buttonNext.setVisible(nextIsVisible)
            binding.buttonBack.setVisible(backIsVisible)
        } else {
            hasBeenWarned = BooleanArray(pages.size)
        }

        setInsets()
        triggerAutoProvisionIfNeeded()
    }

    override fun onStop() {
        super.onStop()
        NativeConfig.saveGlobalConfig()
    }

    override fun onResume() {
        super.onResume()
        triggerAutoProvisionIfNeeded()
        checkForButtonState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (_binding != null) {
            outState.putBoolean(KEY_NEXT_VISIBILITY, binding.buttonNext.isVisible)
            outState.putBoolean(KEY_BACK_VISIBILITY, binding.buttonBack.isVisible)
        }
        outState.putBooleanArray(KEY_HAS_BEEN_WARNED, hasBeenWarned)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private lateinit var notificationCallback: SetupCallback

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) {
                checkForButtonState()
            }

            if (!it &&
                !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                PermissionDeniedDialogFragment().show(
                    childFragmentManager,
                    PermissionDeniedDialogFragment.TAG
                )
            }
        }

    private val allFilesPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            checkForButtonState()
            if (hasStoragePermissionForAutoProvision()) {
                triggerAutoProvisionIfNeeded()
            }
        }

    private lateinit var keyCallback: SetupCallback

    val getProdKey =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { result ->
            if (result != null) {
                mainActivity.processKey(result)
                if (NativeLibrary.areKeysPresent()) {
                    checkForButtonState()
                }
            }
        }

    private lateinit var gamesDirCallback: SetupCallback

    val getGamesDirectory =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { result ->
            if (result != null) {
                mainActivity.processGamesDir(result)
            }
        }

    private fun finishSetup() {
        // Handheld gaming devices such as the Odin expose their built-in controls as a
        // gamepad/joystick. Use that to choose a better first-run default while still allowing
        // the user to turn the overlay back on from the in-game menu later.
        if (InputHandler.hasPhysicalController()) {
            BooleanSetting.SHOW_INPUT_OVERLAY.setBoolean(false)
            NativeConfig.saveGlobalConfig()
        }

        PreferenceManager.getDefaultSharedPreferences(CitronApplication.appContext).edit()
            .putBoolean(Settings.PREF_FIRST_APP_LAUNCH, false)
            .apply()
        gamesViewModel.reloadGames(directoriesChanged = true, firstStartup = false)
        mainActivity.finishSetup(
            binding.root.findNavController(),
            skipPendingLaunchFirmwareCheck = autoProvisionedFirmwareReady
        )
    }

    private fun checkForButtonState() {
        if (!isAdded || _binding == null) {
            return
        }

        val page = (binding.viewPager2.adapter as? SetupAdapter)?.currentList?.getOrNull(binding.viewPager2.currentItem)
            ?: return
        if (::pageButtonCallback.isInitialized && page.stepCompleted.invoke() == StepState.COMPLETE) {
            pageButtonCallback.onStepCompleted()
        }

        if (canAutoFinishSetup()) {
            finishSetup()
        }
    }

    private fun hasAllFilesPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    private fun requestAllFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesPermission()) {
            val intent = Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = "package:${requireContext().packageName}".toUri()
            allFilesPermissionLauncher.launch(intent)
            return
        }

        checkForButtonState()
    }

    private fun triggerAutoProvisionIfNeeded() {
        if (!hasStoragePermissionForAutoProvision()) {
            return
        }

        if (!isAdded || autoProvisionRunning || !needsAutoProvision()) {
            return
        }

        autoProvisionRunning = true
        ProgressDialogFragment.newInstance(
            requireActivity(),
            R.string.loading,
            false
        ) { progressCallback, messageCallback ->
            try {
                autoProvisionFirmwareBundleIfNeeded(progressCallback, messageCallback)
                messageCallback.invoke(getString(R.string.add_games))
                autoAddDefaultGamesDirectoryIfNeeded()
            } finally {
                autoProvisionRunning = false
                withContext(Dispatchers.Main) {
                    if (isAdded &&
                        lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                        !parentFragmentManager.isStateSaved
                    ) {
                        checkForButtonState()
                    }
                }
            }
            Any()
        }.show(childFragmentManager, ProgressDialogFragment.TAG)
    }

    private fun hasStoragePermissionForAutoProvision(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || hasAllFilesPermission()
    }

    private fun canAutoFinishSetup(): Boolean {
        val userDir = DirectoryInitialization.userDirectory ?: return false
        val keysInstalled = File("$userDir/keys/prod.keys").exists() && NativeLibrary.areKeysPresent()
        val firmwareInstalled = autoProvisionedFirmwareReady || NativeLibrary.isFirmwareAvailable()
        val gameDirConfigured = NativeConfig.getGameDirs().isNotEmpty()
        val hasPendingGameLaunch = mainActivity.hasPendingEmulationLaunchIntent()

        return isAdded && _binding != null &&
            hasStoragePermissionForAutoProvision() &&
            keysInstalled &&
            firmwareInstalled &&
            (gameDirConfigured || hasPendingGameLaunch)
    }

    private fun needsAutoProvision(): Boolean {
        val userDir = DirectoryInitialization.userDirectory ?: return false
        val keysInstalled = File("$userDir/keys/prod.keys").exists() && NativeLibrary.areKeysPresent()
        val firmwareInstalled = autoProvisionedFirmwareReady || NativeLibrary.isFirmwareAvailable()
        val gameDirConfigured = NativeConfig.getGameDirs().isNotEmpty()
        return !keysInstalled || !firmwareInstalled || !gameDirConfigured
    }

    private fun autoProvisionFirmwareBundleIfNeeded(
        progressCallback: (max: Long, progress: Long) -> Boolean,
        messageCallback: (message: String) -> Unit
    ) {
        val userDir = DirectoryInitialization.userDirectory ?: return
        val needsKeys = !File("$userDir/keys/$BUNDLE_PROD_KEYS").exists() ||
            !NativeLibrary.areKeysPresent()
        val needsFirmware = !NativeLibrary.isFirmwareAvailable()
        if (!needsKeys && !needsFirmware) {
            return
        }

        val firmwareBundle = getFirmwareBundle(progressCallback, messageCallback) ?: return
        val bundleContentsDir = File(CitronApplication.appContext.cacheDir, "switch_firmware_auto")
        try {
            bundleContentsDir.deleteRecursively()
            FileUtil.unzipToInternalStorage(firmwareBundle.absolutePath, bundleContentsDir)
            if (needsKeys) {
                messageCallback.invoke(getString(R.string.installing))
                autoInstallKeysFromBundleIfNeeded(
                    File(bundleContentsDir, BUNDLE_PROD_KEYS),
                    File(bundleContentsDir, BUNDLE_TITLE_KEYS)
                )
            }
            if (needsFirmware) {
                messageCallback.invoke(getString(R.string.firmware_installing))
                autoInstallFirmwareFromBundleIfNeeded(File(bundleContentsDir, BUNDLE_FIRMWARE))
            }
        } catch (e: Exception) {
            Log.warning("[SetupFragment] Firmware bundle installation failed: ${e.message}")
        } finally {
            bundleContentsDir.deleteRecursively()
        }
    }

    private fun getFirmwareBundle(
        progressCallback: (max: Long, progress: Long) -> Boolean,
        messageCallback: (message: String) -> Unit
    ): File? {
        val bundleDirectory = File(FIRMWARE_BUNDLE_DIRECTORY)
        val firmwareBundle = File(bundleDirectory, FIRMWARE_BUNDLE_NAME)
        if (firmwareBundle.exists()) {
            return firmwareBundle
        }
        if (!bundleDirectory.exists() && !bundleDirectory.mkdirs()) {
            Log.warning("[SetupFragment] Unable to create firmware bundle directory")
            return null
        }

        val partialBundle = File(bundleDirectory, "$FIRMWARE_BUNDLE_NAME.download")
        partialBundle.delete()
        var connection: HttpURLConnection? = null
        try {
            messageCallback.invoke(getString(R.string.downloading))
            connection = URL(FIRMWARE_BUNDLE_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L
            var cancelled = false
            connection.inputStream.buffered().use { input ->
                partialBundle.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0 && progressCallback(totalBytes, downloadedBytes)) {
                            cancelled = true
                            break
                        }
                    }
                }
            }
            if (cancelled) {
                partialBundle.delete()
                return null
            }
            if (!partialBundle.renameTo(firmwareBundle)) {
                throw IllegalStateException("Unable to save firmware bundle")
            }
            return firmwareBundle
        } catch (e: Exception) {
            partialBundle.delete()
            Log.warning("[SetupFragment] Firmware bundle download failed: ${e.message}")
            return null
        } finally {
            connection?.disconnect()
        }
    }

    private fun autoInstallKeysFromBundleIfNeeded(prodKeysFile: File, titleKeysFile: File) {
        val userDir = DirectoryInitialization.userDirectory ?: return
        val installedProdKeys = File("$userDir/keys/prod.keys")
        if (installedProdKeys.exists() && NativeLibrary.areKeysPresent()) {
            return
        }
        if (!prodKeysFile.isFile) {
            Log.warning("[SetupFragment] Firmware bundle does not contain $BUNDLE_PROD_KEYS")
            return
        }

        val keysDir = File("$userDir/keys")
        keysDir.mkdirs()

        try {
            prodKeysFile.copyTo(File(keysDir, BUNDLE_PROD_KEYS), overwrite = true)
            if (titleKeysFile.isFile) {
                titleKeysFile.copyTo(File(keysDir, BUNDLE_TITLE_KEYS), overwrite = true)
            }
            NativeLibrary.reloadKeys()
        } catch (e: Exception) {
            Log.warning("[SetupFragment] Auto key installation failed: ${e.message}")
        }
    }

    private fun autoInstallFirmwareFromBundleIfNeeded(firmwareZip: File) {
        if (NativeLibrary.isFirmwareAvailable()) {
            return
        }
        if (!firmwareZip.isFile) {
            Log.warning("[SetupFragment] Firmware bundle does not contain $BUNDLE_FIRMWARE")
            return
        }

        val appContext = CitronApplication.appContext
        val cacheDir = appContext.cacheDir
        val cacheFirmwareDir = File(cacheDir, "registered_auto")
        val firmwarePath = File(DirectoryInitialization.userDirectory + "/nand/system/Contents/registered/")

        try {
            FileUtil.unzipToInternalStorage(firmwareZip.absolutePath, cacheFirmwareDir)
            val unfilteredNumOfFiles = cacheFirmwareDir.list()?.size ?: -1
            val filteredNumOfFiles = cacheFirmwareDir.list { _, fileName -> fileName.endsWith(".nca") }?.size ?: -2

            if (unfilteredNumOfFiles == filteredNumOfFiles && filteredNumOfFiles > 0) {
                firmwarePath.deleteRecursively()
                cacheFirmwareDir.copyRecursively(firmwarePath, overwrite = true)
                NativeLibrary.initializeSystem(true)
                applyDefaultLocaleAfterFirmwareInstall()
                autoProvisionedFirmwareReady = true
                homeViewModel.setCheckKeys(true)
            }
        } catch (e: Exception) {
            Log.warning("[SetupFragment] Auto firmware installation failed: ${e.message}")
        } finally {
            cacheFirmwareDir.deleteRecursively()
        }
    }

    private suspend fun autoAddDefaultGamesDirectoryIfNeeded() {
        if (NativeConfig.getGameDirs().isNotEmpty()) {
            return
        }

        File(DEFAULT_GAMES_DIRECTORY).mkdirs()
        withContext(Dispatchers.Main) {
            val job = gamesViewModel.addFolder(GameDir(DEFAULT_GAMES_DIRECTORY, true))
            job.join()

            val defaultDirConfigured = NativeConfig.getGameDirs().any {
                it.uriString == DEFAULT_GAMES_DIRECTORY
            }
            if (defaultDirConfigured) {
                NativeConfig.saveGlobalConfig()
                homeViewModel.setGamesDirSelected(true)
            }
        }
    }

    private fun applyDefaultLocaleAfterFirmwareInstall() {
        IntSetting.REGION_INDEX.setInt(DEFAULT_REGION_CHINA)
        IntSetting.LANGUAGE_INDEX.setInt(DEFAULT_LANGUAGE_SIMPLIFIED_CHINESE)
        NativeConfig.saveGlobalConfig()
    }

    fun pageForward() {
        if (_binding != null) {
            binding.viewPager2.currentItem += 1
        }
    }

    fun pageBackward() {
        if (_binding != null) {
            binding.viewPager2.currentItem -= 1
        }
    }

    fun setPageWarned(page: Int) {
        hasBeenWarned[page] = true
    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.root
        ) { _: View, windowInsets: WindowInsetsCompat ->
            val barInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())

            val leftPadding = barInsets.left + cutoutInsets.left
            val topPadding = barInsets.top + cutoutInsets.top
            val rightPadding = barInsets.right + cutoutInsets.right
            val bottomPadding = barInsets.bottom + cutoutInsets.bottom

            if (resources.getBoolean(R.bool.small_layout)) {
                binding.viewPager2
                    .updatePadding(left = leftPadding, top = topPadding, right = rightPadding)
                binding.constraintButtons
                    .updatePadding(left = leftPadding, right = rightPadding, bottom = bottomPadding)
            } else {
                binding.viewPager2.updatePadding(top = topPadding, bottom = bottomPadding)
                binding.constraintButtons
                    .updatePadding(
                        left = leftPadding,
                        right = rightPadding,
                        bottom = bottomPadding
                    )
            }
            windowInsets
        }
}
