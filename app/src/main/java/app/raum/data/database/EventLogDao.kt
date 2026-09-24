package app.raum.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EventLogDao {
    @Insert
    suspend fun insert(entries: List<EventLogEntity>)

    /** Gefilterte Abfrage (LOG-005). null-Parameter = kein Filter. */
    @Query(
        """
        SELECT * FROM event_log
        WHERE (:category IS NULL OR category = :category)
          AND (:deviceId IS NULL OR deviceId = :deviceId)
          AND (:automationId IS NULL OR automationId = :automationId)
          AND timestampMs >= :sinceMs
        ORDER BY timestampMs DESC, id DESC
        LIMIT :limit
        """
    )
    fun observe(category: String?, deviceId: String?, automationId: String?, sinceMs: Long, limit: Int): Flow<List<EventLogEntity>>

    /** Letzter Protokolleintrag je Automation – für „zuletzt ausgeführt“ in der Liste. */
    @Query(
        """
        SELECT * FROM event_log WHERE id IN (
            SELECT MAX(id) FROM event_log WHERE automationId IS NOT NULL GROUP BY automationId
        )
        """
    )
    fun observeLatestPerAutomation(): Flow<List<EventLogEntity>>

    @Query("DELETE FROM event_log WHERE timestampMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    /** Hält höchstens [keep] Einträge (die neuesten). */
    @Query("DELETE FROM event_log WHERE id NOT IN (SELECT id FROM event_log ORDER BY id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int): Int

    @Query("DELETE FROM event_log")
    suspend fun clear()
}
