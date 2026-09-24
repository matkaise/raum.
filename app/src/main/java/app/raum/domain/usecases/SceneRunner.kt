package app.raum.domain.usecases

import app.raum.i18n.Strings
import app.raum.R
import app.raum.diagnostics.EventLog
import app.raum.diagnostics.LogCategory
import app.raum.domain.models.Scene
import app.raum.matter.controller.CommandResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class SceneRunResult(val scene: Scene, val succeeded: Int, val failedDevices: List<String>) {
    val isComplete: Boolean get() = failedDevices.isEmpty()
}

/**
 * Führt eine Szene aus (SCN-003). Aktionen pro Gerät laufen der Reihe nach
 * (Reihenfolge ist relevant, z. B. Farbe vor Helligkeit), Geräte untereinander parallel.
 * Teilfehler werden gesammelt gemeldet (SCN-006).
 */
class SceneRunner(
    private val deviceService: DeviceService,
    private val messages: UiMessageBus,
    private val log: EventLog,
    private val strings: Strings,
) {
    /** @param notify false = keine Snackbar (z. B. aus Automationen); protokolliert wird immer. */
    suspend fun run(scene: Scene, notify: Boolean = true): SceneRunResult = coroutineScope {
        val byDevice = scene.actions.groupBy { it.deviceId }
        val results = byDevice.map { (deviceId, actions) ->
            async {
                val name = deviceService.device(deviceId)?.displayName ?: strings.get(R.string.device_unknown)
                var ok = true
                for (action in actions) {
                    val device = deviceService.device(deviceId)
                    val result = if (device == null) null else deviceService.send(device, action.command, notify = false)
                    if (result !is CommandResult.Success) { ok = false; break }
                }
                name to ok
            }
        }.awaitAll()

        val failed = results.filterNot { it.second }.map { it.first }
        val result = SceneRunResult(scene, results.size - failed.size, failed)
        if (result.isComplete) {
            log.info(LogCategory.SCENE, strings.get(R.string.log_scene_done, scene.name, result.succeeded))
            if (notify) messages.info(strings.get(R.string.msg_scene_done, scene.name))
        } else {
            log.error(LogCategory.SCENE, strings.get(R.string.log_scene_partial, scene.name, failed.joinToString()))
            if (notify) messages.error(strings.get(R.string.msg_scene_partial, scene.name, failed.size, failed.joinToString()))
        }
        result
    }
}
