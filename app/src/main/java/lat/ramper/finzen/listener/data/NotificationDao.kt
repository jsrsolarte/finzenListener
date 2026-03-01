package lat.ramper.finzen.listener.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {
    @Insert
    suspend fun insert(notification: NotificationEntry)

    @Query("SELECT * FROM notifications ORDER BY timestamp DESC")
    fun getAllNotifications(): Flow<List<NotificationEntry>>

    @Query("UPDATE notifications SET isSent = 1 WHERE id = :id")
    suspend fun markAsSent(id: Long)

    @Query("UPDATE notifications SET isSent = 0 WHERE id = :id")
    suspend fun markAsUnsent(id: Long)

    @Query("SELECT * FROM notifications WHERE isSent = 0")
    suspend fun getUnsentNotifications(): List<NotificationEntry>

    @Query("DELETE FROM notifications")
    suspend fun deleteAll()

    @Query("DELETE FROM notifications WHERE id = :id")
    suspend fun deleteById(id: Long)
}
