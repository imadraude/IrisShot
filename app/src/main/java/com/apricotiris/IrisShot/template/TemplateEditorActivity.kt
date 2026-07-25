package com.apricotiris.IrisShot.template

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.apricotiris.IrisShot.R
import com.apricotiris.IrisShot.core.app.ApplicationLabelResolver
import com.apricotiris.IrisShot.core.model.GameCaptureTemplate
import com.apricotiris.IrisShot.core.model.withNewTemplateCaptureDefaults
import com.apricotiris.IrisShot.core.model.ModelJsonCodec
import com.apricotiris.IrisShot.core.model.hasDefaultPreviewGeometry
import com.apricotiris.IrisShot.core.model.withDefaultPreviewGeometry
import com.apricotiris.IrisShot.core.template.TemplateReferenceStore
import com.apricotiris.IrisShot.core.template.TemplateRepository
import com.apricotiris.IrisShot.ui.AppPreferences
import com.apricotiris.IrisShot.ui.LocalizedComponentActivity
import com.apricotiris.IrisShot.ui.localizedErrorOrFallback
import com.apricotiris.IrisShot.ui.withCaptureSettings
import com.apricotiris.IrisShot.ui.theme.GameLongShotTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.max

internal fun templateWithReferenceDimensions(
    template: GameCaptureTemplate,
    width: Int,
    height: Int,
    pixelsPerDp: Float,
    rebuildDefaultPreview: Boolean,
    scaleToResolution: (GameCaptureTemplate, Int, Int) -> GameCaptureTemplate,
): GameCaptureTemplate {
    val resized = if (template.screenWidth == width && template.screenHeight == height) {
        template
    } else {
        runCatching { scaleToResolution(template, width, height) }
            .getOrElse {
                GameCaptureTemplate.createDefault(
                    packageName = template.packageName,
                    appName = template.appName,
                    templateName = template.templateName,
                    screenWidth = width,
                    screenHeight = height,
                    scrollDirection = template.scrollDirection,
                )
            }
    }
    val normalized = normalizeTemplateForEditor(
        resized.copy(orientation = template.orientation),
    )
    return if (rebuildDefaultPreview) {
        normalized.withDefaultPreviewGeometry(pixelsPerDp)
    } else {
        normalized
    }
}

class TemplateEditorActivity : LocalizedComponentActivity() {
    private lateinit var repository: TemplateRepository
    private lateinit var referenceStore: TemplateReferenceStore
    private lateinit var labelResolver: ApplicationLabelResolver
    private lateinit var imagePicker: ActivityResultLauncher<Array<String>>
    private lateinit var applicationPicker: ActivityResultLauncher<Intent>
    private lateinit var saveCoordinator: TemplateSaveCoordinator
    private val ownedBitmaps = mutableSetOf<Bitmap>()

    private var state by mutableStateOf<TemplateEditorScreenState?>(null)
    private var pendingReferenceUri: Uri? = null
    private var pendingReferenceVerified = false
    private var validatingReferenceUri: Uri? = null
    private var validatingReferenceUpdatesTemplateSize = false
    private var referenceLoadGeneration = 0L
    private var verifyingReferenceGeneration: Long? = null
    private var retiringBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()
        repository = TemplateRepository(this)
        referenceStore = TemplateReferenceStore(this)
        labelResolver = ApplicationLabelResolver(this)
        saveCoordinator = ViewModelProvider(
            this,
            TemplateSaveCoordinatorFactory(application),
        )[TemplateSaveCoordinator::class.java]
        registerPickers()

        val requestedTemplateId = intent.getLongExtra(EXTRA_TEMPLATE_ID, 0L)
        val restored = savedInstanceState?.getString(STATE_TEMPLATE_JSON)
            ?.let { raw -> runCatching { ModelJsonCodec.templateFromJson(JSONObject(raw)) }.getOrNull() }
        pendingReferenceUri = savedInstanceState?.getString(STATE_REFERENCE_URI)
            ?.let(Uri::parse)
        pendingReferenceVerified = pendingReferenceUri != null &&
            savedInstanceState?.getBoolean(STATE_REFERENCE_VERIFIED) == true
        validatingReferenceUri = savedInstanceState?.getString(STATE_VALIDATING_REFERENCE_URI)
            ?.let(Uri::parse)
        validatingReferenceUpdatesTemplateSize =
            savedInstanceState?.getBoolean(STATE_VALIDATING_REFERENCE_UPDATES_SIZE) == true
        val template = restored ?: if (requestedTemplateId != 0L) {
            repository.getById(requestedTemplateId)
        } else {
            createNewTemplate()
        }
        if (template == null) {
            Toast.makeText(this, R.string.template_editor_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val hasReference = template.id != 0L && referenceStore.exists(template.id)
        state = TemplateEditorScreenState(
            template = normalizeTemplateForEditor(template),
            hasReference = hasReference,
            isNew = requestedTemplateId == 0L,
            isSaving = saveCoordinator.currentState is TemplateSaveState.Saving,
        )

        val appPreferences = AppPreferences(this)
        val themeSettings = appPreferences.loadSettings()
        val nonFocusedRegionOpacity =
            appPreferences.loadOverlayOpacitySettings().nonFocusedRegionOpacity
        setContent {
            GameLongShotTheme(
                themeMode = themeSettings.themeMode,
                dynamicColor = themeSettings.dynamicColor,
            ) {
                state?.let { current ->
                    BackHandler(enabled = current.isSaving) {
                        showStatus(getString(R.string.template_editor_saving))
                    }
                    TemplateEditorScreen(
                        state = current,
                        onBack = ::finishWhenIdle,
                        onTemplateChange = ::updateTemplate,
                        onPickReference = ::pickReference,
                        onPickApplication = ::pickApplication,
                        onSave = ::saveTemplate,
                        nonFocusedRegionOpacity = nonFocusedRegionOpacity,
                        onStatusConsumed = {
                            state = state?.copy(statusMessage = null)
                        },
                    )
                }
            }
        }
        lifecycleScope.launch {
            saveCoordinator.saveState.collect(::handleSaveState)
        }
        loadReferencePreview()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        state?.template?.let { template ->
            outState.putString(
                STATE_TEMPLATE_JSON,
                ModelJsonCodec.templateToJson(template).toString(),
            )
        }
        pendingReferenceUri?.let { outState.putString(STATE_REFERENCE_URI, it.toString()) }
        outState.putBoolean(STATE_REFERENCE_VERIFIED, pendingReferenceVerified)
        validatingReferenceUri?.let {
            outState.putString(STATE_VALIDATING_REFERENCE_URI, it.toString())
            outState.putBoolean(
                STATE_VALIDATING_REFERENCE_UPDATES_SIZE,
                validatingReferenceUpdatesTemplateSize,
            )
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        referenceLoadGeneration += 1L
        retiringBitmap = null
        ownedBitmaps.toList().forEach(::recycleBitmap)
        ownedBitmaps.clear()
        super.onDestroy()
    }

    private fun registerPickers() {
        imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            loadReferenceUri(uri, updateTemplateSize = true)
        }
        applicationPicker = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val packageName = result.data?.component?.packageName
                ?: result.data?.`package`
                ?: return@registerForActivityResult
            val appName = labelResolver.resolve(packageName).orEmpty()
            state = state?.let { current ->
                current.copy(
                    template = current.template.copy(
                        packageName = packageName,
                        appName = appName,
                        templateName = appName.takeIf {
                            it.isNotBlank() && current.template.templateName in DEFAULT_TEMPLATE_NAMES
                        } ?: current.template.templateName,
                    ),
                )
            }
        }
    }

    private fun createNewTemplate(): GameCaptureTemplate {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        return GameCaptureTemplate.createDefault(
            packageName = "",
            appName = "",
            templateName = getString(R.string.template_editor_new_title),
            screenWidth = width,
            screenHeight = height,
        ).withCaptureSettings(AppPreferences(this).loadDefaultCaptureSettings())
            .withNewTemplateCaptureDefaults()
            .withDefaultPreviewGeometry(metrics.density)
    }

    private fun updateTemplate(template: GameCaptureTemplate) {
        state = state?.copy(template = normalizeTemplateForEditor(template))
    }

    private fun pickReference() {
        runCatching { imagePicker.launch(arrayOf("image/*")) }
            .onFailure { showStatus(getString(R.string.template_editor_open_image_picker_failed)) }
    }

    private fun pickApplication() {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pickerIntent = Intent(Intent.ACTION_PICK_ACTIVITY)
            .putExtra(Intent.EXTRA_TITLE, getString(R.string.template_editor_choose_app))
            .putExtra(Intent.EXTRA_INTENT, launcherIntent)
        runCatching { applicationPicker.launch(pickerIntent) }
            .onFailure { showStatus(getString(R.string.template_editor_open_app_picker_failed)) }
    }

    private fun loadReferencePreview() {
        validatingReferenceUri?.let { uri ->
            loadReferenceUri(uri, validatingReferenceUpdatesTemplateSize)
            return
        }
        pendingReferenceUri?.let { uri ->
            loadReferenceUri(uri, updateTemplateSize = false)
            return
        }
        loadStoredReferencePreview()
    }

    private fun loadStoredReferencePreview() {
        val template = state?.template ?: return
        val path = template.id.takeIf { it != 0L }?.let(referenceStore::resource) ?: return
        val requestGeneration = ++referenceLoadGeneration
        lifecycleScope.launch {
            val decoded = withContext(Dispatchers.IO) {
                runCatching { decodeFileReference(path) }
            }
            val reference = decoded.getOrNull()
            if (requestGeneration != referenceLoadGeneration) {
                reference?.bitmap?.let(::recycleBitmap)
                return@launch
            }
            decoded.onSuccess(::applyReferencePreview).onFailure {
                updateReferenceAvailability()
                if (!pendingReferenceVerified) clearReferencePreview()
                showStatus(getString(R.string.template_editor_reference_read_failed))
            }
        }
    }

    private fun loadReferenceUri(uri: Uri, updateTemplateSize: Boolean) {
        val requestGeneration = ++referenceLoadGeneration
        verifyingReferenceGeneration = requestGeneration
        validatingReferenceUri = uri
        validatingReferenceUpdatesTemplateSize = updateTemplateSize
        lifecycleScope.launch {
            val decoded = withContext(Dispatchers.IO) {
                runCatching { decodeUriReference(uri) }
            }
            val reference = decoded.getOrNull()
            if (requestGeneration != referenceLoadGeneration) {
                if (verifyingReferenceGeneration == requestGeneration) {
                    verifyingReferenceGeneration = null
                    validatingReferenceUri = null
                    validatingReferenceUpdatesTemplateSize = false
                }
                reference?.bitmap?.let(::recycleBitmap)
                return@launch
            }
            verifyingReferenceGeneration = null
            validatingReferenceUri = null
            validatingReferenceUpdatesTemplateSize = false
            decoded.onSuccess { reference ->
                pendingReferenceUri = uri
                pendingReferenceVerified = true
                applyReferencePreview(reference)
                if (updateTemplateSize) updateReferenceDimensions(reference.width, reference.height)
            }.onFailure { error ->
                if (pendingReferenceUri == uri) {
                    pendingReferenceUri = null
                    pendingReferenceVerified = false
                }
                updateReferenceAvailability()
                restoreStoredPreviewIfNeeded()
                showStatus(
                    localizedErrorOrFallback(
                        error,
                        R.string.template_editor_reference_read_failed,
                    ),
                )
            }
        }
    }

    private fun applyReferencePreview(reference: DecodedReference) {
        val current = state
        if (current == null) {
            recycleBitmap(reference.bitmap)
            return
        }
        val previous = current.referenceBitmap
        ownedBitmaps += reference.bitmap
        state = current.copy(
            referenceBitmap = reference.bitmap,
            hasReference = true,
        )
        if (previous != null && previous !== reference.bitmap) {
            retireBitmap(previous)
        }
    }

    private fun updateReferenceAvailability() {
        val current = state ?: return
        state = current.copy(
            hasReference = pendingReferenceVerified || hasStoredReference(current.template.id),
        )
    }

    private fun restoreStoredPreviewIfNeeded() {
        val current = state ?: return
        if (pendingReferenceVerified) {
            if (current.referenceBitmap == null) {
                pendingReferenceUri?.let { loadReferenceUri(it, updateTemplateSize = false) }
            }
            return
        }
        if (!hasStoredReference(current.template.id)) {
            clearReferencePreview()
            return
        }
        if (current.referenceBitmap == null) loadStoredReferencePreview()
    }

    private fun hasStoredReference(templateId: Long): Boolean =
        templateId != 0L && referenceStore.exists(templateId)

    private fun clearReferencePreview() {
        val current = state ?: return
        val previous = current.referenceBitmap
        state = current.copy(referenceBitmap = null, hasReference = false)
        previous?.let(::retireBitmap)
    }

    private fun retireBitmap(bitmap: Bitmap) {
        if (!ownedBitmaps.contains(bitmap)) {
            recycleBitmap(bitmap)
            return
        }
        retiringBitmap?.takeIf { it !== bitmap }?.let(::recycleBitmap)
        retiringBitmap = bitmap
        window.decorView.postDelayed({
            if (retiringBitmap === bitmap) retiringBitmap = null
            if (state?.referenceBitmap !== bitmap) recycleBitmap(bitmap)
        }, BITMAP_RELEASE_DELAY_MS)
    }

    private fun recycleBitmap(bitmap: Bitmap) {
        ownedBitmaps.remove(bitmap)
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    private fun updateReferenceDimensions(width: Int, height: Int) {
        val current = state ?: return
        val template = current.template
        val density = resources.displayMetrics.density
        // Existing templates can still carry the pre-selector defaults. Preserve manually
        // adjusted geometry, but regenerate either kind of recognized default after resizing.
        val keepDefaultPreview = template.hasDefaultPreviewGeometry(density)
        val resized = templateWithReferenceDimensions(
            template = template,
            width = width,
            height = height,
            pixelsPerDp = density,
            rebuildDefaultPreview = keepDefaultPreview,
            scaleToResolution = repository::scaleToResolution,
        )
        state = current.copy(template = resized, hasReference = true)
    }

    private fun saveTemplate() {
        val current = state ?: return
        if (current.isSaving) return
        val candidate = normalizeTemplateForEditor(current.template)
        if (candidate.templateName.isBlank()) {
            showStatus(getString(R.string.template_editor_enter_name))
            return
        }
        if (candidate.packageName.isBlank()) {
            showStatus(getString(R.string.template_editor_select_app))
            return
        }
        if (verifyingReferenceGeneration != null) {
            showStatus(getString(R.string.template_editor_reference_loading))
            return
        }
        val referenceUri = pendingReferenceUri.takeIf { pendingReferenceVerified }
        referenceLoadGeneration += 1L
        state = current.copy(isSaving = true, statusMessage = null)
        if (!saveCoordinator.save(candidate, referenceUri, current.isNew)) {
            handleSaveState(saveCoordinator.currentState)
        }
    }

    private fun handleSaveState(saveState: TemplateSaveState) {
        when (saveState) {
            TemplateSaveState.Idle -> state = state?.copy(isSaving = false)
            TemplateSaveState.Saving -> state = state?.copy(isSaving = true)
            is TemplateSaveState.Saved -> {
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(EXTRA_TEMPLATE_ID, saveState.templateId),
                )
                finish()
            }
            TemplateSaveState.Failed -> {
                state = state?.copy(
                    isSaving = false,
                    statusMessage = getString(R.string.template_editor_save_failed_generic),
                )
            }
        }
    }

    private fun showStatus(message: String) {
        state = state?.copy(statusMessage = message)
    }

    private fun finishWhenIdle() {
        if (state?.isSaving == true) {
            showStatus(getString(R.string.template_editor_saving))
        } else {
            finish()
        }
    }

    private fun decodeFileReference(path: String): DecodedReference {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            getString(R.string.template_editor_invalid_reference)
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(path, options)
            ?: error(getString(R.string.template_editor_decode_reference_failed))
        return DecodedReference(bitmap, bounds.outWidth, bounds.outHeight)
    }

    private fun decodeUriReference(uri: Uri): DecodedReference {
        return try {
            decodeUriReferenceWithImageDecoder(uri)
        } catch (_: Exception) {
            decodeUriReferenceWithBitmapFactory(uri)
        }
    }

    private fun decodeUriReferenceWithImageDecoder(uri: Uri): DecodedReference {
        var sourceWidth = 0
        var sourceHeight = 0
        val source = ImageDecoder.createSource(contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            sourceWidth = info.size.width
            sourceHeight = info.size.height
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            decoder.setTargetSampleSize(sampleSize(sourceWidth, sourceHeight))
        }
        require(sourceWidth > 0 && sourceHeight > 0) {
            getString(R.string.template_editor_invalid_selected_image)
        }
        return DecodedReference(bitmap, sourceWidth, sourceHeight)
    }

    private fun decodeUriReferenceWithBitmapFactory(uri: Uri): DecodedReference {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: error(getString(R.string.template_editor_read_selected_image_failed))
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            getString(R.string.template_editor_invalid_selected_image)
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: error(getString(R.string.template_editor_decode_selected_image_failed))
        return DecodedReference(bitmap, bounds.outWidth, bounds.outHeight)
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (max(width / sample, height / sample) > REFERENCE_PREVIEW_MAX_DIMENSION) {
            sample *= 2
        }
        return sample
    }

    private data class DecodedReference(
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
    )

    companion object {
        const val EXTRA_TEMPLATE_ID = "template_id"
        private const val STATE_TEMPLATE_JSON = "template_editor_template_json"
        private const val STATE_REFERENCE_URI = "template_editor_reference_uri"
        private const val STATE_REFERENCE_VERIFIED = "template_editor_reference_verified"
        private const val STATE_VALIDATING_REFERENCE_URI = "template_editor_validating_reference_uri"
        private const val STATE_VALIDATING_REFERENCE_UPDATES_SIZE =
            "template_editor_validating_reference_updates_size"
        private const val REFERENCE_PREVIEW_MAX_DIMENSION = 2_048
        private const val BITMAP_RELEASE_DELAY_MS = 64L
        private val DEFAULT_TEMPLATE_NAMES = setOf(
            "新建模板",
            "游戏模板",
            "游戏页面",
            "New template",
            "Game template",
            "Game screen",
        )

        fun createIntent(context: Context, templateId: Long = 0L): Intent =
            Intent(context, TemplateEditorActivity::class.java)
                .putExtra(EXTRA_TEMPLATE_ID, templateId)
    }
}

private class TemplateSaveCoordinator(application: Application) : AndroidViewModel(application) {
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository = TemplateRepository(application)
    private val referenceStore = TemplateReferenceStore(application)
    private val _saveState = MutableStateFlow<TemplateSaveState>(TemplateSaveState.Idle)

    val saveState: StateFlow<TemplateSaveState> = _saveState.asStateFlow()
    val currentState: TemplateSaveState
        get() = _saveState.value

    fun save(candidate: GameCaptureTemplate, referenceUri: Uri?, isNew: Boolean): Boolean {
        if (currentState is TemplateSaveState.Saving || currentState is TemplateSaveState.Saved) {
            return false
        }
        _saveState.value = TemplateSaveState.Saving
        saveScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (isNew) {
                        saveNewTemplate(candidate, referenceUri)
                    } else {
                        saveExistingTemplate(candidate, referenceUri)
                    }
                }
            }
            _saveState.value = result.fold(
                onSuccess = { TemplateSaveState.Saved(it.id) },
                onFailure = { TemplateSaveState.Failed },
            )
        }
        return true
    }

    override fun onCleared() {
        saveScope.cancel()
        super.onCleared()
    }

    private fun saveNewTemplate(candidate: GameCaptureTemplate, referenceUri: Uri?): GameCaptureTemplate {
        val saved = repository.save(candidate.copy(id = 0L))
        if (referenceUri == null) return saved
        try {
            referenceStore.save(saved.id, referenceUri)
            return saved
        } catch (error: Throwable) {
            runCatching {
                check(repository.delete(saved.id)) { "新建模板回滚失败" }
            }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    private fun saveExistingTemplate(candidate: GameCaptureTemplate, referenceUri: Uri?): GameCaptureTemplate {
        require(candidate.id != 0L) { "模板 id 无效" }
        val original = repository.getById(candidate.id) ?: error("模板不存在或已被删除")
        val saved = repository.update(candidate) ?: error("模板不存在或已被删除")
        if (referenceUri == null) return saved
        try {
            referenceStore.save(saved.id, referenceUri)
            return saved
        } catch (error: Throwable) {
            runCatching {
                checkNotNull(repository.replacePreservingTimestamps(original)) { "编辑模板回滚失败" }
            }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }
}

private class TemplateSaveCoordinatorFactory(
    private val application: Application,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == TemplateSaveCoordinator::class.java)
        @Suppress("UNCHECKED_CAST")
        return TemplateSaveCoordinator(application) as T
    }
}

private sealed interface TemplateSaveState {
    data object Idle : TemplateSaveState
    data object Saving : TemplateSaveState
    data class Saved(val templateId: Long) : TemplateSaveState
    data object Failed : TemplateSaveState
}
