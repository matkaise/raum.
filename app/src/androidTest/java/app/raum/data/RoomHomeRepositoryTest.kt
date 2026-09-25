package app.raum.data

import androidx.room.Room as RoomBuilder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.raum.data.database.InitialHomeData
import app.raum.data.database.RaumDatabase
import app.raum.data.database.RoomHomeRepository
import app.raum.domain.models.DeviceCommand
import app.raum.domain.models.Scene
import app.raum.domain.models.SceneAction
import app.raum.matter.controller.mock.MockHomeSeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomHomeRepositoryTest {

    private lateinit var db: RaumDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repo: RoomHomeRepository

    private val seed = InitialHomeData(MockHomeSeed.rooms, MockHomeSeed.deviceMetadata, MockHomeSeed.scenes, MockHomeSeed.automations)

    @Before
    fun setUp() = runBlocking {
        db = RoomBuilder.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RaumDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        repo = RoomHomeRepository(db, scope)
        repo.initializeIfEmpty("Testzuhause", seed)
        Unit
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private val dao get() = db.homeDao()

    @Test
    fun initializationIsIdempotent() = runBlocking {
        assertFalse(repo.initializeIfEmpty("Nochmal", seed))
        assertEquals("Testzuhause", dao.observeHome().first()!!.name)
        assertEquals(MockHomeSeed.devices.size, dao.observeDevices().first().size)
    }

    @Test
    fun scenesKeepActionOrder() = runBlocking {
        val filmabend = MockHomeSeed.scenes.first { it.name == "Filmabend" }
        assertEquals(filmabend.actions, repo.scene(filmabend.id)!!.actions)
    }

    @Test
    fun upsertSceneReplacesActionsAtomically() = runBlocking {
        val lamp = MockHomeSeed.devices.first().id
        val scene = Scene(UUID.randomUUID(), "Lesen", "reading", listOf(SceneAction(lamp, DeviceCommand.SetBrightness(60))))
        repo.upsertScene(scene)
        repo.upsertScene(scene.copy(name = "Lesen 2", actions = listOf(SceneAction(lamp, DeviceCommand.SetOn(false)))))
        val stored = repo.scene(scene.id)!!
        assertEquals("Lesen 2", stored.name)
        assertEquals(listOf(SceneAction(lamp, DeviceCommand.SetOn(false))), stored.actions)
    }

    @Test
    fun actionsForUnknownDevicesAreDropped() = runBlocking {
        val scene = Scene(UUID.randomUUID(), "X", "home", listOf(SceneAction(UUID.randomUUID(), DeviceCommand.SetOn(true))))
        repo.upsertScene(scene)
        assertTrue(repo.scene(scene.id)!!.actions.isEmpty())
    }

    @Test
    fun removingDeviceRemovesItsSceneActions() = runBlocking {
        val lamp = MockHomeSeed.devices.first { it.name == "Deckenleuchte" }
        repo.removeDevice(lamp.nodeId)
        val actions = dao.observeScenes().first().flatMap { it.actions }
        assertTrue(actions.none { it.deviceId == lamp.id.toString() })
        assertNull(dao.deviceByChannel(lamp.nodeId.toLong(), null))
    }

    @Test
    fun deletingRoomKeepsDevicesUnassigned() = runBlocking {
        val room = MockHomeSeed.rooms.first()
        val before = dao.observeDevices().first().count { it.roomId == room.id.toString() }
        assertTrue(before > 0)
        repo.deleteRoom(room.id)
        val devices = dao.observeDevices().first()
        assertEquals(MockHomeSeed.devices.size, devices.size)
        assertTrue(devices.none { it.roomId == room.id.toString() })
    }

    @Test
    fun upsertByNodeKeepsExistingDeviceId() = runBlocking {
        val lamp = MockHomeSeed.deviceMetadata.first()
        repo.upsertDevice(lamp.copy(id = UUID.randomUUID(), displayName = "Umbenannt"))
        val stored = dao.deviceByChannel(lamp.matterNodeId.toLong(), null)!!
        assertEquals(lamp.id.toString(), stored.id)
        assertEquals("Umbenannt", stored.displayName)
    }

    @Test
    fun deletingSceneRemovesItsActions() = runBlocking {
        val scene = MockHomeSeed.scenes.first()
        repo.deleteScene(scene.id)
        assertNull(repo.scene(scene.id))
        assertTrue(dao.observeScenes().first().none { it.scene.id == scene.id.toString() })
    }

    @Test
    fun automationsRoundTripAndDelete() = runBlocking {
        val seeded = repo.automations.first { it.isNotEmpty() }
        assertEquals(MockHomeSeed.automations.toSet(), seeded.toSet())
        val a = seeded.first()
        repo.upsertAutomation(a.copy(name = "Neu", enabled = false))
        assertEquals("Neu", repo.automation(a.id)!!.name)
        repo.deleteAutomation(a.id)
        assertNull(repo.automation(a.id))
    }

    @Test
    fun eventLogPersistsFiltersAndRotates() = runBlocking {
        val clock = java.time.Clock.systemUTC()
        val store = app.raum.data.database.PersistentEventLog(db.eventLogDao(), scope, clock, maxEntries = 50)
        val log = app.raum.diagnostics.EventLog(sink = store)
        val aId = UUID.randomUUID()
        repeat(260) { i ->
            log.record(
                if (i % 2 == 0) app.raum.diagnostics.LogCategory.DEVICE else app.raum.diagnostics.LogCategory.AUTOMATION,
                app.raum.diagnostics.LogLevel.INFO, "Eintrag $i", automationId = if (i % 2 == 1) aId else null,
            )
        }
        // Schreiben ist asynchron – warten, bis der letzte Eintrag in der DB ist.
        kotlinx.coroutines.withTimeout(5_000) {
            store.observe(app.raum.data.database.LogFilter()).first { list -> list.any { it.message == "Eintrag 259" } }
        }
        val onlyAutomation = store.observe(app.raum.data.database.LogFilter(automationId = aId)).first()
        assertTrue(onlyAutomation.all { it.automationId == aId })
        assertEquals("Eintrag 259", store.observeLatestPerAutomation().first()[aId]!!.message)
        // Rotation greift alle 200 Einträge → danach höchstens 50 + Rest des letzten Stapels
        assertTrue(store.observe(app.raum.data.database.LogFilter(limit = 1000)).first().size < 260)
    }

    @Test
    fun valuesSurviveReopen() = runBlocking {
        // Simuliert einen App-Neustart mit derselben Datei-Datenbank
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase("reopen-test.db")
        val file1 = RoomBuilder.databaseBuilder(ctx, RaumDatabase::class.java, "reopen-test.db").build()
        RoomHomeRepository(file1, scope).apply {
            initializeIfEmpty("Persistent", seed)
            setFavorite(MockHomeSeed.devices.last().id, true)
        }
        file1.close()
        val file2 = RoomBuilder.databaseBuilder(ctx, RaumDatabase::class.java, "reopen-test.db").build()
        val devices = file2.homeDao().observeDevices().first()
        assertTrue(devices.first { it.id == MockHomeSeed.devices.last().id.toString() }.favorite)
        assertEquals("Persistent", file2.homeDao().observeHome().first()!!.name)
        file2.close()
        ctx.deleteDatabase("reopen-test.db")
        Unit
    }
}
