package com.apricotiris.IrisShot.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.database.ContentObserver
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.scrollableArea
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apricotiris.IrisShot.R
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    isActive: () -> Boolean,
    settings: CaptureSettingsUiState,
    onChange: (CaptureSettingsUiState) -> Unit,
    onSelectCacheDirectory: () -> Unit,
    onSelectExportDirectory: () -> Unit,
    onResetCacheDirectory: () -> Unit,
    onResetExportDirectory: () -> Unit,
    onOpenGithub: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var activeDialog by remember { mutableStateOf<String?>(null) }

    RetainedSettingsColumn(
        isActive = isActive,
        modifier = modifier.fillMaxSize(),
        bottomPadding = 32.dp,
    ) {
        // Group 1: Floating Preview
        item(key = "preview_header", contentType = "header") {
            SettingsSectionHeader(title = stringResource(R.string.settings_preview_section))
        }
        item(key = "preview_enabled", contentType = "switch") {
            SettingsSwitchItem(
                title = stringResource(R.string.settings_show_preview),
                description = stringResource(R.string.settings_show_preview_description),
                checked = settings.previewEnabled,
                onCheckedChange = { onChange(settings.copy(previewEnabled = it)) }
            )
        }
        // Group 2: Storage and Results
        item(key = "storage_header", contentType = "header") {
            SettingsSectionHeader(title = stringResource(R.string.settings_storage_section))
        }
        item(key = "cache_location", contentType = "navigation") {
            SettingsNavigationItem(
                title = stringResource(R.string.settings_cache_location),
                subtitle = stringResource(
                    R.string.settings_cache_location_description,
                    settings.cacheLocation,
                ),
                onClick = onSelectCacheDirectory,
            )
        }
        if (settings.cacheLocationIsCustom) {
            item(key = "reset_cache_location", contentType = "navigation") {
                SettingsNavigationItem(
                    title = stringResource(R.string.settings_reset_cache_location),
                    subtitle = stringResource(R.string.settings_reset_cache_description),
                    onClick = { activeDialog = "reset_cache_location" },
                )
            }
        }
        item(key = "save_location", contentType = "navigation") {
            SettingsNavigationItem(
                title = stringResource(R.string.settings_save_location),
                subtitle = stringResource(
                    R.string.settings_save_location_description,
                    settings.saveLocation,
                ),
                onClick = onSelectExportDirectory,
            )
        }
        if (settings.saveLocationIsCustom) {
            item(key = "reset_export_location", contentType = "navigation") {
                SettingsNavigationItem(
                    title = stringResource(R.string.settings_reset_save_location),
                    subtitle = stringResource(R.string.settings_reset_save_description),
                    onClick = { activeDialog = "reset_export_location" },
                )
            }
        }
        item(key = "auto_open", contentType = "switch") {
            SettingsSwitchItem(
                title = stringResource(R.string.settings_auto_open_result),
                description = stringResource(R.string.settings_auto_open_result_description),
                checked = settings.autoOpenResult,
                onCheckedChange = { onChange(settings.copy(autoOpenResult = it)) }
            )
        }
        // Group 3: Appearance
        item(key = "appearance_header", contentType = "header") {
            SettingsSectionHeader(title = stringResource(R.string.settings_appearance_section))
        }
        item(key = "app_language", contentType = "navigation") {
            SettingsNavigationItem(
                title = stringResource(R.string.settings_app_language),
                subtitle = stringResource(R.string.settings_app_language_description),
                valueText = stringResource(settings.appLanguage.labelRes),
                onClick = { activeDialog = "app_language" },
            )
        }
        item(key = "theme_mode", contentType = "navigation") {
            val themeLabel = when (settings.themeMode) {
                "LIGHT" -> stringResource(R.string.theme_light)
                "DARK" -> stringResource(R.string.theme_dark)
                else -> stringResource(R.string.theme_system)
            }
            SettingsNavigationItem(
                title = stringResource(R.string.settings_theme),
                subtitle = stringResource(R.string.settings_theme_description),
                valueText = themeLabel,
                onClick = { activeDialog = "theme_mode" }
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            item(key = "dynamic_color", contentType = "switch") {
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_dynamic_color),
                    description = stringResource(R.string.settings_dynamic_color_description),
                    checked = settings.dynamicColor,
                    onCheckedChange = { onChange(settings.copy(dynamicColor = it)) }
                )
            }
        }

        // Group 4: About
        item(key = "about_header", contentType = "header") {
            SettingsSectionHeader(title = stringResource(R.string.settings_about_section))
        }
        item(key = "version", contentType = "navigation") {
            SettingsNavigationItem(
                title = stringResource(R.string.settings_version),
                valueText = "v1.0",
                trailingIcon = null,
                modifier = Modifier.testTag("settings-version"),
            )
        }
        item(key = "github", contentType = "navigation") {
            SettingsNavigationItem(
                title = stringResource(R.string.settings_github),
                onClick = { activeDialog = "github" },
                trailingIcon = Icons.AutoMirrored.Filled.OpenInNew,
            )
        }
    }

    // Settings Dialogs
    when (activeDialog) {
        "reset_cache_location" -> {
            AlertDialog(
                onDismissRequest = { activeDialog = null },
                title = {
                    Text(
                        stringResource(R.string.settings_reset_cache_location),
                        fontWeight = FontWeight.Medium,
                    )
                },
                text = { Text(stringResource(R.string.settings_reset_cache_dialog)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            activeDialog = null
                            onResetCacheDirectory()
                        },
                    ) { Text(stringResource(R.string.common_restore), fontWeight = FontWeight.Medium) }
                },
                dismissButton = {
                    TextButton(onClick = { activeDialog = null }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
        }
        "reset_export_location" -> {
            AlertDialog(
                onDismissRequest = { activeDialog = null },
                title = {
                    Text(
                        stringResource(R.string.settings_reset_save_location),
                        fontWeight = FontWeight.Medium,
                    )
                },
                text = { Text(stringResource(R.string.settings_reset_save_dialog)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            activeDialog = null
                            onResetExportDirectory()
                        },
                    ) { Text(stringResource(R.string.common_restore), fontWeight = FontWeight.Medium) }
                },
                dismissButton = {
                    TextButton(onClick = { activeDialog = null }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
        }
        "app_language" -> {
            AlertDialog(
                onDismissRequest = { activeDialog = null },
                title = {
                    Text(
                        stringResource(R.string.settings_language_dialog),
                        fontWeight = FontWeight.Medium,
                    )
                },
                text = {
                    Column(Modifier.selectableGroup()) {
                        AppLanguage.entries.forEach { language ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .selectable(
                                        selected = language == settings.appLanguage,
                                        onClick = {
                                            activeDialog = null
                                            onChange(settings.copy(appLanguage = language))
                                        },
                                        role = Role.RadioButton,
                                    )
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = language == settings.appLanguage,
                                    onClick = null,
                                )
                                Text(
                                    text = stringResource(language.labelRes),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(start = 16.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { activeDialog = null }) {
                        Text(stringResource(R.string.common_close))
                    }
                },
            )
        }
        "theme_mode" -> {
            val modes = listOf(
                "SYSTEM" to stringResource(R.string.theme_system),
                "LIGHT" to stringResource(R.string.theme_light),
                "DARK" to stringResource(R.string.theme_dark),
            )
            AlertDialog(
                onDismissRequest = { activeDialog = null },
                title = {
                    Text(stringResource(R.string.settings_theme), fontWeight = FontWeight.Medium)
                },
                text = {
                    Column(Modifier.selectableGroup()) {
                        modes.forEach { (mode, label) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .selectable(
                                        selected = (mode == settings.themeMode),
                                        onClick = {
                                            onChange(settings.copy(themeMode = mode))
                                            activeDialog = null
                                        },
                                        role = Role.RadioButton
                                    )
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (mode == settings.themeMode),
                                    onClick = null
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(start = 16.dp)
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { activeDialog = null }) {
                        Text(stringResource(R.string.common_close))
                    }
                }
            )
        }
        "github" -> {
            AlertDialog(
                onDismissRequest = { activeDialog = null },
                title = {
                    Text(
                        stringResource(R.string.settings_external_link),
                        fontWeight = FontWeight.Medium,
                    )
                },
                text = { Text(stringResource(R.string.settings_github_repository)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            activeDialog = null
                            onOpenGithub()
                        },
                    ) {
                        Text(stringResource(R.string.settings_open_link))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { activeDialog = null }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
        }
    }
}

private class SettingsItemsScope {
    @Suppress("ComposableNaming")
    @Composable
    fun item(
        key: String,
        contentType: String,
        content: @Composable () -> Unit,
    ) {
        key(key, contentType) { content() }
    }
}

@Composable
private fun RetainedSettingsColumn(
    isActive: () -> Boolean,
    modifier: Modifier = Modifier,
    bottomPadding: androidx.compose.ui.unit.Dp,
    content: @Composable SettingsItemsScope.() -> Unit,
) {
    val itemsScope = remember { SettingsItemsScope() }
    val scrollPosition = rememberSaveable(saver = RetainedSettingsScrollState.Saver) {
        RetainedSettingsScrollState()
    }
    val scrollOffset = scrollPosition.offset
    val maxScrollOffset = remember { mutableFloatStateOf(0f) }
    val stableSemanticsRequired = rememberStableSettingsSemanticsRequired()
    val placeAllForKeyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val scrollableState = rememberScrollableState { delta ->
        if (!isActive()) return@rememberScrollableState 0f
        val previous = scrollOffset.floatValue
        val updated = (previous + delta).coerceIn(0f, maxScrollOffset.floatValue)
        scrollOffset.floatValue = updated
        updated - previous
    }
    val scrollAxisRange = remember {
        ScrollAxisRange(
            value = { scrollOffset.floatValue },
            maxValue = { maxScrollOffset.floatValue },
        )
    }
    val suppressSettingsSemantics = !stableSemanticsRequired && !placeAllForKeyboard
    val scrollSemantics = if (suppressSettingsSemantics) {
        Modifier.clearAndSetSemantics {}
    } else {
        Modifier.semantics {
            isTraversalGroup = true
            verticalScrollAxisRange = scrollAxisRange
        }
    }
    Layout(
        modifier = modifier
            .scrollableArea(scrollableState, Orientation.Vertical)
            .then(scrollSemantics),
        content = { itemsScope.content() },
    ) { measurables, constraints ->
        val childConstraints = constraints.copy(minHeight = 0, maxHeight = Int.MAX_VALUE)
        val placeables = measurables.map { it.measure(childConstraints) }
        val viewportHeight = constraints.maxHeight
        val contentHeight = placeables.sumOf { it.height } + bottomPadding.roundToPx()
        val newMaxScrollOffset = (contentHeight - viewportHeight).coerceAtLeast(0).toFloat()
        maxScrollOffset.floatValue = newMaxScrollOffset
        var currentScrollOffset = Snapshot.withoutReadObservation { scrollOffset.floatValue }
        val widthChanged =
            scrollPosition.measuredWidth >= 0 && scrollPosition.measuredWidth != constraints.maxWidth
        if (scrollPosition.restoreFromAnchor || widthChanged) {
            val anchorIndex = scrollPosition.anchorIndex.coerceIn(0, placeables.lastIndex)
            val anchorBase = placeables.take(anchorIndex).sumOf { it.height }
            val anchorHeight = placeables[anchorIndex].height.coerceAtLeast(1)
            currentScrollOffset = (
                anchorBase + scrollPosition.anchorOffset.coerceIn(0f, (anchorHeight - 1).toFloat())
            ).coerceIn(0f, newMaxScrollOffset)
            scrollOffset.floatValue = currentScrollOffset
            scrollPosition.restoreFromAnchor = false
        }
        scrollPosition.measuredWidth = constraints.maxWidth
        if (currentScrollOffset > newMaxScrollOffset) {
            currentScrollOffset = newMaxScrollOffset
            scrollOffset.floatValue = newMaxScrollOffset
        }

        layout(constraints.maxWidth, viewportHeight) {
            val viewportOffset = scrollOffset.floatValue.roundToInt()
            var itemOffset = -viewportOffset
            var anchorRecorded = false
            placeables.forEachIndexed { index, placeable ->
                val intersectsViewport =
                    itemOffset < viewportHeight && itemOffset + placeable.height > 0
                if (!anchorRecorded && itemOffset + placeable.height > 0) {
                    scrollPosition.anchorIndex = index
                    scrollPosition.anchorOffset = (-itemOffset)
                        .coerceIn(0, (placeable.height - 1).coerceAtLeast(0))
                        .toFloat()
                    anchorRecorded = true
                }
                if (placeAllForKeyboard || intersectsViewport) {
                    placeable.placeRelativeWithLayer(0, itemOffset)
                }
                itemOffset += placeable.height
            }
        }
    }
}

private class RetainedSettingsScrollState(
    var anchorIndex: Int = 0,
    var anchorOffset: Float = 0f,
    var restoreFromAnchor: Boolean = false,
) {
    val offset = mutableFloatStateOf(0f)
    var measuredWidth = -1

    companion object {
        val Saver = listSaver(
            save = { listOf(it.anchorIndex, it.anchorOffset) },
            restore = {
                RetainedSettingsScrollState(
                    anchorIndex = it[0] as Int,
                    anchorOffset = it[1] as Float,
                    restoreFromAnchor = true,
                )
            },
        )
    }
}

@Composable
private fun rememberStableSettingsSemanticsRequired(): Boolean {
    val context = LocalContext.current
    val accessibilityManager = remember(context) {
        context.getSystemService(AccessibilityManager::class.java)
    }
    var enabled by remember(accessibilityManager) {
        mutableStateOf(
            accessibilityManager?.requiresStableSettingsSemantics(context.packageName) == true
        )
    }

    DisposableEffect(context, accessibilityManager) {
        if (accessibilityManager == null) {
            onDispose {}
        } else {
            val update = {
                enabled = accessibilityManager.requiresStableSettingsSemantics(context.packageName)
            }
            val touchExplorationListener = AccessibilityManager.TouchExplorationStateChangeListener {
                update()
            }
            val accessibilityStateListener = AccessibilityManager.AccessibilityStateChangeListener {
                update()
            }
            val servicesObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    update()
                }
            }
            update()
            accessibilityManager.addTouchExplorationStateChangeListener(touchExplorationListener)
            accessibilityManager.addAccessibilityStateChangeListener(accessibilityStateListener)
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                servicesObserver,
            )
            onDispose {
                accessibilityManager.removeTouchExplorationStateChangeListener(touchExplorationListener)
                accessibilityManager.removeAccessibilityStateChangeListener(accessibilityStateListener)
                context.contentResolver.unregisterContentObserver(servicesObserver)
            }
        }
    }

    return enabled
}

private fun AccessibilityManager.requiresStableSettingsSemantics(
    excludedPackageName: String,
): Boolean {
    if (isTouchExplorationEnabled) return true

    val interactiveFeedback =
        AccessibilityServiceInfo.FEEDBACK_SPOKEN or
            AccessibilityServiceInfo.FEEDBACK_BRAILLE or
            AccessibilityServiceInfo.FEEDBACK_AUDIBLE or
            AccessibilityServiceInfo.FEEDBACK_HAPTIC
    return getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { service ->
        if (service.resolveInfo.serviceInfo.packageName == excludedPackageName) return@any false
        val serviceId = service.id.orEmpty().lowercase()
        val interactiveEvents =
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
        val filtersKeyEvents =
            service.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0 &&
                service.capabilities and
                    AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS != 0
        service.feedbackType and interactiveFeedback != 0 ||
            service.eventTypes and interactiveEvents != 0 ||
            filtersKeyEvents ||
            listOf("talkback", "screenreader", "voiceaccess", "switchaccess").any(serviceId::contains)
    }
}
