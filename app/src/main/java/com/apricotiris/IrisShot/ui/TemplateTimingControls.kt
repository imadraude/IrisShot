package com.apricotiris.IrisShot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Switch
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.apricotiris.IrisShot.R
import com.apricotiris.IrisShot.core.model.CaptureTimingOptions
import java.util.Locale
import kotlin.math.roundToInt

/** Native timing controls shared by the full-screen and floating template editors. */
@Composable
fun TemplateTimingControls(
    swipeDurationMs: Long,
    stableTimeMs: Long,
    postGestureWaitEnabled: Boolean,
    postGestureWaitMs: Long,
    minimumIntervalMs: Long,
    onSwipeDurationChanged: (Long) -> Unit,
    onStableTimeChanged: (Long) -> Unit,
    onPostGestureWaitEnabledChanged: (Boolean) -> Unit,
    onPostGestureWaitChanged: (Long) -> Unit,
    onMinimumIntervalChanged: (Long) -> Unit,
    modifier: Modifier = Modifier,
    swipeDurationModifier: Modifier = Modifier.testTag("template-swipe-duration-slider"),
    stableTimeModifier: Modifier = Modifier.testTag("template-stable-time-slider"),
    postGestureWaitModifier: Modifier = Modifier.testTag("template-post-gesture-wait-slider"),
    postGestureWaitSwitchModifier: Modifier = Modifier.testTag("template-post-gesture-wait-switch"),
    minimumIntervalModifier: Modifier = Modifier.testTag("template-minimum-interval-slider"),
    detailsAlpha: Float = 1f,
) {
    Column(modifier = modifier) {
        DiscreteTimingSlider(
            title = stringResource(R.string.timing_swipe_duration),
            description = stringResource(R.string.timing_swipe_duration_description),
            value = swipeDurationMs,
            options = CaptureTimingOptions.swipeDurationMs,
            onValueChange = onSwipeDurationChanged,
            modifier = swipeDurationModifier,
            detailsAlpha = detailsAlpha,
        )
        DiscreteTimingSlider(
            title = stringResource(R.string.timing_stable_time),
            description = stringResource(R.string.timing_stable_time_description),
            value = stableTimeMs,
            options = CaptureTimingOptions.stableTimeMs,
            onValueChange = onStableTimeChanged,
            modifier = stableTimeModifier,
            detailsAlpha = detailsAlpha,
        )
        DiscreteTimingSlider(
            title = stringResource(R.string.timing_post_gesture_wait),
            description = stringResource(R.string.timing_post_gesture_wait_description),
            value = postGestureWaitMs,
            options = CaptureTimingOptions.postGestureWaitMs,
            onValueChange = onPostGestureWaitChanged,
            modifier = postGestureWaitModifier,
            enabled = postGestureWaitEnabled,
            detailsAlpha = detailsAlpha,
            headerControl = {
                CompactTimingSwitch(
                    checked = postGestureWaitEnabled,
                    onCheckedChange = onPostGestureWaitEnabledChanged,
                    contentDescription = stringResource(R.string.timing_post_gesture_wait),
                    modifier = postGestureWaitSwitchModifier,
                )
            },
        )
        DiscreteTimingSlider(
            title = stringResource(R.string.timing_minimum_interval),
            description = stringResource(R.string.timing_minimum_interval_description),
            value = minimumIntervalMs,
            options = CaptureTimingOptions.minimumIntervalMs,
            onValueChange = onMinimumIntervalChanged,
            modifier = minimumIntervalModifier,
            detailsAlpha = detailsAlpha,
        )
    }
}

@Composable
private fun DiscreteTimingSlider(
    title: String,
    description: String,
    value: Long,
    options: List<Long>,
    onValueChange: (Long) -> Unit,
    modifier: Modifier,
    enabled: Boolean = true,
    detailsAlpha: Float,
    headerControl: (@Composable () -> Unit)? = null,
) {
    val selectedIndex = CaptureTimingOptions.nearestIndex(value, options)
    val millisecondsFormat = stringResource(R.string.milliseconds_value)
    SettingsSliderItem(
        title = title,
        value = selectedIndex.toFloat(),
        range = 0f..options.lastIndex.toFloat(),
        steps = (options.size - 2).coerceAtLeast(0),
        onValueChange = { sliderValue ->
            onValueChange(options[sliderValue.roundToInt().coerceIn(options.indices)])
        },
        valueFormatter = { sliderValue ->
            String.format(
                Locale.getDefault(),
                millisecondsFormat,
                options[sliderValue.roundToInt().coerceIn(options.indices)],
            )
        },
        description = description,
        modifier = modifier,
        enabled = enabled,
        detailsAlpha = detailsAlpha,
        headerControl = headerControl,
    )
}

@Composable
private fun CompactTimingSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(width = 42.dp, height = 24.dp)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Switch(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier
                    .scale(0.72f)
                    .clearAndSetSemantics {},
            )
        }
    }
}
