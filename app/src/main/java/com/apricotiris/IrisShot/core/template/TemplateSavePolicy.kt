package com.apricotiris.IrisShot.core.template

import com.apricotiris.IrisShot.core.model.GameCaptureTemplate

data class TargetApplicationInfo(
    val packageName: String,
    val appName: String,
) {
    val displayName: String get() = appName.ifBlank { packageName }
}

data class CrossAppOverwriteConfirmation(
    val templateId: Long,
    val sourcePackageName: String,
    val sourceAppName: String,
    val sourceUpdatedAt: Long,
    val targetPackageName: String,
    val targetAppName: String,
)

internal sealed interface TemplateSaveAuthorization {
    data object Authorized : TemplateSaveAuthorization
    data class RequiresConfirmation(
        val confirmation: CrossAppOverwriteConfirmation,
    ) : TemplateSaveAuthorization
}

internal sealed interface TemplateSaveCommitResult {
    data class Saved(val template: GameCaptureTemplate) : TemplateSaveCommitResult
    data class RequiresConfirmation(
        val confirmation: CrossAppOverwriteConfirmation,
    ) : TemplateSaveCommitResult
}

internal fun authorizeTemplateSave(
    storedTemplate: GameCaptureTemplate?,
    target: TargetApplicationInfo,
    confirmation: CrossAppOverwriteConfirmation?,
): TemplateSaveAuthorization {
    if (storedTemplate == null ||
        storedTemplate.packageName.isBlank() ||
        storedTemplate.packageName == target.packageName
    ) {
        return TemplateSaveAuthorization.Authorized
    }

    val expected = CrossAppOverwriteConfirmation(
        templateId = storedTemplate.id,
        sourcePackageName = storedTemplate.packageName,
        sourceAppName = storedTemplate.appName.ifBlank { storedTemplate.packageName },
        sourceUpdatedAt = storedTemplate.updatedAt,
        targetPackageName = target.packageName,
        targetAppName = target.displayName,
    )
    return if (confirmation == expected) {
        TemplateSaveAuthorization.Authorized
    } else {
        TemplateSaveAuthorization.RequiresConfirmation(expected)
    }
}
