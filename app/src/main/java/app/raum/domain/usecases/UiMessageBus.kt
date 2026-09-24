package app.raum.domain.usecases

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class UiMessage(val text: String, val isError: Boolean = false)

/** Kurze Rückmeldungen (Snackbars) aus Domainlogik an die Oberfläche. */
class UiMessageBus {
    private val _messages = MutableSharedFlow<UiMessage>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    fun info(text: String) { _messages.tryEmit(UiMessage(text)) }
    fun error(text: String) { _messages.tryEmit(UiMessage(text, isError = true)) }
}
