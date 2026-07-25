package com.apricotiris.IrisShot.ui

import androidx.annotation.StringRes
import com.apricotiris.IrisShot.R

enum class ContentSortOrder(
    @param:StringRes val labelRes: Int,
    @param:StringRes val optionLabelRes: Int = labelRes,
) {
    NAME_ASC(
        R.string.sort_name_asc_short,
        R.string.sort_name_asc_option,
    ),
    NAME_DESC(
        R.string.sort_name_desc_short,
        R.string.sort_name_desc_option,
    ),
    MODIFIED_DESC(
        R.string.sort_modified_desc_short,
        R.string.sort_modified_desc_option,
    ),
    MODIFIED_ASC(
        R.string.sort_modified_asc_short,
        R.string.sort_modified_asc_option,
    ),
    SIZE_DESC(
        R.string.sort_size_desc_short,
        R.string.sort_size_desc_option,
    ),
    SIZE_ASC(
        R.string.sort_size_asc_short,
        R.string.sort_size_asc_option,
    );

    companion object {
        val resultOptions: List<ContentSortOrder> = entries
        val templateOptions: List<ContentSortOrder> = entries.filterNot {
            it == SIZE_DESC || it == SIZE_ASC
        }

        fun fromStoredValue(
            value: String?,
            allowed: List<ContentSortOrder> = entries,
        ): ContentSortOrder = allowed.firstOrNull { it.name == value } ?: MODIFIED_DESC
    }
}
