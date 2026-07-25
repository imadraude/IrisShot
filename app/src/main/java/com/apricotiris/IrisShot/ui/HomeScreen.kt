package com.apricotiris.IrisShot.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apricotiris.IrisShot.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    isActive: () -> Boolean,
    permissions: PermissionUiState,
    service: CaptureServiceUiState,
    compact: Boolean,
    onProjection: () -> Unit,
    onProjectionPermission: () -> Unit = onProjection,
    onOverlay: () -> Unit,
    onAccessibility: () -> Unit,
    onNotification: () -> Unit,
    onShizukuModeChange: (Boolean) -> Unit,
    onOpenOverlay: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    CancelScrollWhenHidden(isActive, listState)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("home-content"),
        state = listState,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item(key = "permissions", contentType = "permissions") {
            PermissionSection(
                permissions = permissions,
                captureRunning = service.running,
                onShizukuModeChange = onShizukuModeChange,
                onProjection = onProjectionPermission,
                onOverlay = onOverlay,
                onAccessibility = onAccessibility,
                onNotification = onNotification,
            )
        }

        item(key = "actions", contentType = "actions") {
            CaptureActions(
                overlayGranted = permissions.overlayGranted,
                accessibilityGranted = permissions.accessibilityGranted,
                shizukuModeReady = permissions.shizukuModeReady,
                running = service.running,
                onProjection = onProjection,
                onStop = onStop,
            )
        }

        item(key = "instructions", contentType = "instructions") {
            UsageInstructionsSection()
        }
    }

}

@Composable
private fun UsageInstructionsSection() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionHeader(title = stringResource(R.string.home_instructions))
        Text(
            text = stringResource(R.string.home_instructions_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            text = stringResource(R.string.home_instructions_capture_warning),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun PermissionSection(
    permissions: PermissionUiState,
    captureRunning: Boolean,
    onShizukuModeChange: (Boolean) -> Unit,
    onProjection: () -> Unit,
    onOverlay: () -> Unit,
    onAccessibility: () -> Unit,
    onNotification: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionHeader(title = stringResource(R.string.home_permissions_services))
        Column(modifier = Modifier.fillMaxWidth()) {
            if (permissions.shizukuInstalled) {
                PermissionStatusItem(
                    title = stringResource(R.string.home_use_shizuku),
                    description = when {
                        !permissions.shizukuBinderAvailable ->
                            stringResource(R.string.home_shizuku_service_not_running)
                        !permissions.shizukuPermissionGranted ->
                            stringResource(R.string.home_shizuku_authorize_description)
                        else -> stringResource(R.string.home_shizuku_ready_description)
                    },
                    icon = Icons.Default.Security,
                    granted = permissions.shizukuPermissionGranted,
                    statusDescription = stringResource(
                        if (permissions.shizukuModeEnabled) R.string.status_enabled
                        else R.string.status_disabled,
                    ),
                    actionable = !captureRunning,
                    onClick = { onShizukuModeChange(!permissions.shizukuModeEnabled) },
                    customTrailingContent = {
                        Switch(
                            checked = permissions.shizukuModeEnabled,
                            onCheckedChange = null,
                            modifier = Modifier
                                .testTag("permission-shizuku-switch")
                                .alpha(if (captureRunning) 0.5f else 1f),
                        )
                    },
                    modifier = Modifier.testTag("permission-shizuku"),
                )
            }
            PermissionStatusItem(
                title = stringResource(R.string.home_screen_capture),
                description = stringResource(R.string.home_screen_capture_description),
                icon = Icons.Default.PlayArrow,
                granted = permissions.projectionGranted,
                statusDescription = stringResource(
                    if (permissions.projectionGranted) R.string.status_authorized
                    else R.string.status_unauthorized,
                ),
                actionable = !permissions.shizukuModeReady && !permissions.projectionGranted,
                onClick = onProjection,
                trailingText = if (permissions.shizukuModeReady) {
                    stringResource(R.string.status_not_required)
                } else null,
                modifier = Modifier.testTag("permission-projection"),
            )
            PermissionStatusItem(
                title = stringResource(R.string.home_overlay),
                description = stringResource(R.string.home_overlay_description),
                icon = Icons.Default.Settings,
                granted = permissions.overlayGranted,
                statusDescription = stringResource(
                    if (permissions.overlayGranted) R.string.status_on else R.string.status_off,
                ),
                actionable = !permissions.overlayGranted,
                onClick = onOverlay,
                modifier = Modifier.testTag("permission-overlay"),
            )
            PermissionStatusItem(
                title = stringResource(R.string.home_accessibility),
                description = stringResource(R.string.home_accessibility_description),
                icon = Icons.Default.Info,
                granted = permissions.accessibilityGranted,
                statusDescription = stringResource(
                    if (permissions.accessibilityGranted) R.string.status_on else R.string.status_off,
                ),
                actionable = !permissions.shizukuModeReady && !permissions.accessibilityGranted,
                onClick = onAccessibility,
                trailingText = if (permissions.shizukuModeReady) {
                    stringResource(R.string.status_not_required)
                } else null,
                modifier = Modifier.testTag("permission-accessibility"),
            )
            PermissionStatusItem(
                title = stringResource(R.string.home_notification_permission),
                description = stringResource(R.string.home_notification_description),
                icon = Icons.Default.Notifications,
                granted = permissions.notificationGranted,
                statusDescription = stringResource(
                    if (permissions.notificationGranted) R.string.status_on else R.string.status_off,
                ),
                actionable = !permissions.notificationGranted,
                onClick = onNotification,
                modifier = Modifier.testTag("permission-notification"),
            )
        }
    }
}

@Composable
private fun CaptureActions(
    overlayGranted: Boolean,
    accessibilityGranted: Boolean,
    shizukuModeReady: Boolean,
    running: Boolean,
    onProjection: () -> Unit,
    onStop: () -> Unit,
) {
    val hasCorePermissions = overlayGranted && (shizukuModeReady || accessibilityGranted)
    val buttonText = stringResource(
        if (hasCorePermissions) R.string.home_start_capture else R.string.home_complete_permissions,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!running) {
            Button(
                onClick = onProjection,
                enabled = hasCorePermissions,
                colors = PrimaryButtonColors,
                modifier = Modifier.fillMaxWidth().height(AppButtonHeight),
            ) {
                Text(buttonText, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            }
        }
        if (running) {
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().height(AppButtonHeight),
            ) {
                Text(
                    stringResource(R.string.home_stop_capture),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
