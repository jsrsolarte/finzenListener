package lat.ramper.finzen.listener.network

import lat.ramper.finzen.listener.data.NotificationEntry
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface WebhookApi {
    @POST("webhook/register-transaction")
    suspend fun sendNotification(@Body notification: NotificationEntry): Response<Unit>
}
