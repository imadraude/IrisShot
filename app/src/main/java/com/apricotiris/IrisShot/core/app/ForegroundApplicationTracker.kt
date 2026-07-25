package com.apricotiris.IrisShot.core.app

/** A window transition reported by the accessibility service. */
data class ForegroundWindowObservation(
    val packageName: String,
    val className: String? = null,
    val observedAtMs: Long,
    val windowId: Int? = null,
    val pageSignature: String? = null,
    val kind: ForegroundObservationKind = ForegroundObservationKind.WINDOW_STATE,
)

enum class ForegroundObservationKind {
    WINDOW_STATE,
    PAGE_CONTENT,
}

data class ForegroundApplicationUpdate(
    val targetPackageName: String? = null,
    val targetChanged: Boolean = false,
    val departedLockedTarget: String? = null,
)

/**
 * Keeps overlay windows and transient System UI from replacing the last real target app while
 * still detecting Home, Recents, and another application as departures from a guarded editor.
 */
class ForegroundApplicationTracker(
    private val ownPackageName: String,
    private val homePackages: Set<String>,
    private val inputMethodPackages: Set<String>,
    private val systemUiPackages: Set<String> = setOf("com.android.systemui"),
) {
    private var latestObservedAtMs = Long.MIN_VALUE
    private var targetPackageName: String? = null
    private var targetWindowClassName: String? = null
    private var targetWindowId: Int? = null
    private var targetPageSignature: String? = null
    private var lockedTargetPackageName: String? = null
    private var lockedTargetWindowClassName: String? = null
    private var lockedTargetWindowId: Int? = null
    private var lockedTargetPageSignature: String? = null
    private var lockedAtMs = Long.MIN_VALUE
    private var transientSuppressionUntilMs = Long.MIN_VALUE
    private var lockedPageSignatureRebaseRequested = false
    private var pendingPageSignature: String? = null
    private var pendingPageSignatureFirstAtMs = Long.MIN_VALUE
    private var pendingPageSignatureConfirmations = 0

    @Synchronized
    fun observe(observation: ForegroundWindowObservation): ForegroundApplicationUpdate {
        val packageName = observation.packageName.trim()
        if (packageName.isEmpty() || observation.observedAtMs < latestObservedAtMs) {
            return ForegroundApplicationUpdate()
        }
        latestObservedAtMs = observation.observedAtMs
        val lockedTarget = lockedTargetPackageName
        val observedClassName = observation.className
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val observedWindowId = observation.windowId?.takeIf { it >= 0 }
        val observedPageSignature = observation.pageSignature
            ?.trim()
            ?.takeIf(String::isNotEmpty)

        if (packageName == ownPackageName) {
            if (
                observation.kind == ForegroundObservationKind.WINDOW_STATE &&
                lockedTarget != null &&
                observation.className.isOwnActivityWindow()
            ) {
                clearLockedTarget()
                return ForegroundApplicationUpdate(departedLockedTarget = lockedTarget)
            }
            if (lockedTarget != null && observation.kind == ForegroundObservationKind.WINDOW_STATE) {
                suppressTransientReturn(observation.observedAtMs)
            }
            return ForegroundApplicationUpdate()
        }
        if (packageName in inputMethodPackages) {
            if (lockedTarget != null && observation.kind == ForegroundObservationKind.WINDOW_STATE) {
                suppressTransientReturn(observation.observedAtMs)
            }
            return ForegroundApplicationUpdate()
        }

        if (packageName in systemUiPackages) {
            if (
                observation.kind == ForegroundObservationKind.WINDOW_STATE &&
                lockedTarget != null &&
                observation.className.isRecentsWindow()
            ) {
                clearLockedTarget()
                return ForegroundApplicationUpdate(departedLockedTarget = lockedTarget)
            }
            if (lockedTarget != null && observation.kind == ForegroundObservationKind.WINDOW_STATE) {
                suppressTransientReturn(observation.observedAtMs)
            }
            return ForegroundApplicationUpdate()
        }

        if (packageName in homePackages) {
            if (
                observation.kind == ForegroundObservationKind.WINDOW_STATE &&
                lockedTarget != null
            ) {
                clearLockedTarget()
                return ForegroundApplicationUpdate(departedLockedTarget = lockedTarget)
            }
            return ForegroundApplicationUpdate()
        }

        if (
            observation.kind == ForegroundObservationKind.PAGE_CONTENT &&
            packageName != targetPackageName &&
            packageName != lockedTarget
        ) {
            return ForegroundApplicationUpdate()
        }

        if (
            observation.kind == ForegroundObservationKind.WINDOW_STATE &&
            lockedTarget == packageName &&
            !observedClassName.isApplicationPageWindow()
        ) {
            suppressTransientReturn(observation.observedAtMs)
            return ForegroundApplicationUpdate()
        }

        if (lockedTarget == packageName && lockedTargetWindowClassName != null) {
            val isWindowState = observation.kind == ForegroundObservationKind.WINDOW_STATE
            // Shizuku's foreground query only guarantees a package name. Treat a missing
            // class as unknown rather than as a different window; otherwise a concurrent
            // accessibility callback can make every poll look like a page switch and collapse
            // the overlay while the user is still on the target screen.
            val classChanged = isWindowState && observedClassName != null &&
                observedClassName != lockedTargetWindowClassName
            val windowChanged = isWindowState &&
                lockedTargetWindowId != null &&
                observedWindowId != null &&
                observedWindowId != lockedTargetWindowId
            val contentFromAnotherWindow = !isWindowState &&
                lockedTargetWindowId != null &&
                observedWindowId != lockedTargetWindowId
            if (contentFromAnotherWindow) return ForegroundApplicationUpdate()

            val confirmedSignatureChange = observedPageSignature?.let { signature ->
                observeLockedPageSignature(
                    signature = signature,
                    observedAtMs = observation.observedAtMs,
                    canRebase = observation.kind == ForegroundObservationKind.PAGE_CONTENT,
                )
            } == true
            if (classChanged || windowChanged || confirmedSignatureChange) {
                clearLockedTarget()
                updateTargetWindow(
                    packageName,
                    observedClassName.takeIf { isWindowState } ?: targetWindowClassName,
                    observedWindowId.takeIf { isWindowState } ?: targetWindowId,
                    observedPageSignature,
                )
                return ForegroundApplicationUpdate(
                    targetPackageName = packageName,
                    departedLockedTarget = lockedTarget,
                )
            }
            targetPackageName = packageName
            if (isWindowState) {
                targetWindowClassName = observedClassName
                targetWindowId = observedWindowId
            }
            if (lockedTargetPageSignature == observedPageSignature) {
                targetPageSignature = observedPageSignature
            }
            return ForegroundApplicationUpdate(targetPackageName = packageName)
        }

        if (observation.kind == ForegroundObservationKind.PAGE_CONTENT) {
            val sameTargetWindow = packageName == targetPackageName &&
                (targetWindowId == null || observedWindowId == targetWindowId)
            if (sameTargetWindow && observedPageSignature != null) {
                targetPageSignature = observedPageSignature
            }
            return ForegroundApplicationUpdate(targetPackageName = targetPackageName)
        }

        val changed = targetPackageName != packageName
        targetPackageName = packageName
        if (observedClassName.isApplicationPageWindow()) {
            targetWindowClassName = observedClassName
            targetWindowId = observedWindowId
            if (observedPageSignature != null || changed) {
                targetPageSignature = observedPageSignature
            }
        } else if (changed) {
            targetWindowClassName = null
            targetWindowId = null
            targetPageSignature = null
        }
        if (
            lockedTarget == packageName &&
            lockedTargetWindowClassName == null &&
            observedClassName.isApplicationPageWindow()
        ) {
            lockedTargetWindowClassName = observedClassName
            lockedTargetWindowId = observedWindowId
            lockedTargetPageSignature = observedPageSignature
            lockedAtMs = observation.observedAtMs
            transientSuppressionUntilMs = Long.MIN_VALUE
            clearPendingPageSignature()
        }
        if (lockedTarget != null && packageName != lockedTarget) {
            clearLockedTarget()
            return ForegroundApplicationUpdate(
                targetPackageName = packageName,
                targetChanged = changed,
                departedLockedTarget = lockedTarget,
            )
        }
        return ForegroundApplicationUpdate(
            targetPackageName = packageName,
            targetChanged = changed,
        )
    }

    @Synchronized
    fun lockTarget(
        preferredPackageName: String? = null,
        lockedAtMs: Long = latestObservedAtMs,
    ): String? {
        val preferred = preferredPackageName
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != ownPackageName && it !in homePackages }
        return (preferred ?: targetPackageName).also { selected ->
            lockedTargetPackageName = selected
            lockedTargetWindowClassName = targetWindowClassName.takeIf {
                selected != null && selected == targetPackageName
            }
            lockedTargetWindowId = targetWindowId.takeIf {
                selected != null && selected == targetPackageName
            }
            lockedTargetPageSignature = targetPageSignature.takeIf {
                selected != null && selected == targetPackageName
            }
            this.lockedAtMs = lockedAtMs
            transientSuppressionUntilMs = Long.MIN_VALUE
            lockedPageSignatureRebaseRequested = false
            clearPendingPageSignature()
        }
    }

    @Synchronized
    fun unlockTarget() {
        clearLockedTarget()
    }

    /**
     * Makes the next page-content signature from the locked window the new baseline.
     *
     * Use this before resuming page-content sampling after a capture or recognition job. Window
     * observations and page content from another window do not consume the request.
     */
    @Synchronized
    fun requestLockedPageSignatureRebase() {
        if (lockedTargetPackageName == null) return
        lockedPageSignatureRebaseRequested = true
        clearPendingPageSignature()
    }

    @Synchronized
    fun departLockedTarget(): ForegroundApplicationUpdate {
        val lockedTarget = lockedTargetPackageName ?: return ForegroundApplicationUpdate()
        clearLockedTarget()
        return ForegroundApplicationUpdate(departedLockedTarget = lockedTarget)
    }

    @Synchronized
    fun currentTargetPackageName(): String? = targetPackageName

    private fun clearLockedTarget() {
        lockedTargetPackageName = null
        lockedTargetWindowClassName = null
        lockedTargetWindowId = null
        lockedTargetPageSignature = null
        lockedAtMs = Long.MIN_VALUE
        transientSuppressionUntilMs = Long.MIN_VALUE
        lockedPageSignatureRebaseRequested = false
        clearPendingPageSignature()
    }

    private fun suppressTransientReturn(observedAtMs: Long) {
        transientSuppressionUntilMs = maxOf(
            transientSuppressionUntilMs,
            observedAtMs + TRANSIENT_RETURN_SUPPRESSION_MS,
        )
        clearPendingPageSignature()
    }

    private fun observeLockedPageSignature(
        signature: String,
        observedAtMs: Long,
        canRebase: Boolean,
    ): Boolean {
        if (lockedAtMs != Long.MIN_VALUE && observedAtMs < lockedAtMs) return false
        if (canRebase && lockedPageSignatureRebaseRequested) {
            lockedTargetPageSignature = signature
            targetPageSignature = signature
            lockedPageSignatureRebaseRequested = false
            clearPendingPageSignature()
            return false
        }
        val baseline = lockedTargetPageSignature
        if (baseline == null) {
            lockedTargetPageSignature = signature
            targetPageSignature = signature
            clearPendingPageSignature()
            return false
        }
        if (signature == baseline) {
            clearPendingPageSignature()
            return false
        }
        if (observedAtMs <= transientSuppressionUntilMs) {
            clearPendingPageSignature()
            return false
        }

        val elapsed = observedAtMs - pendingPageSignatureFirstAtMs
        if (
            pendingPageSignature != signature ||
            pendingPageSignatureFirstAtMs == Long.MIN_VALUE ||
            elapsed > PAGE_SIGNATURE_CONFIRMATION_TIMEOUT_MS
        ) {
            pendingPageSignature = signature
            pendingPageSignatureFirstAtMs = observedAtMs
            pendingPageSignatureConfirmations = 1
            return false
        }
        pendingPageSignatureConfirmations += 1
        return pendingPageSignatureConfirmations >= REQUIRED_PAGE_SIGNATURE_CONFIRMATIONS &&
            elapsed >= PAGE_SIGNATURE_STABILITY_MS
    }

    private fun clearPendingPageSignature() {
        pendingPageSignature = null
        pendingPageSignatureFirstAtMs = Long.MIN_VALUE
        pendingPageSignatureConfirmations = 0
    }

    private fun updateTargetWindow(
        packageName: String,
        className: String?,
        windowId: Int?,
        pageSignature: String?,
    ) {
        targetPackageName = packageName
        targetWindowClassName = className
        targetWindowId = windowId
        targetPageSignature = pageSignature
    }

    private fun String?.isRecentsWindow(): Boolean {
        val normalized = this?.lowercase().orEmpty()
        return RECENTS_CLASS_MARKERS.any(normalized::contains)
    }

    private fun String?.isOwnActivityWindow(): Boolean {
        val value = this ?: return false
        return value.startsWith("$ownPackageName.") && value.endsWith("Activity")
    }

    private fun String?.isApplicationPageWindow(): Boolean {
        val normalized = this?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return false
        if (normalized.endsWith("activity")) return true
        if (normalized.startsWith("android.")) return false
        return TRANSIENT_WINDOW_CLASS_MARKERS.none(normalized::contains)
    }

    private companion object {
        const val TRANSIENT_RETURN_SUPPRESSION_MS = 600L
        const val PAGE_SIGNATURE_STABILITY_MS = 200L
        const val PAGE_SIGNATURE_CONFIRMATION_TIMEOUT_MS = 1_500L
        const val REQUIRED_PAGE_SIGNATURE_CONFIRMATIONS = 2
        val RECENTS_CLASS_MARKERS = listOf("recents", "recent", "overview")
        val TRANSIENT_WINDOW_CLASS_MARKERS = listOf(
            "dialog",
            "popup",
            "toast",
            "tooltip",
            "menu",
        )
    }
}
