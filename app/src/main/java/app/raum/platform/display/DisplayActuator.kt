package app.raum.platform.display

import app.raum.domain.usecases.UiMessageBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Setzt Moduswechsel physisch um und weckt das Panel bei Hinweisen (z. B. aus Automationen). */
class DisplayActuator(
    private val display: DisplayController,
    private val screen: ScreenPower,
    private val messages: UiMessageBus,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            var previous = display.mode.value
            display.mode.collect { mode ->
                when {
                    mode == DisplayMode.OFF -> screen.turnOff()
                    previous == DisplayMode.OFF -> screen.turnOn()
                }
                previous = mode
            }
        }
        scope.launch { messages.messages.collect { display.wake() } }
    }
}
