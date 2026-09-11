package com.genius.hyperlyrics.online

import android.content.Context
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.genius.hyperlyrics.online.model.SearchSource
import com.genius.hyperlyrics.online.source.ne.NeApi
import com.genius.hyperlyrics.online.source.ne.NeSource
import com.genius.hyperlyrics.online.source.kugou.KugouSource
import com.genius.hyperlyrics.online.source.kuwo.KuwoSource
import com.genius.hyperlyrics.online.source.lrclib.LrcLibSource
import com.genius.hyperlyrics.online.source.qm.QmApi
import com.genius.hyperlyrics.online.source.qm.QmSource
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

object LyricApiProvider {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    private val baseClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val neApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://interface.music.163.com/")
            .client(baseClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(NeApi::class.java)
    }

    private val qmApi by lazy {
        val qmClient = baseClient.newBuilder()
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .addHeader("User-Agent", "okhttp/3.14.9")
                    .addHeader("Referer", "https://y.qq.com/")
                    .build()
                chain.proceed(req)
            }
            .build()

        Retrofit.Builder()
            .baseUrl("https://u.y.qq.com/")
            .client(qmClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(QmApi::class.java)
    }

    val qmSource: SearchSource by lazy { QmSource(qmApi) }
    val kuwoSource: SearchSource by lazy { KuwoSource() }
    val kugouSource: SearchSource by lazy { KugouSource() }
    val lrclibSource: SearchSource by lazy { LrcLibSource() }

    private var _neSource: SearchSource? = null
    
    fun getNeSource(context: Context): SearchSource {
        if (_neSource == null) {
            _neSource = NeSource(neApi, json, context.applicationContext)
        }
        return _neSource!!
    }
}
