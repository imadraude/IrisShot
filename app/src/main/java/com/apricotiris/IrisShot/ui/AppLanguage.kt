package com.apricotiris.IrisShot.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import com.apricotiris.IrisShot.R
import java.util.Locale

enum class AppLanguage(
    val storedValue: String,
    val languageTag: String,
    @param:StringRes val labelRes: Int,
) {
    UKRAINIAN("UKRAINIAN", "uk", R.string.language_ukrainian),
    ENGLISH("ENGLISH", "en", R.string.language_english),
    SIMPLIFIED_CHINESE("ZH_CN", "zh-CN", R.string.language_simplified_chinese);

    companion object {
        val default: AppLanguage = UKRAINIAN

        fun fromLanguageTagOrNull(value: String?): AppLanguage? {
            val normalized = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return when {
                normalized == "uk" || normalized.startsWith("uk-") -> UKRAINIAN
                normalized == "en" || normalized.startsWith("en-") -> ENGLISH
                normalized == "zh" || normalized.startsWith("zh-") -> SIMPLIFIED_CHINESE
                else -> null
            }
        }

        fun fromStoredValue(value: String?): AppLanguage = entries.firstOrNull {
            it.storedValue.equals(value, ignoreCase = true) ||
                it.languageTag.equals(value, ignoreCase = true)
        } ?: default
    }
}

enum class NamingRule(
    val storedValue: String,
    @param:StringRes val labelRes: Int,
    private vararg val legacyValues: String,
) {
    TIMESTAMP("TIMESTAMP", R.string.naming_rule_timestamp, "时间戳", "Timestamp"),
    SEQUENCE("SEQUENCE", R.string.naming_rule_sequence, "序号", "Sequence"),
    CUSTOM_PREFIX(
        "CUSTOM_PREFIX",
        R.string.naming_rule_custom_prefix,
        "自定义命名前缀",
        "Custom filename prefix",
    );

    companion object {
        fun fromStoredValue(value: String?): NamingRule? = entries.firstOrNull { rule ->
            rule.storedValue.equals(value, ignoreCase = true) ||
                rule.legacyValues.any { it.equals(value, ignoreCase = true) }
        }

        fun normalizeStoredValue(value: String?): String =
            fromStoredValue(value)?.storedValue ?: value.orEmpty()
    }
}

object AppLocaleManager {
    fun locale(language: AppLanguage): Locale = Locale.forLanguageTag(language.languageTag)

    fun localizedContext(base: Context, language: AppLanguage): Context {
        val configuration = Configuration(base.resources.configuration)
        val locale = locale(language)
        configuration.setLocales(LocaleList(locale))
        configuration.setLayoutDirection(locale)
        return base.createConfigurationContext(configuration)
    }

    fun apply(activity: Activity, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applyFrameworkLocale(activity, language)
        } else {
            activity.recreate()
        }
    }

    fun applyFrameworkLocale(context: Context, language: AppLanguage): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val localeManager = context.getSystemService(LocaleManager::class.java)
        val requestedLocales = LocaleList.forLanguageTags(language.languageTag)
        if (localeManager.applicationLocales.toLanguageTags() == requestedLocales.toLanguageTags()) {
            return false
        }
        // Activities that do not handle locale configuration changes are recreated by the framework.
        localeManager.applicationLocales = requestedLocales
        return true
    }

    fun synchronizeFrameworkLocale(context: Context): AppLanguage {
        val preferences = AppPreferences(context)
        val storedLanguage = preferences.loadAppLanguage()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return storedLanguage

        val localeManager = context.getSystemService(LocaleManager::class.java)
        val frameworkLanguage = localeManager.applicationLocales.takeIf { !it.isEmpty }
            ?.get(0)
            ?.toLanguageTag()
            ?.let(AppLanguage::fromLanguageTagOrNull)
        if (frameworkLanguage != null) {
            if (frameworkLanguage != storedLanguage) preferences.saveAppLanguage(frameworkLanguage)
            return frameworkLanguage
        }

        localeManager.applicationLocales = LocaleList.forLanguageTags(storedLanguage.languageTag)
        return storedLanguage
    }
}

fun Context.localizedMessageOrFallback(
    message: String?,
    @StringRes fallbackRes: Int,
): String = localizedMessageOrFallback(message, getString(fallbackRes))

fun Context.localizedMessageOrFallback(
    message: String?,
    fallback: String,
): String {
    val detail = message?.trim().orEmpty()
    val selectedLanguage = AppPreferences(this).loadAppLanguage()
    val containsChinese = detail.any { character -> character.code in 0x3400..0x9fff }
    return detail.takeIf {
        it.isNotEmpty() && (selectedLanguage == AppLanguage.SIMPLIFIED_CHINESE || !containsChinese)
    } ?: fallback
}

fun Context.localizedErrorOrFallback(
    error: Throwable,
    @StringRes fallbackRes: Int,
): String = localizedMessageOrFallback(error.localizedMessage, fallbackRes)

abstract class LocalizedComponentActivity : ComponentActivity() {
    private var attachedLanguage: AppLanguage = AppLanguage.default

    override fun attachBaseContext(newBase: Context) {
        val preferences = AppPreferences(newBase)
        val storedLanguage = preferences.loadAppLanguage()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            attachedLanguage = newBase.resources.configuration.locales.takeIf { !it.isEmpty }
                ?.get(0)
                ?.toLanguageTag()
                ?.let(AppLanguage::fromLanguageTagOrNull)
                ?: storedLanguage
            if (attachedLanguage != storedLanguage) {
                preferences.saveAppLanguage(attachedLanguage)
            }
            super.attachBaseContext(newBase)
        } else {
            attachedLanguage = storedLanguage
            super.attachBaseContext(AppLocaleManager.localizedContext(newBase, attachedLanguage))
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            AppPreferences(this).loadAppLanguage() != attachedLanguage &&
            !isFinishing
        ) {
            recreate()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        attachedLanguage = newConfig.locales.takeIf { !it.isEmpty }
            ?.get(0)
            ?.toLanguageTag()
            ?.let(AppLanguage::fromLanguageTagOrNull)
            ?: AppPreferences(this).loadAppLanguage()
        super.onConfigurationChanged(newConfig)
    }
}
