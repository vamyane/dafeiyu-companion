package com.dafeiyu.companion.app.runtime

import com.dafeiyu.companion.model.ChatMessage
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

data class IncomingMessage(
    val message: ChatMessage,
    val isReminder: Boolean
)

object AppRuntime {

    @Volatile
    var isForeground: Boolean = false

    private val _incoming = MutableSharedFlow<IncomingMessage>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val incoming: SharedFlow<IncomingMessage> = _incoming

    fun emit(message: IncomingMessage) {
        _incoming.tryEmit(message)
    }
}
