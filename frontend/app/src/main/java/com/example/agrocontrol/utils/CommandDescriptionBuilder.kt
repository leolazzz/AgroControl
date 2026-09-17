package com.example.agrocontrol.utils

import com.example.agrocontrol.R
import org.json.JSONObject

data class CommandDescription(
    val title: String,
    val summary: String,
    val details: String,
    val serverResponseSection: String? = null,
    val iconRes: Int,
    val timestamp: Long
)

class CommandDescriptionBuilder {

    fun buildCompleteDescription(
        commandType: String,
        commandData: JSONObject,
        status: String,
        timestamp: Long,
        serverResponse: JSONObject? = null,
        aiModelUsed: String? = null,
        aiResponseParsed: String? = null,
        aiConfidence: Float? = null,
        serverResponseRaw: String? = null
    ): CommandDescription {
        val type = normalizeType(commandType)
        return CommandDescription(
            title = titleFor(type),
            summary = buildSummary(type, commandData),
            details = buildDetails(
                type,
                commandData,
                status,
                aiModelUsed,
                aiResponseParsed,
                aiConfidence
            ),
            serverResponseSection = buildResponseSection(serverResponse, serverResponseRaw),
            iconRes = iconFor(type),
            timestamp = timestamp
        )
    }

    private fun normalizeType(type: String): String = when (type.uppercase()) {
        "CONTROL_COMMAND", "MULTI_RELAY_CONTROL" -> "RELAY_CONTROL"
        "DIAGNOSTICS_UPDATE" -> "DIAGNOSTICS_FETCH"
        "AI_RECOMMENDATION" -> "RECOMMENDATION_REQUEST"
        else -> type.uppercase()
    }

    private fun titleFor(type: String): String = when (type) {
        "RELAY_CONTROL" -> "Управление оборудованием"
        "DIAGNOSTICS_FETCH" -> "Диагностика теплицы"
        "SENSOR_BASED_RECOMMENDATION" -> "Совет по показаниям"
        "PHOTO_ANALYSIS" -> "Анализ фотографии"
        "RECOMMENDATION_REQUEST" -> "Рекомендации по растению"
        "MODE_CHANGE" -> "Смена режима"
        "RELAY_1_CONTROL" -> "Полив"
        "RELAY_2_CONTROL" -> "Освещение"
        "RELAY_3_CONTROL" -> "Вентиляция"
        "EC_MEASUREMENT" -> "Измерение EC"
        "PH_MEASUREMENT" -> "Измерение pH"
        else -> type.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }
    }

    private fun buildSummary(type: String, data: JSONObject): String = when (type) {
        "RELAY_CONTROL" -> controlSummary(data)
        "DIAGNOSTICS_FETCH" -> "Запрошены свежие показания датчиков"
        "SENSOR_BASED_RECOMMENDATION", "RECOMMENDATION_REQUEST" -> plantSummary(data)
        "PHOTO_ANALYSIS" -> data.text("filename")
            ?.let { "Файл: $it" }
            ?: "Фотография отправлена на проверку"
        "MODE_CHANGE" -> listOfNotNull(
            data.text("previous_mode"),
            data.text("new_mode")
        ).joinToString(" → ").ifBlank { "Режим изменён" }
        "EC_MEASUREMENT", "PH_MEASUREMENT" -> measurementSummary(type, data)
        else -> "Действие сохранено в журнале"
    }

    private fun buildDetails(
        type: String,
        data: JSONObject,
        status: String,
        model: String?,
        aiResponse: String?,
        confidence: Float?
    ): String {
        val lines = mutableListOf("Статус: ${statusText(status)}")

        when (type) {
            "RELAY_CONTROL" -> lines += controlDetails(data)
            "SENSOR_BASED_RECOMMENDATION", "RECOMMENDATION_REQUEST" -> {
                data.text("plant")?.let { lines += "Культура: $it" }
                data.text("stage")?.let { lines += "Стадия: $it" }
                data.text("disease")?.let { lines += "Результат анализа: $it" }
                data.optionalBoolean("includeSensors")?.let {
                    lines += "Показания датчиков: ${if (it) "включены" else "не включены"}"
                }
            }
            "PHOTO_ANALYSIS" -> {
                data.text("filename")?.let { lines += "Файл: $it" }
                data.optLong("fileSize", -1).takeIf { it >= 0 }?.let {
                    lines += "Размер: ${formatFileSize(it)}"
                }
                data.text("stage")?.let { lines += "Стадия: $it" }
                data.optionalBoolean("includeSensors")?.let {
                    lines += "Показания датчиков: ${if (it) "включены" else "не включены"}"
                }
            }
            "MODE_CHANGE" -> {
                data.text("previous_mode")?.let { lines += "Было: $it" }
                data.text("new_mode")?.let { lines += "Стало: $it" }
            }
            "EC_MEASUREMENT", "PH_MEASUREMENT" -> lines += measurementSummary(type, data)
        }

        model?.takeIf { it.isNotBlank() }?.let { lines += "Модель: $it" }
        confidence?.let { lines += "Уверенность: ${(it * 100).toInt()}%" }
        aiResponse?.takeIf { it.isNotBlank() }?.let { lines += "Ответ: ${it.take(600)}" }
        return lines.distinct().joinToString("\n")
    }

    private fun controlSummary(data: JSONObject): String {
        val enabled = listOf(
            "полив" to data.optionalBoolean("relay1"),
            "освещение" to data.optionalBoolean("relay2"),
            "вентиляция" to data.optionalBoolean("relay3")
        ).filter { it.second == true }.map { it.first }

        return when {
            enabled.isNotEmpty() -> "Включено: ${enabled.joinToString(", ")}"
            data.has("relay1") || data.has("relay2") || data.has("relay3") -> "Оборудование выключено"
            else -> "Состояние оборудования изменено"
        }
    }

    private fun controlDetails(data: JSONObject): List<String> {
        val lines = mutableListOf<String>()
        data.text("mode")?.let { lines += "Режим: $it" }
        listOf(
            "Полив" to "relay1",
            "Освещение" to "relay2",
            "Вентиляция" to "relay3",
            "Датчик EC" to "ec_sensor_active",
            "Датчик pH" to "ph_sensor_active"
        ).forEach { (label, key) ->
            data.optionalBoolean(key)?.let { lines += "$label: ${if (it) "вкл" else "выкл"}" }
        }
        return lines
    }

    private fun plantSummary(data: JSONObject): String {
        val plant = data.text("plant") ?: data.text("plant_type")
        return listOfNotNull(plant, data.text("stage")).joinToString(" · ")
            .ifBlank { "Запрошен совет агронома" }
    }

    private fun measurementSummary(type: String, data: JSONObject): String {
        val value = data.optDouble("value", Double.NaN)
        if (value.isNaN()) return "Значение не сохранено"
        val unit = data.text("unit") ?: if (type == "PH_MEASUREMENT") "pH" else "мСм/см"
        return "Значение: $value $unit"
    }

    private fun buildResponseSection(response: JSONObject?, raw: String?): String? {
        if (response == null) {
            return raw?.takeIf { it.isNotBlank() }?.let { "Ответ сервера:\n${it.take(800)}" }
        }

        val lines = mutableListOf<String>()
        response.optInt("status_code", -1).takeIf { it >= 0 }?.let { lines += "HTTP $it" }
        response.optLong("response_time_ms", -1).takeIf { it >= 0 }?.let { lines += "Время ответа: $it мс" }

        response.optJSONObject("data")?.let { data ->
            addValue(lines, "Температура", data, "temperature")
            addValue(lines, "Влажность", data, "humidity")
            addValue(lines, "Освещённость", data, "light")
            addValue(lines, "Уровень раствора", data, "water_level")
            addValue(lines, "EC", data, "ec")
            addValue(lines, "pH", data, "ph")
            data.text("mode")?.let { lines += "Режим: $it" }
        }

        response.optJSONObject("error")?.text("message")?.let { lines += "Ошибка: $it" }
        response.text("recommendations")?.let { lines += "Рекомендация: $it" }
        response.text("recommendation_source")?.let { lines += "Источник: $it" }
        response.text("model_used")?.let { lines += "Модель: $it" }

        return lines.takeIf { it.isNotEmpty() }
            ?.distinct()
            ?.joinToString("\n", prefix = "Ответ сервера:\n")
            ?: raw?.takeIf { it.isNotBlank() }?.let { "Ответ сервера:\n${it.take(800)}" }
    }

    private fun addValue(lines: MutableList<String>, label: String, data: JSONObject, key: String) {
        if (data.has(key) && !data.isNull(key)) lines += "$label: ${data.opt(key)}"
    }

    private fun statusText(status: String): String = when (status) {
        "PENDING" -> "ожидает отправки"
        "SENT_TO_SERVER" -> "отправлено"
        "SERVER_ACKNOWLEDGED" -> "принято сервером"
        "QUEUED_FOR_RETRY" -> "в очереди"
        "FAILED" -> "ошибка"
        "CANCELLED" -> "отменено"
        else -> status.lowercase()
    }

    private fun iconFor(type: String): Int = when (type) {
        "RELAY_CONTROL", "MODE_CHANGE", "RELAY_1_CONTROL", "RELAY_2_CONTROL", "RELAY_3_CONTROL" ->
            R.drawable.ic_history_control
        "DIAGNOSTICS_FETCH", "EC_MEASUREMENT", "PH_MEASUREMENT" -> R.drawable.ic_history_sensor
        "PHOTO_ANALYSIS" -> R.drawable.ic_history_photo
        "SENSOR_BASED_RECOMMENDATION", "RECOMMENDATION_REQUEST" -> R.drawable.ic_history_advice
        else -> R.drawable.ic_history_system
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f МБ".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.0f КБ".format(bytes / 1024.0)
        else -> "$bytes Б"
    }

    private fun JSONObject.text(key: String): String? = optString(key)
        .trim()
        .takeIf { it.isNotEmpty() && it != "null" }

    private fun JSONObject.optionalBoolean(key: String): Boolean? =
        if (has(key) && !isNull(key)) optBoolean(key) else null
}
