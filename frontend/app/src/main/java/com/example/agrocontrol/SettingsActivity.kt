package com.example.agrocontrol

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.agrocontrol.auth.TokenManager
import com.example.agrocontrol.databinding.ActivitySettingsBinding
import com.example.agrocontrol.model.ChangePasswordRequest
import com.example.agrocontrol.network.ApiErrorParser
import com.example.agrocontrol.network.RetrofitClient
import com.example.agrocontrol.utils.PasswordValidator
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var tokenManager: TokenManager

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        tokenManager = TokenManager(this)

        binding.etBaseUrl.setText(AppPrefs.getBaseUrl(this))
        when (AppPrefs.getThemeMode(this)) {
            "light" -> binding.rbLight.isChecked = true
            "dark" -> binding.rbDark.isChecked = true
            else -> binding.rbSystem.isChecked = true
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnTestConnection.setOnClickListener { testServerConnection() }
        binding.btnChangePassword.setOnClickListener { changePassword() }
        binding.btnSave.setOnClickListener { saveSettings() }
        updateAccountSection()
    }

    override fun onResume() {
        super.onResume()
        if (::tokenManager.isInitialized) updateAccountSection()
    }

    private fun saveSettings() {
        val rawUrl = binding.etBaseUrl.text?.toString().orEmpty().trim()
        if (rawUrl.isBlank()) {
            binding.tilBaseUrl.error = "Введите адрес сервера"
            return
        }
        val normalizedUrl = AppPrefs.normalizeBaseUrl(rawUrl)
        if (!AppPrefs.isValidBaseUrl(rawUrl)) {
            binding.tilBaseUrl.error = "Введите HTTP-адрес сервера и корректный порт"
            return
        }
        binding.tilBaseUrl.error = null
        binding.etBaseUrl.setText(normalizedUrl)
        AppPrefs.setBaseUrl(this, normalizedUrl)

        val mode = when {
            binding.rbLight.isChecked -> "light"
            binding.rbDark.isChecked -> "dark"
            else -> "system"
        }
        AppPrefs.setThemeMode(this, mode)
        AppPrefs.applyTheme(this)
        Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun testServerConnection() {
        val rawUrl = binding.etBaseUrl.text?.toString().orEmpty().trim()
        if (!AppPrefs.isValidBaseUrl(rawUrl)) {
            binding.tilBaseUrl.error = "Введите HTTP-адрес сервера и корректный порт"
            return
        }
        if (rawUrl.isBlank()) {
            binding.tilBaseUrl.error = "Введите адрес сервера"
            return
        }
        val normalizedUrl = AppPrefs.normalizeBaseUrl(rawUrl)
        binding.tilBaseUrl.error = null
        binding.etBaseUrl.setText(normalizedUrl)
        AppPrefs.setBaseUrl(this, normalizedUrl)
        binding.btnTestConnection.isEnabled = false
        binding.progressConnection.visibility = View.VISIBLE
        binding.tvConnectionResult.text = "Проверяем соединение…"

        lifecycleScope.launch {
            try {
                val startTime = System.currentTimeMillis()
                val response = RetrofitClient.noAuthApi(this@SettingsActivity).health()
                val ping = System.currentTimeMillis() - startTime
                if (response.isSuccessful && response.body() != null) {
                    val services = response.body()?.get("services") as? Map<*, *>
                    fun serviceLabel(value: Any?): String {
                        val raw = value?.toString().orEmpty()
                        return when {
                            raw == "connected" -> "подключена"
                            raw == "available" || raw.startsWith("available:") -> "доступна"
                            raw == "unavailable" -> "недоступна"
                            raw.contains("not found") -> "модель не установлена"
                            else -> "нет данных"
                        }
                    }
                    val aiModel = serviceLabel(services?.get("ai_model"))
                    val database = serviceLabel(services?.get("database"))
                    val ollama = serviceLabel(services?.get("ollama"))
                    binding.tvConnectionResult.text =
                        "Соединение установлено (${ping} мс)\nМодель: $aiModel\nБаза данных: $database\nРекомендации: $ollama"
                } else {
                    binding.tvConnectionResult.text = ApiErrorParser.message(
                        response,
                        "Сервер ответил с ошибкой HTTP ${response.code()}"
                    )
                }
            } catch (_: Exception) {
                binding.tvConnectionResult.text =
                    "Не удалось подключиться. Проверьте адрес сервера и подключение к Wi-Fi."
            } finally {
                binding.btnTestConnection.isEnabled = true
                binding.progressConnection.visibility = View.GONE
            }
        }
    }

    private fun updateAccountSection() {
        val user = tokenManager.getUserInfo()
        binding.accountSection.visibility = if (tokenManager.isLoggedIn()) View.VISIBLE else View.GONE
        binding.tvAccountName.text = user?.username?.let { "Аккаунт: $it" } ?: "Аккаунт"
    }

    private fun changePassword() {
        val current = binding.etCurrentPassword.text?.toString().orEmpty()
        val newPassword = binding.etNewPassword.text?.toString().orEmpty()
        val confirmation = binding.etConfirmNewPassword.text?.toString().orEmpty()
        binding.tilCurrentPassword.error = null
        binding.tilNewPassword.error = null
        binding.tilConfirmNewPassword.error = null
        binding.tvPasswordResult.text = ""

        if (current.isBlank()) {
            binding.tilCurrentPassword.error = "Введите текущий пароль"
            return
        }
        val validation = PasswordValidator.validatePassword(newPassword)
        if (!validation.isValid) {
            binding.tilNewPassword.error = validation.unmetRequirements.joinToString("; ")
            return
        }
        if (newPassword != confirmation) {
            binding.tilConfirmNewPassword.error = "Пароли не совпадают"
            return
        }
        val accessToken = tokenManager.getAccessToken()
        if (accessToken.isNullOrBlank()) {
            binding.tvPasswordResult.text = "Сессия завершена. Войдите снова."
            updateAccountSection()
            return
        }

        binding.btnChangePassword.isEnabled = false
        binding.tvPasswordResult.text = "Меняем пароль…"
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.createAuthService(this@SettingsActivity).changePassword(
                    "Bearer $accessToken",
                    ChangePasswordRequest(current, newPassword, confirmation)
                )
                if (response.isSuccessful) {
                    binding.etCurrentPassword.text?.clear()
                    binding.etNewPassword.text?.clear()
                    binding.etConfirmNewPassword.text?.clear()
                    binding.tvPasswordResult.text = "Пароль успешно изменён"
                } else {
                    binding.tvPasswordResult.text = ApiErrorParser.message(
                        response,
                        "Не удалось изменить пароль (HTTP ${response.code()})"
                    )
                }
            } catch (_: Exception) {
                binding.tvPasswordResult.text =
                    "Нет связи с сервером. Проверьте подключение и повторите попытку."
            } finally {
                binding.btnChangePassword.isEnabled = true
            }
        }
    }
}
