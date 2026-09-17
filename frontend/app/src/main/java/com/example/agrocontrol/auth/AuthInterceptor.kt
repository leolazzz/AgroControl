package com.example.agrocontrol.auth

import android.content.Context
import android.content.Intent
import com.example.agrocontrol.network.RetrofitClient
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class AuthInterceptor(private val context: Context, private val tokenManager: TokenManager) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        var request = originalRequest

        val accessToken = tokenManager.getAccessToken()
        if (accessToken != null) {
            request = request.newBuilder()
                .header("Authorization", "Bearer $accessToken")
                .build()
        }

        val response = chain.proceed(request)

        if (response.code == 401 && accessToken != null) {
            synchronized(this) {
                val currentToken = tokenManager.getAccessToken()
                if (currentToken != null && currentToken != accessToken) {
                    response.close()
                    val newRequest = originalRequest.newBuilder()
                        .header("Authorization", "Bearer $currentToken")
                        .build()
                    return chain.proceed(newRequest)
                }

                val refreshToken = tokenManager.getRefreshToken()
                if (refreshToken != null) {
                    try {
                        val refreshResponse = runBlocking {
                            RetrofitClient.createAuthService(context).refresh("Bearer $refreshToken")
                        }

                        if (refreshResponse.isSuccessful && refreshResponse.body() != null) {
                            response.close()
                            val body = refreshResponse.body()!!
                            tokenManager.saveAccessToken(body.access_token)

                            val newRequest = originalRequest.newBuilder()
                                .header("Authorization", "Bearer ${body.access_token}")
                                .build()
                            return chain.proceed(newRequest)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                tokenManager.clearAll()
                val intent = Intent(context, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra("session_expired", true)
                }
                context.startActivity(intent)
            }
        }

        return response
    }
}
