package com.apricotiris.IrisShot.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.apricotiris.IrisShot.R

data class ContentSelectionActions(
    val selectedCount: Int,
    val allSelected: Boolean,
    val onClose: () -> Unit,
    val onToggleSelectAll: () -> Unit,
    val onShare: (() -> Unit)? = null,
    val onDelete: () -> Unit,
)

@Composable
fun ContentSelectionTopBar(actions: ContentSelectionActions) {
    AppTopBar(
        title = stringResource(R.string.common_selected_count, actions.selectedCount),
        navigationIcon = {
            AppTooltipIconButton(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.selection_exit),
                onClick = actions.onClose,
            )
        },
        actions = {
            AppTooltipIconButton(
                imageVector = if (actions.allSelected) Icons.Default.CheckBox else Icons.Default.SelectAll,
                contentDescription = stringResource(
                    if (actions.allSelected) R.string.selection_clear_all
                    else R.string.selection_select_all,
                ),
                onClick = actions.onToggleSelectAll,
            )
            actions.onShare?.let { onShare ->
                AppTooltipIconButton(
                    imageVector = Icons.Default.Share,
                    contentDescription = stringResource(R.string.selection_share),
                    onClick = onShare,
                )
            }
            AppTooltipIconButton(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.selection_delete),
                onClick = actions.onDelete,
            )
        },
    )
}
