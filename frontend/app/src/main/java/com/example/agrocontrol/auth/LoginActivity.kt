package com.example.agrocontrol.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.agrocontrol.MainActivity
import com.example.agrocontrol.AppPrefs
import com.example.agrocontrol.SettingsActivity
import com.example.agrocontrol.databinding.ActivityLoginBinding
import com.example.agrocontrol.network.RetrofitClient
import com.example.agrocontrol.network.AuthService
import com.example.agrocontrol.network.ApiErrorParser
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var tokenManager: TokenManager
    private lateinit var authService: AuthService

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tokenManager = TokenManager(this)
        authService = RetrofitClient.createAuthService(this)

        if (tokenManager.isLoggedIn()) {
            navigateToMain()
            return
        }

        binding.btnLogin.setOnClickListener {
            login()
        }

        binding.tvRegisterLink.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        binding.tvServerSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        if (intent.getBooleanExtra("session_expired", false)) {
            Toast.makeText(this, "Сессия истекла. Пожалуйста, войдите снова.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        authService = RetrofitClient.createAuthService(this)
    }

    private fun login() {
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()

        binding.tilUsername.error = null
        binding.tilPassword.error = null
        binding.tvError.visibility = android.view.View.GONE

        if (username.isEmpty()) {
            binding.tilUsername.error = "Введите логин или email"
        }
        if (password.isEmpty()) {
            binding.tilPassword.error = "Введите пароль"
        }
        if (username.isEmpty() || password.isEmpty()) {
            return
        }

        binding.btnLogin.isEnabled = false
        binding.progressBar.visibility = android.view.View.VISIBLE

        lifecycleScope.launch {
            try {
                val response = authService.login(
                    com.example.agrocontrol.model.LoginRequest(username, password)
                )

                if (response.isSuccessful && response.body() != null) {
                    val loginResponse = response.body()!!
                    tokenManager.saveAccessToken(loginResponse.access_token)
                    tokenManager.saveRefreshToken(loginResponse.refresh_token)
                    tokenManager.saveUserInfo(
                        loginResponse.user.id,
                        loginResponse.user.username,
                        loginResponse.user.role
                    )

                    navigateToMain()
                } else {
                    showError(ApiErrorParser.message(response, "Не удалось войти. Проверьте данные."))
                }
            } catch (e: Exception) {
                showError("Сервер недоступен. Проверьте адрес в настройках и подключение к сети.")
            } finally {
                binding.btnLogin.isEnabled = true
                binding.progressBar.visibility = android.view.View.GONE
            }
        }
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = android.view.View.VISIBLE
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
