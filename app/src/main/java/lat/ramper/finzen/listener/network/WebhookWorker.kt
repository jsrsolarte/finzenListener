package lat.ramper.finzen.listener.network

import android.content.Context
import android.util.Log
import androidx.work.*
import lat.ramper.finzen.listener.BuildConfig
import lat.ramper.finzen.listener.data.AppDatabase
import lat.ramper.finzen.listener.data.NotificationEntry
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class WebhookWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val api: WebhookApi by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(BuildConfig.WEBHOOK_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WebhookApi::class.java)
    }

    override suspend fun doWork(): Result {
        val db = AppDatabase.getDatabase(applicationContext)
        val unsent = db.notificationDao().getUnsentNotifications()

        if (unsent.isEmpty()) return Result.success()

        var allSuccess = true
        for (notification in unsent) {
            try {
                val response = api.sendNotification(notification)
                if (response.isSuccessful) {
                    db.notificationDao().markAsSent(notification.id)
                } else {
                    allSuccess = false
                }
            } catch (e: Exception) {
                Log.e("WebhookWorker", "Error sending notification ${notification.id}", e)
                allSuccess = false
            }
        }

        return if (allSuccess) {
            Result.success()
        } else {
            // WorkManager se encarga del reintento exponencial según la configuración de la solicitud
            Result.retry()
        }
    }
}
