package com.apricotiris.IrisShot.result

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apricotiris.IrisShot.R
import com.apricotiris.IrisShot.core.storage.CaptureResultSummary
import com.apricotiris.IrisShot.ui.CancelScrollWhenHidden
import com.apricotiris.IrisShot.ui.ConfirmDialog
import com.apricotiris.IrisShot.ui.ContentSelectionActions
import com.apricotiris.IrisShot.ui.ContentSortOrder
import com.apricotiris.IrisShot.ui.EmptyState
import com.apricotiris.IrisShot.ui.ResultListItem
import com.apricotiris.IrisShot.ui.SettingsSectionHeader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ResultListScreen(
    isActive: () -> Boolean,
    results: List<CaptureResultSummary>,
    thumbnails: Map<String, Bitmap?>,
    onView: (String) -> Unit,
    onShare: (CaptureResultSummary) -> Unit,
    onShareSelected: (List<CaptureResultSummary>) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteSelected: (Set<String>) -> Unit,
    onRequestThumbnail: (String) -> Unit,
    onSelectionActionsChange: (ContentSelectionActions?) -> Unit = {},
    sortOrder: ContentSortOrder = ContentSortOrder.MODIFIED_DESC,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var isSearchFocused by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<CaptureResultSummary?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<CaptureResultSummary?>(null) }
    var deleteSelectionRequested by remember { mutableStateOf(false) }
    var expandedMenuId by remember { mutableStateOf<String?>(null) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val active = isActive()
    val listState = rememberLazyListState()
    val searchDescription = stringResource(R.string.result_list_search_description)
    val clearDescription = stringResource(R.string.result_action_clear)
    CancelScrollWhenHidden(isActive, listState)

    val filteredResults = remember(results, query, sortOrder) {
        filterResults(results, query, sortOrder)
    }
    val allResultIds = remember(results) { results.mapTo(linkedSetOf()) { it.id } }
    val visibleResultIds = remember(filteredResults) {
        filteredResults.mapTo(linkedSetOf()) { it.id }
    }
    val allSelected = visibleResultIds.isNotEmpty() && selectedIds.containsAll(visibleResultIds)

    LaunchedEffect(active, allResultIds, visibleResultIds) {
        selectedIds = if (active) {
            selectedIds.intersect(allResultIds).intersect(visibleResultIds)
        } else {
            deleteSelectionRequested = false
            expandedMenuId = null
            emptySet()
        }
    }
    LaunchedEffect(selectedIds) {
        if (selectedIds.isEmpty()) deleteSelectionRequested = false
    }
    BackHandler(enabled = active && selectedIds.isNotEmpty()) {
        selectedIds = emptySet()
    }

    val selectionActions = remember(
        selectedIds,
        active,
        visibleResultIds,
        allSelected,
        results,
        onShareSelected,
    ) {
        if (!active || selectedIds.isEmpty()) {
            null
        } else {
            ContentSelectionActions(
                selectedCount = selectedIds.size,
                allSelected = allSelected,
                onClose = { selectedIds = emptySet() },
                onToggleSelectAll = {
                    selectedIds = if (allSelected) emptySet() else visibleResultIds
                },
                onShare = {
                    onShareSelected(results.filter { it.id in selectedIds })
                    selectedIds = emptySet()
                },
                onDelete = { deleteSelectionRequested = true },
            )
        }
    }
    LaunchedEffect(selectionActions, onSelectionActionsChange) {
        onSelectionActionsChange(selectionActions)
    }
    DisposableEffect(onSelectionActionsChange) {
        onDispose { onSelectionActionsChange(null) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(56.dp)
                .onFocusChanged { isSearchFocused = it.isFocused }
                .border(
                    width = if (isSearchFocused) 2.dp else 1.dp,
                    color = if (isSearchFocused) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = RoundedCornerShape(20.dp),
                )
                .semantics { contentDescription = searchDescription },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = clearDescription)
                    }
                }
            },
            placeholder = {
                Text(
                    text = stringResource(R.string.result_list_search_placeholder),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            shape = RoundedCornerShape(20.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        if (filteredResults.isEmpty()) {
            if (query.isNotEmpty()) {
                EmptyState(
                    title = stringResource(R.string.result_list_no_matches_title),
                    description = stringResource(R.string.result_list_no_matches_description),
                    icon = Icons.AutoMirrored.Filled.List,
                    actionLabel = stringResource(R.string.result_list_clear_search),
                    onAction = { query = "" },
                    modifier = Modifier.weight(1f),
                )
            } else {
                EmptyState(
                    title = stringResource(R.string.result_list_empty_title),
                    description = stringResource(R.string.result_list_empty_description),
                    icon = Icons.AutoMirrored.Filled.List,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "all_header", contentType = "header") {
                    SettingsSectionHeader(
                        title = stringResource(
                            R.string.result_list_all_header,
                            resultSortOrderLabel(sortOrder),
                        ),
                        detail = pluralStringResource(
                            R.plurals.result_list_result_count,
                            filteredResults.size,
                            filteredResults.size,
                        ),
                        modifier = Modifier.padding(start = 0.dp),
                    )
                }
                items(
                    items = filteredResults,
                    key = { it.id },
                    contentType = { "result" },
                ) { summary ->
                    val modifiedAt = summary.updatedAt.takeIf { it > 0L } ?: summary.createdAt
                    val date = Date(modifiedAt)
                    val shortTime = remember(modifiedAt) {
                        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(date)
                    }
                    val thumbnail = thumbnails[summary.id]
                    LaunchedEffect(summary.id, thumbnail, active) {
                        if (active && thumbnail == null) onRequestThumbnail(summary.id)
                    }

                    ResultListItem(
                        filename = resultTitle(summary),
                        resolutionText = pluralStringResource(
                            R.plurals.result_frame_count_compact,
                            summary.frameCount,
                            summary.frameCount,
                        ),
                        sizeText = formatStoredSize(summary.storedSizeBytes),
                        timeText = shortTime,
                        thumbnail = thumbnail,
                        selected = summary.id in selectedIds,
                        selectionMode = selectedIds.isNotEmpty(),
                        menuExpanded = expandedMenuId == summary.id,
                        onMenuClick = { expandedMenuId = summary.id },
                        onDismissMenu = { expandedMenuId = null },
                        onView = { onView(summary.id) },
                        onShare = { onShare(summary) },
                        onRename = {
                            renameTarget = summary
                            renameValue = resultTitle(summary)
                        },
                        onDelete = { deleteTarget = summary },
                        onLongClick = {
                            expandedMenuId = null
                            selectedIds = selectedIds + summary.id
                        },
                        onToggleSelection = {
                            selectedIds = if (summary.id in selectedIds) {
                                selectedIds - summary.id
                            } else {
                                selectedIds + summary.id
                            }
                        },
                    )
                }
            }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = {
                Text(
                    stringResource(R.string.result_rename_title),
                    fontWeight = FontWeight.Medium,
                )
            },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it.take(30) },
                    label = { Text(stringResource(R.string.result_rename_name_label)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameValue.isNotBlank(),
                    onClick = {
                        onRename(target.id, renameValue.trim())
                        renameTarget = null
                    },
                ) {
                    Text(
                        stringResource(R.string.result_action_save),
                        fontWeight = FontWeight.Medium,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.result_action_cancel))
                }
            },
        )
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.result_delete_single_title),
            text = stringResource(R.string.result_delete_single_description),
            confirmLabel = stringResource(R.string.result_action_delete),
            dismissLabel = stringResource(R.string.result_action_cancel),
            isDangerous = true,
            onConfirm = {
                onDelete(target.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }

    if (deleteSelectionRequested && selectedIds.isNotEmpty()) {
        val selectedCount = selectedIds.size
        ConfirmDialog(
            title = pluralStringResource(
                R.plurals.result_delete_selected_title,
                selectedCount,
                selectedCount,
            ),
            text = stringResource(R.string.result_delete_selected_description),
            confirmLabel = stringResource(R.string.result_action_delete),
            dismissLabel = stringResource(R.string.result_action_cancel),
            isDangerous = true,
            onConfirm = {
                val idsToDelete = selectedIds
                selectedIds = emptySet()
                deleteSelectionRequested = false
                onDeleteSelected(idsToDelete)
            },
            onDismiss = { deleteSelectionRequested = false },
        )
    }
}

internal fun resultTitle(summary: CaptureResultSummary): String =
    summary.recordName?.trim()?.takeIf(String::isNotEmpty)
        ?: summary.templateName
            ?.trim()
            ?.takeIf { summary.schemaVersion < 5 && it.isNotEmpty() }
        ?: summary.appName

internal fun filterResults(
    results: List<CaptureResultSummary>,
    query: String,
    sortOrder: ContentSortOrder = ContentSortOrder.MODIFIED_DESC,
): List<CaptureResultSummary> {
    val term = query.trim()
    val matching = results
        .asSequence()
        .filter { summary ->
            term.isEmpty() ||
                resultTitle(summary).contains(term, ignoreCase = true) ||
                summary.appName.contains(term, ignoreCase = true) ||
                summary.templateName?.contains(term, ignoreCase = true) == true
        }
        .toList()
    return sortResults(matching, sortOrder)
}

internal fun sortResults(
    results: List<CaptureResultSummary>,
    sortOrder: ContentSortOrder,
): List<CaptureResultSummary> = results.sortedWith { left, right ->
    when (sortOrder) {
        ContentSortOrder.NAME_ASC -> compareResultNames(left, right)
        ContentSortOrder.NAME_DESC -> compareResultNames(right, left)
        ContentSortOrder.MODIFIED_DESC -> compareValues(resultModifiedAt(right), resultModifiedAt(left))
            .takeUnless { it == 0 } ?: compareResultNames(left, right)
        ContentSortOrder.MODIFIED_ASC -> compareValues(resultModifiedAt(left), resultModifiedAt(right))
            .takeUnless { it == 0 } ?: compareResultNames(left, right)
        ContentSortOrder.SIZE_DESC -> compareValues(right.storedSizeBytes, left.storedSizeBytes)
            .takeUnless { it == 0 } ?: compareResultNames(left, right)
        ContentSortOrder.SIZE_ASC -> compareValues(left.storedSizeBytes, right.storedSizeBytes)
            .takeUnless { it == 0 } ?: compareResultNames(left, right)
    }
}

private fun compareResultNames(left: CaptureResultSummary, right: CaptureResultSummary): Int =
    resultTitle(left).compareTo(resultTitle(right), ignoreCase = true)
        .takeUnless { it == 0 }
        ?: resultTitle(left).compareTo(resultTitle(right))
            .takeUnless { it == 0 }
        ?: left.id.compareTo(right.id)

private fun resultModifiedAt(summary: CaptureResultSummary): Long =
    summary.updatedAt.takeIf { it > 0L } ?: summary.createdAt

internal fun formatStoredSize(bytes: Long): String =
    String.format(Locale.CHINA, "%.2fM", bytes.coerceAtLeast(0L) / (1024.0 * 1024.0))

@Composable
private fun resultSortOrderLabel(sortOrder: ContentSortOrder): String =
    stringResource(sortOrder.labelRes)
