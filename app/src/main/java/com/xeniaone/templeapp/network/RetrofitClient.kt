package com.xeniaone.templeapp.network

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

   private const val BASE_URL = "https://oneapi.xeniapos.com/"


    fun createAuthApiService(): AuthApiService {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return retrofit.create(AuthApiService::class.java)
    }

    fun createOrderApiService(context: Context? = null): OrderApiService {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val okHttpClientBuilder = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)

        if (context != null) {
            okHttpClientBuilder.addInterceptor { chain ->
                val prefs = context.getSharedPreferences("prasadam_auth_prefs", Context.MODE_PRIVATE)
                val token = prefs.getString("auth_token", "") ?: ""
                val requestBuilder = chain.request().newBuilder()
                if (token.isNotBlank()) {
                    val bearer = if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"
                    requestBuilder.addHeader("Authorization", bearer)
                }
                chain.proceed(requestBuilder.build())
            }
        }

        val okHttpClient = okHttpClientBuilder.build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return retrofit.create(OrderApiService::class.java)
    }
}
