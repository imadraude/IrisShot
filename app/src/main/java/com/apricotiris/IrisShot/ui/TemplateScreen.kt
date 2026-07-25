package com.apricotiris.IrisShot.ui

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apricotiris.IrisShot.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateScreen(
    isActive: () -> Boolean,
    templates: List<TemplateUiModel>,
    onEdit: (TemplateUiModel) -> Unit,
    onRename: (TemplateUiModel, String) -> Unit,
    onDuplicate: (TemplateUiModel) -> Unit,
    onDelete: (TemplateUiModel) -> Unit,
    onNewTemplate: () -> Unit,
    onDeleteSelected: (Set<Long>) -> Unit = {},
    onSelectionActionsChange: (ContentSelectionActions?) -> Unit = {},
    sortOrder: ContentSortOrder = ContentSortOrder.MODIFIED_DESC,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var isSearchFocused by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<TemplateUiModel?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<TemplateUiModel?>(null) }
    var deleteSelectionRequested by remember { mutableStateOf(false) }
    var expandedMenuKey by remember { mutableStateOf<String?>(null) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val active = isActive()
    val listState = rememberLazyListState()
    CancelScrollWhenHidden(isActive, listState)

    val filtered = remember(templates, query, sortOrder) {
        val matching = templates.filter {
            query.isBlank() || it.name.contains(query, ignoreCase = true) ||
                it.appName.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
        sortTemplates(matching, sortOrder)
    }
    val allTemplateIds = remember(templates) { templates.mapTo(linkedSetOf()) { it.id } }
    val visibleTemplateIds = remember(filtered) { filtered.mapTo(linkedSetOf()) { it.id } }
    val allSelected = visibleTemplateIds.isNotEmpty() && selectedIds.containsAll(visibleTemplateIds)
    val searchDescription = stringResource(R.string.template_search)

    LaunchedEffect(active, allTemplateIds, visibleTemplateIds) {
        selectedIds = if (active) {
            selectedIds.intersect(allTemplateIds).intersect(visibleTemplateIds)
        } else {
            deleteSelectionRequested = false
            expandedMenuKey = null
            emptySet()
        }
    }
    LaunchedEffect(selectedIds) {
        if (selectedIds.isEmpty()) deleteSelectionRequested = false
    }
    BackHandler(enabled = active && selectedIds.isNotEmpty()) {
        selectedIds = emptySet()
    }

    val selectionActions = remember(selectedIds, active, visibleTemplateIds, allSelected) {
        if (!active || selectedIds.isEmpty()) {
            null
        } else {
            ContentSelectionActions(
                selectedCount = selectedIds.size,
                allSelected = allSelected,
                onClose = { selectedIds = emptySet() },
                onToggleSelectAll = {
                    selectedIds = if (allSelected) emptySet() else visibleTemplateIds
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
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
            // Search Box
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .onFocusChanged { isSearchFocused = it.isFocused }
                        .border(
                            width = if (isSearchFocused) 2.dp else 1.dp,
                            color = if (isSearchFocused) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = RoundedCornerShape(20.dp)
                        )
                        .semantics { contentDescription = searchDescription },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.common_clear),
                                )
                            }
                        }
                    },
                    placeholder = {
                        Text(
                            stringResource(R.string.template_search),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    shape = RoundedCornerShape(20.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }

            if (filtered.isEmpty()) {
                if (query.isNotEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.template_no_search_results),
                        description = stringResource(R.string.template_try_another_search),
                        icon = Icons.AutoMirrored.Filled.List,
                        actionLabel = stringResource(R.string.template_clear_search),
                        onAction = { query = "" },
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    EmptyState(
                        title = stringResource(R.string.template_empty),
                        description = stringResource(R.string.template_empty_description),
                        icon = Icons.AutoMirrored.Filled.List,
                        actionLabel = stringResource(R.string.new_template),
                        onAction = onNewTemplate,
                        modifier = Modifier.weight(1f)
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
                                R.string.template_all_title,
                                stringResource(sortOrder.labelRes),
                            ),
                            detail = stringResource(R.string.common_item_count, filtered.size),
                            modifier = Modifier.padding(start = 0.dp)
                        )
                    }

                    items(
                        items = filtered,
                        key = { "all_${it.id}" },
                        contentType = { "template" },
                    ) { template ->
                        val menuKey = "all_${template.id}"
                        TemplateListItem(
                            name = template.name,
                            appName = template.appName.ifBlank { template.packageName },
                            resolutionText = template.resolution,
                            directionText = if (template.directionLabelRes != 0) {
                                stringResource(template.directionLabelRes)
                            } else {
                                template.direction
                            },
                            timeText = template.updatedAtLabel,
                            selected = template.id in selectedIds,
                            selectionMode = selectedIds.isNotEmpty(),
                            menuExpanded = expandedMenuKey == menuKey,
                            onMenuClick = { expandedMenuKey = menuKey },
                            onDismissMenu = { expandedMenuKey = null },
                            onEdit = { onEdit(template) },
                            onRename = { renameTarget = template; renameValue = template.name },
                            onDuplicate = { onDuplicate(template) },
                            onDelete = { deleteTarget = template },
                            onLongClick = {
                                expandedMenuKey = null
                                selectedIds = selectedIds + template.id
                            },
                            onToggleSelection = {
                                selectedIds = if (template.id in selectedIds) {
                                    selectedIds - template.id
                                } else {
                                    selectedIds + template.id
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
                Text(stringResource(R.string.template_rename), fontWeight = FontWeight.Medium)
            },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it.take(30) },
                    label = { Text(stringResource(R.string.template_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameValue.isNotBlank(),
                    onClick = { onRename(target, renameValue.trim()); renameTarget = null },
                ) { Text(stringResource(R.string.common_save), fontWeight = FontWeight.Medium) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.template_delete_title, target.name),
            text = stringResource(R.string.template_delete_description),
            confirmLabel = stringResource(R.string.common_delete),
            dismissLabel = stringResource(R.string.common_cancel),
            isDangerous = true,
            onConfirm = { onDelete(target); deleteTarget = null },
            onDismiss = { deleteTarget = null }
        )
    }

    if (deleteSelectionRequested && selectedIds.isNotEmpty()) {
        val selectedCount = selectedIds.size
        ConfirmDialog(
            title = stringResource(R.string.template_delete_selected_title, selectedCount),
            text = stringResource(R.string.template_delete_selected_description),
            confirmLabel = stringResource(R.string.common_delete),
            dismissLabel = stringResource(R.string.common_cancel),
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

internal fun sortTemplates(
    templates: List<TemplateUiModel>,
    sortOrder: ContentSortOrder,
): List<TemplateUiModel> = templates.sortedWith { left, right ->
    when (sortOrder) {
        ContentSortOrder.NAME_ASC -> compareTemplateNames(left, right)
        ContentSortOrder.NAME_DESC -> compareTemplateNames(right, left)
        ContentSortOrder.MODIFIED_DESC -> compareValues(right.updatedAt, left.updatedAt)
            .takeUnless { it == 0 } ?: compareTemplateNames(left, right)
        ContentSortOrder.MODIFIED_ASC -> compareValues(left.updatedAt, right.updatedAt)
            .takeUnless { it == 0 } ?: compareTemplateNames(left, right)
        ContentSortOrder.SIZE_DESC -> compareValues(templatePixelCount(right), templatePixelCount(left))
            .takeUnless { it == 0 } ?: compareTemplateNames(left, right)
        ContentSortOrder.SIZE_ASC -> compareValues(templatePixelCount(left), templatePixelCount(right))
            .takeUnless { it == 0 } ?: compareTemplateNames(left, right)
    }
}

private fun templatePixelCount(template: TemplateUiModel): Long =
    template.screenWidth.coerceAtLeast(0).toLong() * template.screenHeight.coerceAtLeast(0).toLong()

private fun compareTemplateNames(left: TemplateUiModel, right: TemplateUiModel): Int =
    left.name.compareTo(right.name, ignoreCase = true)
        .takeUnless { it == 0 }
        ?: left.name.compareTo(right.name)
            .takeUnless { it == 0 }
        ?: left.id.compareTo(right.id)
