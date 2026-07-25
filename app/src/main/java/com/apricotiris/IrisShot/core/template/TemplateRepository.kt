package com.apricotiris.IrisShot.core.template

import android.content.Context
import android.content.SharedPreferences
import com.apricotiris.IrisShot.core.model.GameCaptureTemplate
import com.apricotiris.IrisShot.core.model.ModelJsonCodec
import com.apricotiris.IrisShot.core.model.ScreenOrientation
import com.apricotiris.IrisShot.core.model.hasDefaultPreviewGeometry
import com.apricotiris.IrisShot.core.model.withDefaultPreviewGeometry
import org.json.JSONArray
import kotlin.math.roundToInt

data class TemplateMatch(
    val template: GameCaptureTemplate,
    val exactResolution: Boolean,
    val requiresUserConfirmation: Boolean
)

internal enum class ExistingTemplateTimestampPolicy {
    TOUCH,
    PRESERVE_SUPPLIED,
}

internal fun applyExistingTemplateTimestamps(
    template: GameCaptureTemplate,
    storedTemplate: GameCaptureTemplate,
    now: Long,
    policy: ExistingTemplateTimestampPolicy,
): GameCaptureTemplate = when (policy) {
    ExistingTemplateTimestampPolicy.TOUCH -> template.copy(
        createdAt = storedTemplate.createdAt,
        updatedAt = now,
    )
    ExistingTemplateTimestampPolicy.PRESERVE_SUPPLIED -> template
}

internal fun templateMatchesTargetDisplayOrientation(
    template: GameCaptureTemplate,
    targetWidth: Int,
    targetHeight: Int,
    targetOrientation: String,
): Boolean {
    val targetIsLandscape = if (targetWidth > 0 && targetHeight > 0) {
        targetWidth >= targetHeight
    } else {
        targetOrientation.equals("landscape", ignoreCase = true)
    }
    val templateIsLandscape = if (template.screenWidth > 0 && template.screenHeight > 0) {
        template.screenWidth >= template.screenHeight
    } else {
        template.orientation.equals("landscape", ignoreCase = true)
    }
    return targetIsLandscape == templateIsLandscape
}

/**
 * Small, dependency-free template store backed by one atomic SharedPreferences value.
 * Captures are few and JSON keeps the schema inspectable and easy to migrate.
 */
class TemplateRepository(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME
) {
    private val pixelsPerDp = context.applicationContext.resources.displayMetrics.density
    private val referenceStore = TemplateReferenceStore(context)
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun getAll(): List<GameCaptureTemplate> = synchronized(STORE_LOCK) {
        readTemplates().sortedByDescending { it.updatedAt }
    }

    fun getById(id: Long): GameCaptureTemplate? = synchronized(STORE_LOCK) {
        readTemplates().firstOrNull { it.id == id }
    }

    /** Inserts when id is zero/unknown, otherwise replaces the existing template. */
    fun save(template: GameCaptureTemplate): GameCaptureTemplate = synchronized(STORE_LOCK) {
        saveLocked(template, readTemplates().toMutableList())
    }

    internal fun saveForTarget(
        template: GameCaptureTemplate,
        target: TargetApplicationInfo,
        confirmation: CrossAppOverwriteConfirmation?,
    ): TemplateSaveCommitResult = synchronized(STORE_LOCK) {
        val templates = readTemplates().toMutableList()
        val stored = template.id.takeIf { it != 0L }
            ?.let { id -> templates.firstOrNull { it.id == id } }
        when (val authorization = authorizeTemplateSave(stored, target, confirmation)) {
            TemplateSaveAuthorization.Authorized ->
                TemplateSaveCommitResult.Saved(saveLocked(template, templates))
            is TemplateSaveAuthorization.RequiresConfirmation ->
                TemplateSaveCommitResult.RequiresConfirmation(authorization.confirmation)
        }
    }

    fun update(template: GameCaptureTemplate): GameCaptureTemplate? = synchronized(STORE_LOCK) {
        if (template.id == 0L) return@synchronized null
        val templates = readTemplates().toMutableList()
        if (templates.none { it.id == template.id }) return@synchronized null
        saveLocked(template, templates)
    }

    /**
     * Replaces an existing template while keeping the supplied timestamps exactly as-is.
     * This is reserved for data migrations and rollback; normal user edits must use [update].
     */
    fun replacePreservingTimestamps(template: GameCaptureTemplate): GameCaptureTemplate? =
        synchronized(STORE_LOCK) {
            if (template.id == 0L) return@synchronized null
            val templates = readTemplates().toMutableList()
            if (templates.none { it.id == template.id }) return@synchronized null
            saveLocked(
                template = template,
                templates = templates,
                timestampPolicy = ExistingTemplateTimestampPolicy.PRESERVE_SUPPLIED,
            )
        }

    fun rename(id: Long, newName: String): GameCaptureTemplate? = synchronized(STORE_LOCK) {
        val cleanName = newName.trim().takeIf { it.isNotEmpty() } ?: return@synchronized null
        val templates = readTemplates().toMutableList()
        val current = templates.firstOrNull { it.id == id } ?: return@synchronized null
        saveLocked(current.copy(templateName = cleanName), templates)
    }

    fun delete(id: Long): Boolean = synchronized(STORE_LOCK) {
        val templates = readTemplates().toMutableList()
        val removed = templates.removeAll { it.id == id }
        if (!removed) return@synchronized false
        persistTemplates(templates)
        referenceStore.delete(id)
        true
    }

    fun duplicate(id: Long, newName: String): GameCaptureTemplate? = synchronized(STORE_LOCK) {
        val templates = readTemplates().toMutableList()
        val source = templates.firstOrNull { it.id == id } ?: return@synchronized null
        val name = newName.trim().takeIf { it.isNotEmpty() } ?: source.templateName
        val duplicated = saveLocked(
            source.copy(id = 0L, templateName = name, createdAt = System.currentTimeMillis()),
            templates,
        )
        referenceStore.copy(source.id, duplicated.id)
        duplicated
    }

    /** Exact resolution templates rank first; scaled matches must be confirmed in UI. */
    fun findCompatible(
        packageName: String,
        screenWidth: Int,
        screenHeight: Int,
        orientation: String = "landscape",
        includeScaledMatches: Boolean = true,
    ): List<TemplateMatch> = synchronized(STORE_LOCK) {
        readTemplates().asSequence()
            .filter { it.packageName == packageName }
            .mapNotNull { template ->
                val exact = template.screenWidth == screenWidth && template.screenHeight == screenHeight
                when {
                    exact -> TemplateMatch(template, exactResolution = true, requiresUserConfirmation = false)
                    includeScaledMatches &&
                        template.screenWidth > 0 &&
                        template.screenHeight > 0 &&
                        templateMatchesTargetDisplayOrientation(
                            template = template,
                            targetWidth = screenWidth,
                            targetHeight = screenHeight,
                            targetOrientation = orientation,
                        ) -> TemplateMatch(
                            scaleToResolution(template, screenWidth, screenHeight),
                            exactResolution = false,
                            requiresUserConfirmation = true,
                        )
                    else -> null
                }
            }
            .sortedWith(compareByDescending<TemplateMatch> { it.exactResolution }.thenByDescending { it.template.updatedAt })
            .toList()
    }

    /** Returns a transient scaled copy; call [save] only after the user confirms it. */
    fun scaleToResolution(
        template: GameCaptureTemplate,
        screenWidth: Int,
        screenHeight: Int
    ): GameCaptureTemplate {
        require(screenWidth > 0 && screenHeight > 0) { "目标分辨率必须大于 0" }
        require(template.screenWidth > 0 && template.screenHeight > 0) { "模板分辨率无效" }
        if (template.screenWidth == screenWidth && template.screenHeight == screenHeight) return template

        val scaleX = screenWidth.toFloat() / template.screenWidth
        val scaleY = screenHeight.toFloat() / template.screenHeight
        val directionScale = if (template.scrollDirection.isVertical) scaleY else scaleX
        val usesDefaultPreviewGeometry = template.hasDefaultPreviewGeometry(pixelsPerDp)
        val scaled = template.copy(
            orientation = ScreenOrientation.fromSerialized(
                value = null,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
            ).serializedValue,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            captureRegion = template.captureRegion.scale(scaleX, scaleY).clampTo(screenWidth, screenHeight),
            stitchRegion = template.stitchRegion.scale(scaleX, scaleY).clampTo(screenWidth, screenHeight),
            fixedLayers = template.fixedLayers.map { layer ->
                layer.copy(
                    repeatRegion = layer.repeatRegion
                        ?.scale(scaleX, scaleY)
                        ?.clampTo(screenWidth, screenHeight),
                )
            },
            gestureRegion = template.gestureRegion.scale(scaleX, scaleY).clampTo(screenWidth, screenHeight),
            navigationContentBoundsAtGeometry = template.navigationContentBoundsAtGeometry
                ?.scale(scaleX, scaleY)
                ?.clampTo(screenWidth, screenHeight),
            topFixedRegion = template.topFixedRegion?.scale(scaleX, scaleY)?.clampTo(screenWidth, screenHeight),
            bottomFixedRegion = template.bottomFixedRegion?.scale(scaleX, scaleY)?.clampTo(screenWidth, screenHeight),
            hudMaskRegions = template.hudMaskRegions.map {
                it.scale(scaleX, scaleY).clampTo(screenWidth, screenHeight)
            }.filterNot { it.isEmpty },
            gestureDistancePx = (template.gestureDistancePx * directionScale).roundToInt().coerceAtLeast(1),
            updatedAt = System.currentTimeMillis()
        ).withNormalizedRegions()
        return if (usesDefaultPreviewGeometry) {
            scaled.withDefaultPreviewGeometry(pixelsPerDp)
        } else {
            scaled
        }
    }

    fun clear(): Unit = synchronized(STORE_LOCK) {
        preferences.edit()
            .remove(KEY_TEMPLATES)
            .remove(KEY_NEXT_ID)
            .apply()
        referenceStore.clear()
    }

    private fun saveLocked(
        template: GameCaptureTemplate,
        templates: MutableList<GameCaptureTemplate>,
        timestampPolicy: ExistingTemplateTimestampPolicy = ExistingTemplateTimestampPolicy.TOUCH,
    ): GameCaptureTemplate {
        val existingIndex = templates.indexOfFirst { it.id == template.id && template.id != 0L }
        val now = System.currentTimeMillis()
        val saved = if (existingIndex >= 0) {
            val old = templates[existingIndex]
            applyExistingTemplateTimestamps(template, old, now, timestampPolicy)
        } else {
            template.copy(
                id = nextId(templates),
                createdAt = template.createdAt.takeIf { it > 0L } ?: now,
                updatedAt = now,
            )
        }
        if (existingIndex >= 0) templates[existingIndex] = saved else templates += saved
        persistTemplates(templates)
        return saved
    }

    private fun readTemplates(): List<GameCaptureTemplate> {
        val raw = preferences.getString(KEY_TEMPLATES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                runCatching { ModelJsonCodec.templateFromJson(json) }.getOrNull()?.let(::add)
            }
        }
    }

    private fun persistTemplates(templates: List<GameCaptureTemplate>) {
        val array = JSONArray().also { output ->
            templates.forEach { output.put(ModelJsonCodec.templateToJson(it)) }
        }
        preferences.edit().putString(KEY_TEMPLATES, array.toString()).apply()
    }

    private fun nextId(templates: List<GameCaptureTemplate>): Long {
        val stored = preferences.getLong(KEY_NEXT_ID, 1L)
        val next = maxOf(stored, (templates.maxOfOrNull { it.id } ?: 0L) + 1L)
        preferences.edit().putLong(KEY_NEXT_ID, next + 1L).apply()
        return next
    }

    companion object {
        const val DEFAULT_PREFERENCES_NAME = "game_capture_templates"
        private val STORE_LOCK = Any()
        private const val KEY_TEMPLATES = "templates_json"
        private const val KEY_NEXT_ID = "next_template_id"
    }
}
