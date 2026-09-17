package com.example.agrocontrol.network

import org.json.JSONObject
import retrofit2.Response

object ApiErrorParser {
    fun message(response: Response<*>, fallback: String): String {
        val raw = try {
            response.errorBody()?.string().orEmpty()
        } catch (_: Exception) {
            ""
        }
        if (raw.isBlank()) return fallback
        return try {
            val json = JSONObject(raw)
            json.optString("message").ifBlank {
                when (json.optString("error")) {
                    "username_exists" -> "Этот логин уже занят"
                    "email_exists" -> "Аккаунт с таким email уже существует"
                    "invalid_credentials" -> "Неверный логин или пароль"
                    else -> fallback
                }
            }
        } catch (_: Exception) {
            fallback
        }
    }
}
