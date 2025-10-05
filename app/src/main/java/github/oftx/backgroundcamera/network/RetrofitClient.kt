package github.oftx.backgroundcamera.network

import android.content.Context
import github.oftx.backgroundcamera.MainActivity
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {
    // 缓存Retrofit实例和它所使用的URL
    private var retrofit: Retrofit? = null
    private var lastUsedBaseUrl: String? = null

    // 这是获取ApiService的唯一方法
    fun getApiService(context: Context): ApiService {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val currentBaseUrl = prefs.getString(MainActivity.KEY_SERVER_URL, AppConfig.BASE_URL) ?: AppConfig.BASE_URL

        // 如果URL已更改或实例尚未创建，则构建新的Retrofit实例
        if (retrofit == null || currentBaseUrl != lastUsedBaseUrl) {
            lastUsedBaseUrl = currentBaseUrl

            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            val client = OkHttpClient.Builder()
                .addInterceptor(logging)
                .build()

            retrofit = Retrofit.Builder()
                .baseUrl(currentBaseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
        return retrofit!!.create(ApiService::class.java)
    }
}