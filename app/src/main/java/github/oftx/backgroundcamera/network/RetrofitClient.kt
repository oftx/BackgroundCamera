package github.oftx.backgroundcamera.network

import android.content.Context
import com.google.gson.GsonBuilder
import github.oftx.backgroundcamera.MainActivity
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.Instant

object RetrofitClient {
    private var retrofit: Retrofit? = null
    private var lastUsedBaseUrl: String? = null

    fun getApiService(context: Context): ApiService {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val currentBaseUrl = prefs.getString(MainActivity.KEY_SERVER_URL, AppConfig.BASE_URL) ?: AppConfig.BASE_URL

        if (retrofit == null || currentBaseUrl != lastUsedBaseUrl) {
            lastUsedBaseUrl = currentBaseUrl

            // 【核心修改】创建自定义的Gson实例
            val gson = GsonBuilder()
                .registerTypeAdapter(Instant::class.java, InstantTypeAdapter())
                .create()

            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            val client = OkHttpClient.Builder()
                .addInterceptor(logging)
                .build()

            // 【核心修改】使用自定义的Gson实例来构建Retrofit
            retrofit = Retrofit.Builder()
                .baseUrl(currentBaseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
        }
        return retrofit!!.create(ApiService::class.java)
    }
}