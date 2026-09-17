package com.example.agrocontrol.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class TokenManager(private val context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "auth_tokens",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveAccessToken(token: String) {
        encryptedPrefs.edit().putString(KEY_ACCESS_TOKEN, token).apply()
    }

    fun getAccessToken(): String? {
        return encryptedPrefs.getString(KEY_ACCESS_TOKEN, null)
    }

    fun saveRefreshToken(token: String) {
        encryptedPrefs.edit().putString(KEY_REFRESH_TOKEN, token).apply()
    }

    fun getRefreshToken(): String? {
        return encryptedPrefs.getString(KEY_REFRESH_TOKEN, null)
    }

    fun saveUserInfo(userId: Int, username: String, role: Int) {
        encryptedPrefs.edit()
            .putInt(KEY_USER_ID, userId)
            .putString(KEY_USERNAME, username)
            .putInt(KEY_ROLE, role)
            .apply()
    }

    fun getUserInfo(): UserInfo? {
        val userId = encryptedPrefs.getInt(KEY_USER_ID, -1)
        val username = encryptedPrefs.getString(KEY_USERNAME, null)
        val role = encryptedPrefs.getInt(KEY_ROLE, -1)
        return if (userId != -1 && username != null && role != -1) {
            UserInfo(userId, username, role)
        } else null
    }

    fun clearAll() {
        encryptedPrefs.edit().clear().apply()
    }

    fun isLoggedIn(): Boolean {
        return getAccessToken() != null && getRefreshToken() != null
    }

    data class UserInfo(val userId: Int, val username: String, val role: Int)

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_ROLE = "role"
    }
}
