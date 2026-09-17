package com.example.agrocontrol.network

import android.content.Context
import com.example.agrocontrol.auth.AuthInterceptor
import com.example.agrocontrol.auth.TokenManager
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    @Volatile
    private var retrofit: Retrofit? = null

    @Volatile
    private var authRetrofit: Retrofit? = null

    @Volatile
    private var apiBaseUrl: String? = null

    @Volatile
    private var authBaseUrl: String? = null

    @Volatile
    private var noAuthRetrofit: Retrofit? = null

    @Volatile
    private var noAuthBaseUrl: String? = null

    fun api(context: Context): ApiService {
        val baseUrl = com.example.agrocontrol.AppPrefs.getBaseUrl(context)
        val cached = retrofit
        if (cached != null && apiBaseUrl == baseUrl) {
            return cached.create(ApiService::class.java)
        }

        synchronized(this) {
            val cached2 = retrofit
            if (cached2 != null && apiBaseUrl == baseUrl) {
                return cached2.create(ApiService::class.java)
            }

            apiBaseUrl = baseUrl
            val tokenManager = TokenManager(context)
            val okHttpClient = OkHttpClient.Builder()
                .addInterceptor(AuthInterceptor(context, tokenManager))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()

            retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            return retrofit!!.create(ApiService::class.java)
        }
    }

    fun noAuthApi(context: Context): ApiService {
        val baseUrl = com.example.agrocontrol.AppPrefs.getBaseUrl(context)
        val cached = noAuthRetrofit
        if (cached != null && noAuthBaseUrl == baseUrl) {
            return cached.create(ApiService::class.java)
        }

        synchronized(this) {
            val cached2 = noAuthRetrofit
            if (cached2 != null && noAuthBaseUrl == baseUrl) {
                return cached2.create(ApiService::class.java)
            }

            noAuthBaseUrl = baseUrl
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .writeTimeout(2, TimeUnit.SECONDS)
                .build()

            noAuthRetrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            return noAuthRetrofit!!.create(ApiService::class.java)
        }
    }

    fun createAuthService(context: Context): AuthService {
        val baseUrl = com.example.agrocontrol.AppPrefs.getBaseUrl(context)
        val cached = authRetrofit
        if (cached != null && authBaseUrl == baseUrl) {
            return cached.create(AuthService::class.java)
        }

        synchronized(this) {
            val cached2 = authRetrofit
            if (cached2 != null && authBaseUrl == baseUrl) {
                return cached2.create(AuthService::class.java)
            }

            authBaseUrl = baseUrl
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

            authRetrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            return authRetrofit!!.create(AuthService::class.java)
        }
    }
}
