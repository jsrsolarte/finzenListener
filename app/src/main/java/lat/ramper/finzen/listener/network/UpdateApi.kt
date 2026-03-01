package lat.ramper.finzen.listener.network

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val releaseNotes: String? = null
)

interface UpdateApi {
    @GET
    suspend fun checkUpdate(@Url url: String): Response<UpdateInfo>
}
