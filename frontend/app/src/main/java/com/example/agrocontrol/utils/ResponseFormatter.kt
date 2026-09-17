package com.example.agrocontrol.utils

import com.example.agrocontrol.model.DiagnosticsData
import com.example.agrocontrol.model.RecResponse
import org.json.JSONArray
import org.json.JSONObject

object ResponseFormatter {

    fun formatDiagnosticsResponse(
        diagnosticsData: DiagnosticsData,
        responseTimeMs: Long,
        statusCode: Int
    ): JSONObject {
        return JSONObject().apply {
            put("response_type", "diagnostics")
            put("timestamp", System.currentTimeMillis())
            put("response_time_ms", responseTimeMs)
            put("status_code", statusCode)
            put("data", JSONObject().apply {
                put("temperature", diagnosticsData.air_temp)
                put("humidity", diagnosticsData.humidity)
                put("ph", diagnosticsData.ph)
                put("ec", diagnosticsData.ec)
                put("light", diagnosticsData.light)
                put("water_level", diagnosticsData.level)
                put("mode", diagnosticsData.mode ?: "неизвестно")
            })
            put("warnings", JSONArray())
            put("errors", JSONArray())
        }
    }

    fun formatAIRecommendationResponse(
        recResponse: RecResponse,
        processingTimeMs: Long,
        inputType: String = "sensor_based",
        confidence: Double? = null,
        modelUsed: String = "Локальная агрономическая база знаний"
    ): JSONObject {
        return JSONObject().apply {
            put("response_type", "ai_recommendations")
            put("timestamp", System.currentTimeMillis())
            put("processing_time_ms", processingTimeMs)
            put("model_used", modelUsed)
            put("input_type", inputType)
            if (confidence != null) put("confidence_score", confidence)
            put("recommendations", recResponse.recommendations)
            put("recommendation_source", recResponse.recommendation_source ?: "не указан")
            put("language_validated", recResponse.language_validated)
            put("grounded", recResponse.retrieval?.grounded ?: false)
            put("matched_documents", recResponse.retrieval?.matched_documents ?: 0)
            put("warnings", JSONArray())
        }
    }

    fun formatErrorResponse(
        errorMessage: String,
        errorType: String = "network_error",
        responseTimeMs: Long,
        statusCode: Int? = null
    ): JSONObject {
        return JSONObject().apply {
            put("response_type", "error")
            put("timestamp", System.currentTimeMillis())
            put("response_time_ms", responseTimeMs)
            put("status_code", statusCode)
            put("error", JSONObject().apply {
                put("type", errorType)
                put("message", errorMessage)
                put("code", statusCode ?: "ERR_UNKNOWN")
            })
            put("troubleshooting_steps", JSONArray().apply {
                put("Проверьте питание сервера и подключение к сети")
                put("Проверьте адрес сервера в настройках приложения")
                put("Проверьте настройки брандмауэра и маршрутизатора")
                put("При необходимости перезапустите контроллер HotBed")
            })
        }
    }

}
