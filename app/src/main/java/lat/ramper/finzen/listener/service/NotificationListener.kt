package lat.ramper.finzen.listener.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.work.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import lat.ramper.finzen.listener.data.AppDatabase
import lat.ramper.finzen.listener.data.NotificationEntry
import lat.ramper.finzen.listener.network.WebhookWorker
import java.text.SimpleDateFormat
import java.util.*

class NotificationListener : NotificationListenerService() {

    companion object {
        const val ACTION_PROCESS_ACTIVE = "lat.ramper.finzen.listener.PROCESS_ACTIVE"

        fun getServiceComponentName(context: Context): ComponentName {
            return ComponentName(context, NotificationListener::class.java)
        }
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PROCESS_ACTIVE) {
            processActiveNotifications()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        processSbn(sbn)
    }

    private fun processActiveNotifications() {
        try {
            activeNotifications?.forEach { sbn ->
                processSbn(sbn)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun processSbn(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getString("android.title")
        val text = extras.getCharSequence("android.text")?.toString()
        val timestamp = sbn.postTime

        val sharedPrefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val userId = sharedPrefs.getString("user_id", "") ?: ""
        val selectedApps = sharedPrefs.getStringSet("selected_apps", emptySet()) ?: emptySet()

        if (userId.isNotEmpty() && selectedApps.contains(packageName)) {
            // Verificar filtros de palabras clave
            val appKeywords = sharedPrefs.getString("keywords_$packageName", "") ?: ""
            val keywordsList = appKeywords.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val contentToSearch = "${title ?: ""} ${text ?: ""}".lowercase()
            val matchesFilter = if (keywordsList.isEmpty()) {
                true
            } else {
                keywordsList.any { contentToSearch.contains(it.lowercase()) }
            }

            if (matchesFilter) {
                val appName = try {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(packageName, 0)
                    ).toString()
                } catch (e: Exception) {
                    packageName
                }

                val formattedDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

                val entry = NotificationEntry(
                    packageName = packageName,
                    appName = appName,
                    title = title,
                    text = text,
                    timestamp = timestamp,
                    userId = userId,
                    formattedDateTime = formattedDate
                )

                scope.launch {
                    val db = AppDatabase.getDatabase(applicationContext)
                    db.notificationDao().insert(entry)
                    enqueueWebhookWork()
                }
            }
        }
    }

    private fun enqueueWebhookWork() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<WebhookWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                java.util.concurrent.TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "webhook_sync",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}
