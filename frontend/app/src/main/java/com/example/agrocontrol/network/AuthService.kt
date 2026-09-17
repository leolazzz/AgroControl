package com.example.agrocontrol.network

import com.example.agrocontrol.model.*
import retrofit2.Response
import retrofit2.http.*

interface AuthService {
    @POST("api/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<LoginResponse>

    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @POST("api/auth/refresh")
    suspend fun refresh(@Header("Authorization") authHeader: String): Response<RefreshTokenResponse>

    @POST("api/auth/logout")
    suspend fun logout(
        @Header("Authorization") authHeader: String,
        @Header("X-Refresh-Token") refreshToken: String
    ): Response<ApiResponse>

    @POST("api/auth/change-password")
    suspend fun changePassword(
        @Header("Authorization") authHeader: String,
        @Body request: ChangePasswordRequest
    ): Response<ApiResponse>
}
