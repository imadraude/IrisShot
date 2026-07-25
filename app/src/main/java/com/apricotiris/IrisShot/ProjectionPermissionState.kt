package com.apricotiris.IrisShot

import android.content.Intent
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.ArrayDeque
import java.util.EnumSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PendingProjectionGrant(
    val resultCode: Int,
    val data: Intent,
)

/** Retains an unused projection grant across configuration changes, but never process death. */
internal class ProjectionPermissionViewModel : ViewModel() {
    val requests = PendingProjectionRequest<PendingProjectionGrant>()
    private val automaticGrants = AutomaticPermissionGrantQueue()
    private val _automaticGrantSignal = MutableLiveData(0L)
    val automaticGrantSignal: LiveData<Long> = _automaticGrantSignal

    fun requestAutomaticGrant(
        kind: AutomaticPermissionKind,
        operation: () -> Boolean,
    ): Boolean {
        if (!automaticGrants.tryStart(kind)) return false
        viewModelScope.launch {
            val commandSucceeded = withContext(Dispatchers.IO) {
                runCatching(operation).getOrDefault(false)
            }
            val result = automaticGrants.complete(kind, commandSucceeded) ?: return@launch
            _automaticGrantSignal.value = result.id
        }
        return true
    }

    fun takeAutomaticGrantResult(): AutomaticPermissionGrantResult? = automaticGrants.takeResult()

    override fun onCleared() {
        requests.clear()
        automaticGrants.clear()
    }
}

internal class PendingProjectionRequest<T : Any> {
    private var pending: T? = null
    private var startCaptureAfterGrant = false
    private var requestInFlight = false

    val hasPending: Boolean
        get() = pending != null

    val isRequestInFlight: Boolean
        get() = requestInFlight

    fun begin(startCaptureAfterGrant: Boolean): Boolean {
        if (requestInFlight) return false
        pending = null
        this.startCaptureAfterGrant = startCaptureAfterGrant
        requestInFlight = true
        return true
    }

    fun complete(value: T): Boolean? {
        if (!requestInFlight) return null
        requestInFlight = false
        pending = value
        return startCaptureAfterGrant.also { startCaptureAfterGrant = false }
    }

    fun take(): T? = pending.also { pending = null }

    fun cancelRequest(): Boolean {
        if (!requestInFlight) return false
        clear()
        return true
    }

    fun clear() {
        pending = null
        startCaptureAfterGrant = false
        requestInFlight = false
    }
}

internal enum class AutomaticPermissionKind {
    OVERLAY,
    NOTIFICATION,
}

internal data class AutomaticPermissionGrantResult(
    val id: Long,
    val kind: AutomaticPermissionKind,
    val commandSucceeded: Boolean,
)

internal class AutomaticPermissionGrantQueue {
    private val inFlight = EnumSet.noneOf(AutomaticPermissionKind::class.java)
    private val completed = ArrayDeque<AutomaticPermissionGrantResult>()
    private var nextResultId = 1L

    @Synchronized
    fun tryStart(kind: AutomaticPermissionKind): Boolean = inFlight.add(kind)

    @Synchronized
    fun complete(
        kind: AutomaticPermissionKind,
        commandSucceeded: Boolean,
    ): AutomaticPermissionGrantResult? {
        if (!inFlight.remove(kind)) return null
        return AutomaticPermissionGrantResult(
            id = nextResultId++,
            kind = kind,
            commandSucceeded = commandSucceeded,
        ).also(completed::addLast)
    }

    @Synchronized
    fun takeResult(): AutomaticPermissionGrantResult? = completed.pollFirst()

    @Synchronized
    fun clear() {
        inFlight.clear()
        completed.clear()
    }
}

internal fun isCaptureServiceActive(
    processRunning: Boolean,
    stateName: String,
): Boolean = processRunning && stateName !in INACTIVE_CAPTURE_STATES

private val INACTIVE_CAPTURE_STATES = setOf("IDLE", "RESULT_EDIT")
