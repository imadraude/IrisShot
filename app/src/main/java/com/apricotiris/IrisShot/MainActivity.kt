package com.apricotiris.IrisShot

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.annotation.StringRes
import com.apricotiris.IrisShot.capture.GameLongShotCaptureService
import com.apricotiris.IrisShot.core.model.GameCaptureTemplate
import com.apricotiris.IrisShot.core.model.ModelJsonCodec
import com.apricotiris.IrisShot.core.storage.CaptureResultRepository
import com.apricotiris.IrisShot.core.storage.SessionFileStore
import com.apricotiris.IrisShot.core.storage.StorageMigrationIssue
import com.apricotiris.IrisShot.core.storage.StorageLocationPreferences
import com.apricotiris.IrisShot.core.template.TemplateRepository
import com.apricotiris.IrisShot.shizuku.ShizukuBridge
import com.apricotiris.IrisShot.shizuku.ShizukuPermissionRequest
import com.apricotiris.IrisShot.ui.AppPreferences
import com.apricotiris.IrisShot.ui.AppLocaleManager
import com.apricotiris.IrisShot.ui.CaptureServiceUiState
import com.apricotiris.IrisShot.ui.CaptureSettingsUiState
import com.apricotiris.IrisShot.ui.CaptureUiContract
import com.apricotiris.IrisShot.ui.GameLongShotApp
import com.apricotiris.IrisShot.ui.LanguageTransitionPhase
import com.apricotiris.IrisShot.ui.LocalizedComponentActivity
import com.apricotiris.IrisShot.ui.PermissionUiState
import com.apricotiris.IrisShot.ui.TemplateUiModel
import com.apricotiris.IrisShot.ui.areNotificationsReady
import com.apricotiris.IrisShot.ui.migrateLegacyCaptureSettings
import com.apricotiris.IrisShot.ui.localizedErrorOrFallback
import com.apricotiris.IrisShot.ui.toCaptureSettingsUiState
import com.apricotiris.IrisShot.ui.theme.resolveGameLongShotWindowBackgroundColor
import com.apricotiris.IrisShot.template.TemplateEditorActivity

class MainActivity : LocalizedComponentActivity() {
    private val templateRepository by lazy { TemplateRepository(this) }
    private val captureResultRepository by lazy { CaptureResultRepository(this) }
    private val appPreferences by lazy { AppPreferences(this) }
    private val storageLocationPreferences by lazy { StorageLocationPreferences(this) }

    private var permissions by mutableStateOf(PermissionUiState())
    private var serviceState by mutableStateOf(CaptureServiceUiState())
    private var templates by mutableStateOf<List<TemplateUiModel>>(emptyList())
    private var settings by mutableStateOf(CaptureSettingsUiState())
    private var languageTransitionPhase by mutableStateOf(LanguageTransitionPhase.IDLE)
    private var languageContentFadeGeneration by mutableIntStateOf(0)
    private var languageTransitionFallbackJob: Job? = null
    private var templateRefreshJob: Job? = null

    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var cacheDirectoryLauncher: ActivityResultLauncher<Uri?>
    private lateinit var exportDirectoryLauncher: ActivityResultLauncher<Uri?>
    private val projectionPermissionState by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(this)[ProjectionPermissionViewModel::class.java]
    }
    private var stateReceiverRegistered = false
    private var cacheMigrationInProgress = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != CaptureUiContract.ACTION_STATE_CHANGED) return
            val sourceInstanceId = intent.getLongExtra(
                CaptureUiContract.EXTRA_SERVICE_INSTANCE_ID,
                0L,
            )
            val activeInstanceId = GameLongShotCaptureService.activeServiceInstanceId()
            if (activeInstanceId != 0L && sourceInstanceId != activeInstanceId) return
            val state = intent.getStringExtra(CaptureUiContract.EXTRA_STATE).orEmpty().ifBlank { "IDLE" }
            val previous = serviceState
            val running = isCaptureServiceActive(
                processRunning = GameLongShotCaptureService.isRunning(),
                stateName = state,
            )
            val sessionId = intent.getStringExtra(CaptureUiContract.EXTRA_RESULT_SESSION_ID)
                ?: previous.resultSessionId
            val next = previous.copy(
                running = running,
                state = state,
                message = intent.getStringExtra(CaptureUiContract.EXTRA_MESSAGE)
                    ?: previous.message,
                frameCount = intent.getIntExtra(
                    CaptureUiContract.EXTRA_FRAME_COUNT,
                    previous.frameCount,
                ),
                resultSessionId = sessionId,
            )
            if (next != previous) serviceState = next
            val projectionReady = running || projectionPermissionState.requests.hasPending
            if (permissions.projectionGranted != projectionReady) {
                permissions = permissions.copy(projectionGranted = projectionReady)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val initialSettings = appPreferences.loadSettings()
        window.setBackgroundDrawable(
            ColorDrawable(
                resolveGameLongShotWindowBackgroundColor(
                    context = this,
                    themeMode = initialSettings.themeMode,
                    dynamicColor = initialSettings.dynamicColor,
                ),
            ),
        )
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settings = initialSettings
        val pendingLanguage = intent.getStringExtra(EXTRA_PENDING_LANGUAGE_CHANGE)
        languageTransitionPhase = if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            intent.getBooleanExtra(EXTRA_FADE_IN_AFTER_LANGUAGE_CHANGE, false)
        ) {
            LanguageTransitionPhase.LEGACY_FADE_IN
        } else if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            pendingLanguage == settings.appLanguage.storedValue
        ) {
            LanguageTransitionPhase.LEGACY_FADE_OUT
        } else {
            LanguageTransitionPhase.IDLE
        }
        intent.removeExtra(EXTRA_FADE_IN_AFTER_LANGUAGE_CHANGE)
        if (languageTransitionPhase == LanguageTransitionPhase.IDLE) {
            intent.removeExtra(EXTRA_PENDING_LANGUAGE_CHANGE)
        }
        permissions = permissions.copy(
            projectionGranted = projectionPermissionState.requests.hasPending,
        )

        projectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val permissionData = result.data
            if (result.resultCode == Activity.RESULT_OK && permissionData != null) {
                val startAfterGrant = projectionPermissionState.requests.complete(
                    PendingProjectionGrant(result.resultCode, permissionData),
                ) ?: return@registerForActivityResult
                permissions = permissions.copy(projectionGranted = true)
                if (startAfterGrant) {
                    startPendingProjectionCapture()
                } else {
                    showToast(R.string.toast_projection_authorized)
                }
            } else {
                if (!projectionPermissionState.requests.cancelRequest()) {
                    return@registerForActivityResult
                }
                permissions = permissions.copy(projectionGranted = false)
                showToast(R.string.toast_projection_denied)
            }
        }
        projectionPermissionState.automaticGrantSignal.observe(this) {
            drainAutomaticPermissionGrantResults()
        }
        cacheDirectoryLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            uri?.let(::changeCacheDirectory)
        }
        exportDirectoryLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            uri?.let(::changeExportDirectory)
        }

        ShizukuBridge.startListening(
            context = this,
            onStateChanged = {
                runOnUiThread { refreshPermissionState() }
            },
            onPermissionResult = { requestCode, granted ->
                if (requestCode != SHIZUKU_PERMISSION_REQUEST_CODE) return@startListening
                runOnUiThread {
                    if (granted) {
                        ShizukuBridge.setModeEnabled(this, true)
                        showToast(R.string.toast_shizuku_authorized)
                    } else {
                        ShizukuBridge.setModeEnabled(this, false)
                        showToast(R.string.toast_shizuku_denied)
                    }
                    refreshPermissionState()
                }
            },
        )

        setContent {
            val onOpenOverlayRemembered = remember {
                    {
                        startCaptureService(CaptureUiContract.ACTION_START_OVERLAY)
                        Unit
                    }
                }
            val onStopCaptureRemembered = remember {
                    {
                        startCaptureService(CaptureUiContract.ACTION_EXIT_CAPTURE)
                        Unit
                    }
                }
            val onRenameTemplateRemembered = remember {
                    { template: TemplateUiModel, name: String ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            templateRepository.rename(template.id, name)
                            withContext(Dispatchers.Main) {
                                refreshTemplates()
                            }
                        }
                        Unit
                    }
                }
            val onDuplicateTemplateRemembered = remember {
                    { template: TemplateUiModel ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            templateRepository.duplicate(
                                template.id,
                                getString(R.string.template_copy_name, template.name),
                            )
                            withContext(Dispatchers.Main) {
                                refreshTemplates()
                                showToast(R.string.toast_template_duplicated, template.name)
                            }
                        }
                        Unit
                    }
                }
            val onDeleteTemplateRemembered = remember {
                    { template: TemplateUiModel ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            templateRepository.delete(template.id)
                            withContext(Dispatchers.Main) {
                                refreshTemplates()
                            }
                        }
                        Unit
                    }
                }
            val onDeleteTemplatesRemembered = remember {
                    { templateIds: Set<Long> ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            templateIds.forEach(templateRepository::delete)
                            withContext(Dispatchers.Main) {
                                refreshTemplates()
                            }
                        }
                        Unit
                    }
                }
            val onNewTemplateRemembered = remember { { openTemplateEditor() } }
            val onEditTemplateRemembered = remember {
                { template: TemplateUiModel -> openTemplateEditor(template.id) }
            }
            val onProjectionRemembered = remember { { requestProjection() } }
            val onProjectionPermissionRemembered = remember { { requestProjectionAuthorization() } }
            val onOverlayPermissionRemembered = remember { { requestOverlayPermission() } }
            val onAccessibilityPermissionRemembered = remember { { openAccessibilitySettings() } }
            val onNotificationPermissionRemembered = remember { { requestNotification() } }
            val onShizukuModeChangeRemembered = remember {
                { enabled: Boolean -> changeShizukuMode(enabled) }
            }
            val onSettingsChangeRemembered = remember { { value: CaptureSettingsUiState -> updateSettings(value) } }
            val onSelectCacheDirectoryRemembered = remember { { selectCacheDirectory() } }
            val onSelectExportDirectoryRemembered = remember { { selectExportDirectory() } }
            val onResetCacheDirectoryRemembered = remember { { resetCacheDirectory() } }
            val onResetExportDirectoryRemembered = remember { { resetExportDirectory() } }
            val onOpenGithubRemembered = remember { { openGithubRepository() } }
            val onLanguageTransitionCoveredRemembered = remember {
                { applyLanguageAfterFadeOut() }
            }
            val onLanguageTransitionFinishedRemembered = remember {
                { languageTransitionPhase = LanguageTransitionPhase.IDLE }
            }

            GameLongShotApp(
                    permissions = permissions,
                    service = serviceState,
                    templates = templates,
                    settings = settings,
                    onProjection = onProjectionRemembered,
                    onProjectionPermission = onProjectionPermissionRemembered,
                    onOverlayPermission = onOverlayPermissionRemembered,
                    onAccessibilityPermission = onAccessibilityPermissionRemembered,
                    onNotificationPermission = onNotificationPermissionRemembered,
                    onShizukuModeChange = onShizukuModeChangeRemembered,
                    onOpenOverlay = onOpenOverlayRemembered,
                    onStopCapture = onStopCaptureRemembered,
                    onRenameTemplate = onRenameTemplateRemembered,
                    onDuplicateTemplate = onDuplicateTemplateRemembered,
                    onDeleteTemplate = onDeleteTemplateRemembered,
                    onDeleteTemplates = onDeleteTemplatesRemembered,
                    onNewTemplate = onNewTemplateRemembered,
                    onEditTemplate = onEditTemplateRemembered,
                    onSettingsChange = onSettingsChangeRemembered,
                    onSelectCacheDirectory = onSelectCacheDirectoryRemembered,
                    onSelectExportDirectory = onSelectExportDirectoryRemembered,
                    onResetCacheDirectory = onResetCacheDirectoryRemembered,
                    onResetExportDirectory = onResetExportDirectoryRemembered,
                    onOpenGithub = onOpenGithubRemembered,
                    languageTransitionPhase = languageTransitionPhase,
                    languageContentFadeGeneration = languageContentFadeGeneration,
                    onLanguageTransitionCovered = onLanguageTransitionCoveredRemembered,
                    onLanguageTransitionFinished = onLanguageTransitionFinishedRemembered,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        registerStateReceiver()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
        refreshTemplates()
        refreshServiceRunningState()
        refreshLatestResult()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val pendingLanguageValue = intent.getStringExtra(EXTRA_PENDING_LANGUAGE_CHANGE)
        val pendingLanguage = pendingLanguageValue
            ?.let(com.apricotiris.IrisShot.ui.AppLanguage::fromStoredValue)
        val appliedLanguage = newConfig.locales.takeIf { !it.isEmpty }
            ?.get(0)
            ?.toLanguageTag()
            ?.let(com.apricotiris.IrisShot.ui.AppLanguage::fromLanguageTagOrNull)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && appliedLanguage != null) {
            // Keep preference-backed state in sync with changes made from the system app-language page.
            // During our own transition, wait for the requested locale instead of writing the old one back.
            if (pendingLanguage == null || pendingLanguage == appliedLanguage) {
                if (settings.appLanguage != appliedLanguage) {
                    settings = settings.copy(appLanguage = appliedLanguage)
                    appPreferences.saveAppLanguage(appliedLanguage)
                    refreshTemplates()
                    refreshServiceRunningState()
                }
            }
        }
        if (appliedLanguage != null && pendingLanguage == appliedLanguage) {
            completeFrameworkLanguageChange(appliedLanguage)
        }
    }

    override fun onStop() {
        if (
            !isChangingConfigurations &&
            intent.hasExtra(EXTRA_PENDING_LANGUAGE_CHANGE)
        ) {
            languageTransitionFallbackJob?.cancel()
            languageTransitionFallbackJob = null
            intent.removeExtra(EXTRA_PENDING_LANGUAGE_CHANGE)
            languageTransitionPhase = LanguageTransitionPhase.IDLE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.removeExtra(EXTRA_FADE_IN_AFTER_LANGUAGE_CHANGE)
                AppLocaleManager.applyFrameworkLocale(this, settings.appLanguage)
            } else {
                intent.putExtra(EXTRA_FADE_IN_AFTER_LANGUAGE_CHANGE, true)
            }
        }
        if (stateReceiverRegistered) {
            unregisterReceiver(stateReceiver)
            stateReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        ShizukuBridge.stopListening()
        super.onDestroy()
    }

    private fun requestProjection() {
        if (!Settings.canDrawOverlays(this)) {
            showToast(R.string.toast_enable_overlay_first)
            requestOverlayPermission()
            return
        }
        if (ShizukuBridge.isModeEnabled(this)) {
            clearPendingProjectionPermission()
            if (!ShizukuBridge.isModeReady(this)) {
                showToast(R.string.toast_shizuku_unavailable)
                openShizukuManager()
                return
            }
            val started = startCaptureService(
                action = CaptureUiContract.ACTION_START_OVERLAY,
                useShizuku = true,
            )
            if (started) showToast(R.string.toast_shizuku_capture_ready)
            return
        }
        if (!isAccessibilityEnabled()) {
            showToast(R.string.toast_enable_accessibility_first)
            openAccessibilitySettings()
            return
        }
        if (projectionPermissionState.requests.hasPending) {
            startPendingProjectionCapture()
        } else {
            launchProjectionPermission(startCaptureAfterGrant = true)
        }
    }

    private fun requestProjectionAuthorization() {
        if (serviceState.running) {
            showToast(R.string.toast_finish_capture_first)
            return
        }
        launchProjectionPermission(startCaptureAfterGrant = false)
    }

    private fun launchProjectionPermission(startCaptureAfterGrant: Boolean) {
        if (!projectionPermissionState.requests.begin(startCaptureAfterGrant)) {
            showToast(R.string.toast_projection_in_progress)
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Game templates and accessibility gestures use physical display
            // coordinates. Force full-display capture so Android 14's optional
            // single-app sharing cannot introduce letterboxing or remapping.
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        runCatching {
            projectionLauncher.launch(captureIntent)
        }.onFailure {
            projectionPermissionState.requests.clear()
            permissions = permissions.copy(projectionGranted = false)
            showToast(
                R.string.toast_open_projection_failed,
                localizedErrorOrFallback(it, R.string.common_try_again_later),
            )
        }
    }

    private fun startPendingProjectionCapture() {
        val grant = projectionPermissionState.requests.take() ?: return
        val started = startCaptureService(
            action = CaptureUiContract.ACTION_START_OVERLAY,
            projectionResultCode = grant.resultCode,
            projectionData = grant.data,
            useShizuku = false,
        )
        permissions = permissions.copy(projectionGranted = started)
        if (started) showToast(R.string.toast_projection_ready_return_game)
    }

    private fun clearPendingProjectionPermission() {
        projectionPermissionState.requests.clear()
        if (!serviceState.running) {
            permissions = permissions.copy(projectionGranted = false)
        }
    }

    private fun changeShizukuMode(enabled: Boolean) {
        if (serviceState.running) {
            showToast(R.string.toast_finish_before_shizuku_switch)
            return
        }
        if (!enabled) {
            ShizukuBridge.setModeEnabled(this, false)
            refreshPermissionState()
            return
        }
        when (ShizukuBridge.requestPermission(this, SHIZUKU_PERMISSION_REQUEST_CODE)) {
            ShizukuPermissionRequest.ALREADY_GRANTED -> {
                ShizukuBridge.setModeEnabled(this, true)
                refreshPermissionState()
            }
            ShizukuPermissionRequest.REQUESTED -> Unit
            ShizukuPermissionRequest.MANAGER_NOT_INSTALLED -> {
                ShizukuBridge.setModeEnabled(this, false)
                showToast(R.string.toast_shizuku_not_installed)
            }
            ShizukuPermissionRequest.SERVICE_UNAVAILABLE -> {
                ShizukuBridge.setModeEnabled(this, false)
                showToast(R.string.toast_start_shizuku_first)
                openShizukuManager()
            }
        }
    }

    private fun openShizukuManager() {
        packageManager.getLaunchIntentForPackage(ShizukuBridge.MANAGER_PACKAGE)?.let { intent ->
            runCatching { startActivity(intent) }
        }
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) return
        if (!ShizukuBridge.isModeReady(this)) {
            openOverlaySettings()
            return
        }
        val targetPackage = packageName
        val targetUserId = currentUserId()
        val started = projectionPermissionState.requestAutomaticGrant(
            AutomaticPermissionKind.OVERLAY,
        ) {
            ShizukuBridge.tryGrantOverlay(targetPackage, targetUserId)
        }
        if (!started) showToast(R.string.toast_enabling_overlay)
    }

    private fun openOverlaySettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }.onFailure { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun requestNotification() {
        if (areNotificationsReady()) return
        if (ShizukuBridge.isModeReady(this)) {
            val targetPackage = packageName
            val targetUserId = currentUserId()
            val started = projectionPermissionState.requestAutomaticGrant(
                AutomaticPermissionKind.NOTIFICATION,
            ) {
                ShizukuBridge.tryGrantNotification(targetPackage, targetUserId)
            }
            if (!started) showToast(R.string.toast_enabling_notification)
            return
        }
        requestNotificationManually()
    }

    private fun drainAutomaticPermissionGrantResults() {
        while (true) {
            val result = projectionPermissionState.takeAutomaticGrantResult() ?: return
            refreshPermissionState()
            when (result.kind) {
                AutomaticPermissionKind.OVERLAY -> {
                    if (Settings.canDrawOverlays(this)) {
                        showToast(R.string.toast_overlay_enabled)
                    } else {
                        showToast(R.string.toast_overlay_auto_failed)
                        openOverlaySettings()
                    }
                }
                AutomaticPermissionKind.NOTIFICATION -> {
                    if (areNotificationsReady()) {
                        showToast(R.string.toast_notification_enabled)
                    } else {
                        showToast(R.string.toast_notification_auto_failed)
                        requestNotificationManually()
                    }
                }
            }
        }
    }

    private fun requestNotificationManually() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        }.onFailure {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    private fun selectCacheDirectory() {
        if (cacheMigrationInProgress) {
            showToast(R.string.toast_cache_migrating)
            return
        }
        if (serviceState.running) {
            showToast(R.string.toast_finish_before_cache_change)
            return
        }
        cacheDirectoryLauncher.launch(storageLocationPreferences.cacheTreeUri)
    }

    private fun selectExportDirectory() {
        exportDirectoryLauncher.launch(storageLocationPreferences.exportTreeUri)
    }

    private fun changeCacheDirectory(uri: Uri) {
        if (cacheMigrationInProgress) {
            showToast(R.string.toast_cache_migrating)
            return
        }
        if (serviceState.running) {
            showToast(R.string.toast_cache_unchanged_during_capture)
            return
        }
        if (!takeDirectoryPermission(uri)) return
        migrateCacheDirectory(uri, null, getString(R.string.toast_cache_location_changed))
    }

    private fun resetCacheDirectory() {
        if (cacheMigrationInProgress) {
            showToast(R.string.toast_cache_migrating)
            return
        }
        if (serviceState.running) {
            showToast(R.string.toast_finish_before_cache_reset)
            return
        }
        migrateCacheDirectory(null, null, getString(R.string.toast_cache_location_reset))
    }

    private fun migrateCacheDirectory(uri: Uri?, displayName: String?, successMessage: String) {
        cacheMigrationInProgress = true
        lifecycleScope.launch {
            try {
                val migration = withContext(Dispatchers.IO) {
                    runCatching {
                        val resolvedDisplayName = displayName ?: uri?.let(::friendlyDirectoryName)
                        SessionFileStore(this@MainActivity).changeCacheLocation(uri, resolvedDisplayName)
                    }
                }
                migration.onSuccess { result ->
                    refreshTemplates()
                    refreshLatestResult()
                    showToast(
                        if (result.isSuccess) {
                            successMessage
                        } else {
                            getString(
                                when (result.issue) {
                                    StorageMigrationIssue.SOURCE_UNAVAILABLE_DEFAULT_RESTORED ->
                                        R.string.toast_cache_source_unavailable_default_restored
                                    StorageMigrationIssue.SOURCE_CLEANUP_INCOMPLETE ->
                                        R.string.toast_cache_switched_cleanup_incomplete
                                    null -> R.string.common_unknown_error
                                },
                            )
                        },
                    )
                }.onFailure {
                    showToast(
                        R.string.toast_cache_migration_failed,
                        localizedErrorOrFallback(it, R.string.common_unknown_error),
                    )
                }
            } finally {
                cacheMigrationInProgress = false
            }
        }
    }

    private fun changeExportDirectory(uri: Uri) {
        if (!takeDirectoryPermission(uri)) return
        lifecycleScope.launch {
            val update = withContext(Dispatchers.IO) {
                runCatching {
                    val previous = storageLocationPreferences.exportTreeUri
                    storageLocationPreferences.setExportLocation(
                        uri = uri,
                        displayName = friendlyDirectoryName(uri),
                        releasePreviousPermission = false,
                    )
                    captureResultRepository.releaseExportPermissionIfUnreferenced(previous)
                }
            }
            update.onSuccess {
                refreshTemplates()
                showToast(R.string.toast_save_location_changed)
            }.onFailure {
                showToast(
                    R.string.toast_save_location_change_failed,
                    localizedErrorOrFallback(it, R.string.common_unknown_error),
                )
            }
        }
    }

    private fun resetExportDirectory() {
        lifecycleScope.launch {
            val update = withContext(Dispatchers.IO) {
                runCatching {
                    val previous = storageLocationPreferences.exportTreeUri
                    storageLocationPreferences.clearExportLocation(
                        releasePreviousPermission = false,
                    )
                    captureResultRepository.releaseExportPermissionIfUnreferenced(previous)
                }
            }
            update.onSuccess {
                refreshTemplates()
                showToast(R.string.toast_save_location_reset)
            }.onFailure {
                showToast(
                    R.string.toast_save_location_reset_failed,
                    localizedErrorOrFallback(it, R.string.common_unknown_error),
                )
            }
        }
    }

    private fun takeDirectoryPermission(uri: Uri): Boolean = runCatching {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }.onFailure {
        showToast(R.string.toast_directory_permission_failed)
    }.isSuccess

    private fun friendlyDirectoryName(uri: Uri): String {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        if (!documentId.isNullOrBlank()) {
            val volumeAndPath = documentId.split(':', limit = 2)
            val volume = when (volumeAndPath.first().lowercase(Locale.ROOT)) {
                "primary" -> getString(R.string.storage_internal)
                "home" -> getString(R.string.storage_documents)
                else -> volumeAndPath.first()
            }
            val relativePath = volumeAndPath.getOrNull(1)
                ?.replace('\\', '/')
                ?.trim('/')
                .orEmpty()
            return if (relativePath.isBlank()) volume else "$volume/$relativePath"
        }
        return runCatching {
            contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull().orEmpty().ifBlank { getString(R.string.storage_selected_directory) }
    }

    private fun startCaptureService(
        action: String,
        projectionResultCode: Int? = null,
        projectionData: Intent? = null,
        templateId: Long? = null,
        templateJson: String? = null,
        useShizuku: Boolean = ShizukuBridge.isModeEnabled(this),
    ): Boolean {
        val explicitTemplateSettings = templateJson
            ?.let { raw -> runCatching { ModelJsonCodec.templateFromJson(org.json.JSONObject(raw)) }.getOrNull() }
            ?.toCaptureSettingsUiState(appPreferences.loadSettings())
            ?: templateId?.let(templateRepository::getById)
                ?.toCaptureSettingsUiState(appPreferences.loadSettings())
        val shouldSendSettings = explicitTemplateSettings != null ||
            !serviceState.running || action == CaptureUiContract.ACTION_UPDATE_CONFIG
        val serviceIntent = Intent().setClassName(this, CaptureUiContract.SERVICE_CLASS).apply {
            this.action = action
            projectionResultCode?.let { putExtra(CaptureUiContract.EXTRA_RESULT_CODE, it) }
            projectionData?.let { putExtra(CaptureUiContract.EXTRA_RESULT_DATA, it) }
            templateId?.let { putExtra(CaptureUiContract.EXTRA_TEMPLATE_ID, it) }
            templateJson?.let { putExtra(CaptureUiContract.EXTRA_TEMPLATE_JSON, it) }
            putExtra(CaptureUiContract.EXTRA_USE_SHIZUKU, useShizuku)
            if (shouldSendSettings) {
                putSettingsExtras(this, explicitTemplateSettings ?: appPreferences.loadSettings())
            }
        }
        return runCatching {
            if (action == CaptureUiContract.ACTION_STOP_CAPTURE ||
                action == CaptureUiContract.ACTION_EXIT_CAPTURE ||
                action == CaptureUiContract.ACTION_UPDATE_CONFIG || serviceState.running
            ) startService(serviceIntent)
            else ContextCompat.startForegroundService(this, serviceIntent)
        }.onFailure {
            showToast(
                R.string.toast_start_service_failed,
                localizedErrorOrFallback(it, R.string.common_check_permissions),
            )
        }.isSuccess
    }

    private fun putSettingsExtras(intent: Intent, value: CaptureSettingsUiState) {
        intent.putExtra(CaptureUiContract.EXTRA_PREVIEW_ENABLED, value.previewEnabled)
        intent.putExtra(CaptureUiContract.EXTRA_PREVIEW_GAP_DURATION_MS, value.gapDurationMs)
        intent.putExtra(CaptureUiContract.EXTRA_MAX_OUTPUT_HEIGHT, value.maxOutputHeight)
        intent.putExtra(CaptureUiContract.EXTRA_AUTO_OPEN_RESULT, value.autoOpenResult)
        intent.putExtra(CaptureUiContract.EXTRA_OUTPUT_FORMAT, value.outputFormat.name)
        intent.putExtra(CaptureUiContract.EXTRA_JPEG_QUALITY, value.jpegQuality)
    }

    private fun updateSettings(value: CaptureSettingsUiState) {
        val languageChanged = value.appLanguage != settings.appLanguage
        settings = value
        appPreferences.saveSettings(value)
        if (languageChanged) {
            languageTransitionFallbackJob?.cancel()
            languageTransitionFallbackJob = null
            if (GameLongShotCaptureService.isRunning()) {
                startCaptureService(CaptureUiContract.ACTION_UPDATE_CONFIG)
            }
            intent.putExtra(EXTRA_PENDING_LANGUAGE_CHANGE, value.appLanguage.storedValue)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                applyFrameworkLanguageDirectly()
            } else {
                languageTransitionPhase = LanguageTransitionPhase.LEGACY_FADE_OUT
            }
        }
    }

    private fun applyLanguageAfterFadeOut() {
        if (languageTransitionPhase != LanguageTransitionPhase.LEGACY_FADE_OUT) return
        intent.removeExtra(EXTRA_PENDING_LANGUAGE_CHANGE)
        intent.putExtra(EXTRA_FADE_IN_AFTER_LANGUAGE_CHANGE, true)
        AppLocaleManager.apply(this, settings.appLanguage)
    }

    private fun applyFrameworkLanguageDirectly() {
        val requestedLanguage = settings.appLanguage
        languageTransitionPhase = LanguageTransitionPhase.IDLE
        val changed = AppLocaleManager.applyFrameworkLocale(this, requestedLanguage)
        if (intent.getStringExtra(EXTRA_PENDING_LANGUAGE_CHANGE) != requestedLanguage.storedValue) {
            return
        }
        if (!changed && currentConfigurationLanguage() == requestedLanguage) {
            completeFrameworkLanguageChange(requestedLanguage)
            return
        }
        languageTransitionFallbackJob?.cancel()
        languageTransitionFallbackJob = lifecycleScope.launch {
            delay(LANGUAGE_TRANSITION_FALLBACK_TIMEOUT_MS)
            if (intent.getStringExtra(EXTRA_PENDING_LANGUAGE_CHANGE) == requestedLanguage.storedValue) {
                if (currentConfigurationLanguage() == requestedLanguage) {
                    completeFrameworkLanguageChange(requestedLanguage)
                } else {
                    // Some OEM builds occasionally omit the locale callback. Retry without
                    // covering or recreating the current page; a late callback still completes it.
                    AppLocaleManager.applyFrameworkLocale(this@MainActivity, requestedLanguage)
                }
            }
        }
    }

    private fun completeFrameworkLanguageChange(language: com.apricotiris.IrisShot.ui.AppLanguage) {
        if (intent.getStringExtra(EXTRA_PENDING_LANGUAGE_CHANGE) != language.storedValue) return
        languageTransitionFallbackJob?.cancel()
        languageTransitionFallbackJob = null
        intent.removeExtra(EXTRA_PENDING_LANGUAGE_CHANGE)
        intent.removeExtra(EXTRA_FADE_IN_AFTER_LANGUAGE_CHANGE)
        refreshTemplates()
        refreshServiceRunningState()
        languageContentFadeGeneration += 1
    }

    private fun currentConfigurationLanguage(): com.apricotiris.IrisShot.ui.AppLanguage? =
        resources.configuration.locales.takeIf { !it.isEmpty }
            ?.get(0)
            ?.toLanguageTag()
            ?.let(com.apricotiris.IrisShot.ui.AppLanguage::fromLanguageTagOrNull)

    private fun openGithubRepository() {
        val browserIntent = Intent.makeMainSelectorActivity(
            Intent.ACTION_MAIN,
            Intent.CATEGORY_APP_BROWSER,
        ).apply {
            data = Uri.parse(GITHUB_REPOSITORY_URL)
        }
        runCatching { startActivity(browserIntent) }
            .onFailure { showToast(R.string.toast_open_external_link_failed) }
    }

    private fun openTemplateEditor(templateId: Long = 0L) {
        startActivity(TemplateEditorActivity.createIntent(this, templateId))
    }

    private fun openResult(resultId: String) {
        if (resultId.isBlank()) {
            val intent = Intent(this, com.apricotiris.IrisShot.result.ResultListActivity::class.java)
            startActivity(intent)
        } else {
            val intent = Intent(CaptureUiContract.ACTION_VIEW_RESULT)
                .setClassName(this, CaptureUiContract.RESULT_ACTIVITY_CLASS)
                .putExtra(CaptureUiContract.EXTRA_SESSION_ID, resultId)
            startActivity(intent)
        }
    }

    private fun refreshTemplates() {
        templateRefreshJob?.cancel()
        templateRefreshJob = lifecycleScope.launch(Dispatchers.IO) {
            val storedTemplates = loadTemplatesWithMigratedCaptureSettings()
            val allTemplates = storedTemplates.map { it.toUiModel() }
            withContext(Dispatchers.Main) {
                templates = allTemplates
            }
        }
    }

    private fun loadTemplatesWithMigratedCaptureSettings(): List<GameCaptureTemplate> {
        val storedTemplates = templateRepository.getAll()
        val needsMigration = storedTemplates.any {
            it.captureSettingsVersion < GameCaptureTemplate.CURRENT_CAPTURE_SETTINGS_VERSION
        }
        if (!needsMigration) return storedTemplates

        val legacySettings = appPreferences.loadDefaultCaptureSettings()
        return storedTemplates.map { stored ->
            val migrated = stored.migrateLegacyCaptureSettings(legacySettings)
            if (migrated === stored) {
                stored
            } else {
                templateRepository.replacePreservingTimestamps(migrated) ?: migrated
            }
        }
    }

    private fun refreshLatestResult() {
        if (serviceState.resultSessionId != null) return
        lifecycleScope.launch(Dispatchers.IO) {
            val latest = captureResultRepository.getAll().firstOrNull()
            if (latest != null) {
                withContext(Dispatchers.Main) {
                    serviceState = serviceState.copy(resultSessionId = latest.id)
                }
            }
        }
    }

    private fun GameCaptureTemplate.toUiModel() = TemplateUiModel(
        id = id,
        name = templateName.ifBlank { getString(R.string.common_unnamed_template) },
        appName = appName,
        packageName = packageName,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        direction = scrollDirection.name,
        updatedAt = updatedAt,
        updatedAtLabel = if (updatedAt <= 0L) {
            ""
        } else {
            DateTimeFormatter
                .ofPattern("MM-dd HH:mm", AppLocaleManager.locale(settings.appLanguage))
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(updatedAt))
        },
    )

    private fun refreshPermissionState() {
        val shizuku = ShizukuBridge.state(this)
        permissions = permissions.copy(
            overlayGranted = Settings.canDrawOverlays(this),
            accessibilityGranted = isAccessibilityEnabled(),
            notificationGranted = areNotificationsReady(),
            shizukuInstalled = shizuku.installed,
            shizukuBinderAvailable = shizuku.binderAvailable,
            shizukuPermissionGranted = shizuku.permissionGranted,
            shizukuModeEnabled = shizuku.modeRequested,
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { flattened ->
            android.content.ComponentName.unflattenFromString(flattened)?.packageName == packageName
        }
    }

    private fun refreshServiceRunningState() {
        val processRunning = GameLongShotCaptureService.isRunning()
        val snapshot = GameLongShotCaptureService.latestSnapshot()
        val active = isCaptureServiceActive(processRunning, snapshot.state.name)
        serviceState = serviceState.copy(
            running = active,
            state = if (processRunning) snapshot.state.name else "IDLE",
            message = if (processRunning) {
                snapshot.message.ifBlank { getString(R.string.capture_waiting_to_start) }
            } else {
                getString(R.string.capture_waiting_to_start)
            },
            frameCount = snapshot.frameCount,
            resultSessionId = snapshot.resultSessionId ?: serviceState.resultSessionId,
        )
        permissions = permissions.copy(
            projectionGranted = active || projectionPermissionState.requests.hasPending,
        )
    }

    private fun registerStateReceiver() {
        if (stateReceiverRegistered) return
        val filter = IntentFilter(CaptureUiContract.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        stateReceiverRegistered = true
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showToast(@StringRes messageRes: Int, vararg formatArgs: Any) {
        showToast(getString(messageRes, *formatArgs))
    }

    private fun currentUserId(): Int =
        android.os.Process.myUid() / 100_000
}

private const val SHIZUKU_PERMISSION_REQUEST_CODE = 9_104
private const val EXTRA_PENDING_LANGUAGE_CHANGE =
    "com.apricotiris.IrisShot.extra.PENDING_LANGUAGE_CHANGE"
private const val EXTRA_FADE_IN_AFTER_LANGUAGE_CHANGE =
    "com.apricotiris.IrisShot.extra.FADE_IN_AFTER_LANGUAGE_CHANGE"
private const val LANGUAGE_TRANSITION_FALLBACK_TIMEOUT_MS = 1_200L
private const val GITHUB_REPOSITORY_URL = "https://github.com/raging-flames/IrisShot"
