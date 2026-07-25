package com.apricotiris.IrisShot.capture

import java.util.concurrent.Executor

/** Delivers the latest submitted value without making submitters wait for the consumer. */
internal class ConflatedExecutorDispatcher<T : Any>(
    private val executor: Executor,
    private val deliver: (T) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val stateLock = Any()
    private var pending: T? = null
    private var pendingVersion = 0L
    private var drainScheduled = false
    private var closed = false

    fun submit(value: T) {
        val scheduledVersion = synchronized(stateLock) {
            if (closed) return
            pending = value
            pendingVersion += 1L
            if (drainScheduled) {
                null
            } else {
                drainScheduled = true
                pendingVersion
            }
        }
        scheduledVersion?.let(::scheduleDrain)
    }

    private fun scheduleDrain(scheduledVersion: Long) {
        try {
            executor.execute(::drain)
        } catch (error: Throwable) {
            val retryVersion = synchronized(stateLock) {
                when {
                    closed -> {
                        pending = null
                        drainScheduled = false
                        null
                    }
                    pending != null && pendingVersion != scheduledVersion -> pendingVersion
                    else -> {
                        pending = null
                        drainScheduled = false
                        null
                    }
                }
            }
            onFailure(error)
            retryVersion?.let(::scheduleDrain)
        }
    }

    private fun drain() {
        while (true) {
            val value = synchronized(stateLock) {
                if (closed) {
                    pending = null
                    drainScheduled = false
                    return
                }
                pending.also { pending = null } ?: run {
                    drainScheduled = false
                    return
                }
            }
            runCatching { deliver(value) }.onFailure(onFailure)
        }
    }

    fun close() {
        synchronized(stateLock) {
            closed = true
            pending = null
        }
    }
}
