package lat.ramper.finzen.listener.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.*

@Entity(tableName = "notifications")
data class NotificationEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val title: String?,
    val text: String?,
    val timestamp: Long,
    val userId: String,
    val isSent: Boolean = false,
    val formattedDateTime: String = ""
)
