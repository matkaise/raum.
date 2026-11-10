package app.raum.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SensorSampleDao {
    @Insert
    suspend fun insert(samples: List<SensorSampleEntity>)

    @Query("SELECT * FROM sensor_samples WHERE deviceId = :deviceId AND metric = :metric AND timestampMs >= :fromMs AND timestampMs <= :toMs ORDER BY timestampMs")
    suspend fun range(deviceId: String, metric: String, fromMs: Long, toMs: Long): List<SensorSampleEntity>

    /** Letzter Wert vor [beforeMs] – Zustand am Anfang eines Zeitfensters. */
    @Query("SELECT * FROM sensor_samples WHERE deviceId = :deviceId AND metric = :metric AND timestampMs < :beforeMs ORDER BY timestampMs DESC LIMIT 1")
    suspend fun lastBefore(deviceId: String, metric: String, beforeMs: Long): SensorSampleEntity?

    @Query("DELETE FROM sensor_samples WHERE timestampMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    @Query("DELETE FROM sensor_samples WHERE deviceId NOT IN (SELECT id FROM devices)")
    suspend fun deleteOrphans(): Int

    @Query("DELETE FROM sensor_samples")
    suspend fun clear()
}
