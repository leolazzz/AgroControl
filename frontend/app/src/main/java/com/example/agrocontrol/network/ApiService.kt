package com.example.agrocontrol.network

import com.example.agrocontrol.model.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {
    @POST("control")
    suspend fun sendControl(@Body data: ControlData): Response<ApiResponse>

    @GET("diagnostics")
    suspend fun getDiagnostics(): Response<DiagnosticsData>

    @POST("recommendations")
    suspend fun getRecommendations(@Body req: RecommendationsRequest): Response<RecResponse>

    @Multipart
    @POST("upload_photo")
    suspend fun uploadPhoto(
        @Part file: MultipartBody.Part,
        @Part("stage") stage: RequestBody,
        @Part("plant") plant: RequestBody,
        @Part("include_sensors") includeSensors: RequestBody
    ): Response<UploadPhotoResponse>

    @POST("api/sync/commands")
    suspend fun syncCommands(@Body request: CommandSyncRequest): Response<Map<String, List<*>>>

    @GET("api/health")
    suspend fun health(): Response<Map<String, Any>>
}
