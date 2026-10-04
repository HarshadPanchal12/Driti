package com.remoteagent

import java.util.concurrent.atomic.AtomicReference

object AgentState {
    private val lastHeartbeatAt = AtomicReference<String?>(null)
    private val lastCommand = AtomicReference<String?>(null)
    private val lastResult = AtomicReference<String?>(null)
    private val listeners = mutableSetOf<() -> Unit>()

    fun setLastHeartbeat(value: String) {
        lastHeartbeatAt.set(value)
        notifyListeners()
    }

    fun setLastCommand(value: String) {
        lastCommand.set(value)
        notifyListeners()
    }

    fun setLastResult(value: String) {
        lastResult.set(value)
        notifyListeners()
    }

    fun getLastHeartbeat(): String? = lastHeartbeatAt.get()

    fun getLastCommand(): String? = lastCommand.get()

    fun getLastResult(): String? = lastResult.get()

    fun addListener(listener: () -> Unit) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: () -> Unit) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    private fun notifyListeners() {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it.invoke() }
    }
}
