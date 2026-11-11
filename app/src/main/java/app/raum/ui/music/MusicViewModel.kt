package app.raum.ui.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.raum.R
import app.raum.domain.usecases.UiMessageBus
import app.raum.i18n.Strings
import app.raum.sonos.RepeatMode
import app.raum.sonos.SonosException
import app.raum.sonos.SonosFavorite
import app.raum.sonos.SonosService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Seite „Musik“: gewählte Gruppe, Befehle und Fehlermeldungen. */
class MusicViewModel(
    val service: SonosService,
    private val messages: UiMessageBus,
    private val strings: Strings,
) : ViewModel() {
    val state = service.state

    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected.asStateFlow()

    /** Gerade gestarteter Favorit – bis die Antwort da ist, zeigt die Kachel einen Ladekreis. */
    private val _starting = MutableStateFlow<String?>(null)
    val starting: StateFlow<String?> = _starting.asStateFlow()

    fun select(groupId: String) { _selected.value = groupId }

    fun playPause(groupId: String) = act { service.playPause(groupId) }
    fun next(groupId: String) = act { service.next(groupId) }
    fun previous(groupId: String) = act { service.previous(groupId) }
    fun seek(groupId: String, seconds: Int) = act { service.seek(groupId, seconds) }
    fun setShuffle(groupId: String, on: Boolean) = act { service.setShuffle(groupId, on) }
    fun setRepeat(groupId: String, mode: RepeatMode) = act { service.setRepeat(groupId, mode) }
    fun setGroupVolume(groupId: String, v: Int) = act { service.setGroupVolume(groupId, v) }
    fun setGroupMute(groupId: String, muted: Boolean) = act { service.setGroupMute(groupId, muted) }
    fun setMemberVolume(playerUuid: String, v: Int) = act { service.setMemberVolume(playerUuid, v) }

    fun join(playerUuid: String, groupId: String) = act { service.join(playerUuid, groupId) }
    fun leave(playerUuid: String) = act { service.leave(playerUuid) }

    fun play(groupId: String, item: SonosFavorite) {
        _starting.value = item.id
        viewModelScope.launch {
            report(service.play(groupId, item))
            _starting.value = null
        }
    }

    private fun act(block: suspend () -> Result<Unit>) {
        viewModelScope.launch { report(block()) }
    }

    private fun report(r: Result<Unit>) {
        r.exceptionOrNull()?.let { e ->
            val refused = (e as? SonosException)?.kind == SonosException.Kind.REFUSED
            messages.error(strings.get(if (refused) R.string.sonos_fail_refused else R.string.sonos_fail))
        }
    }
}
