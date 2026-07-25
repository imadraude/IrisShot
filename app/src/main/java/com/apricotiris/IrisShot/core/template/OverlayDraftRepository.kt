package com.apricotiris.IrisShot.core.template

import android.content.Context
import com.apricotiris.IrisShot.core.model.GameCaptureTemplate
import com.apricotiris.IrisShot.core.model.ModelJsonCodec
import org.json.JSONObject

/**
 * Stores the last overlay configuration separately from the user's template library.
 *
 * A draft is never returned by [TemplateRepository.getAll] and therefore cannot appear as an
 * automatically-created template. It only keeps unsaved controls stable across captures and
 * service recreation.
 */
class OverlayDraftRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): GameCaptureTemplate? {
        val raw = preferences.getString(KEY_DRAFT, null) ?: return null
        return runCatching {
            ModelJsonCodec.templateFromJson(JSONObject(raw)).withNormalizedRegions()
        }.getOrNull()
    }

    fun save(template: GameCaptureTemplate) {
        preferences.edit()
            .putString(KEY_DRAFT, ModelJsonCodec.templateToJson(template).toString())
            .apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_DRAFT).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "game_capture_overlay_draft"
        private const val KEY_DRAFT = "draft_json"
    }
}
