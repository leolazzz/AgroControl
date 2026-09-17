package com.example.agrocontrol.auth

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import android.util.Patterns
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.agrocontrol.databinding.ActivityRegisterBinding
import com.example.agrocontrol.AppPrefs
import com.example.agrocontrol.MainActivity
import com.example.agrocontrol.SettingsActivity
import com.example.agrocontrol.network.RetrofitClient
import com.example.agrocontrol.network.AuthService
import com.example.agrocontrol.network.ApiErrorParser
import com.example.agrocontrol.utils.PasswordValidator
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRegisterBinding
    private lateinit var authService: AuthService
    private lateinit var tokenManager: TokenManager

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPrefs.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authService = RetrofitClient.createAuthService(this)
        tokenManager = TokenManager(this)

        setupPasswordValidation()
        updatePasswordRequirements("")

        binding.btnRegister.setOnClickListener {
            register()
        }

        binding.tvLoginLink.setOnClickListener {
            finish()
        }
        binding.tvServerSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        authService = RetrofitClient.createAuthService(this)
    }

    private fun setupPasswordValidation() {
        val passwordWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val password = s.toString()
                updatePasswordRequirements(password)
            }
        }

        val confirmPasswordWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateConfirmPassword()
            }
        }

        binding.etPassword.addTextChangedListener(passwordWatcher)
        binding.etConfirmPassword.addTextChangedListener(confirmPasswordWatcher)
    }

    private fun updatePasswordRequirements(password: String) {
        val sb = StringBuilder()

        val lengthOk = password.length >= 8
        val upperOk = password.any { it.isUpperCase() }
        val numberOk = password.any { it.isDigit() }
        val specialOk = password.any { !it.isLetterOrDigit() && !it.isWhitespace() }
        sb.append(if (lengthOk) "✓ " else "✗ ").append("Минимум 8 символов\n")
        sb.append(if (upperOk) "✓ " else "✗ ").append("Хотя бы одна заглавная буква\n")
        sb.append(if (numberOk) "✓ " else "✗ ").append("Хотя бы одна цифра\n")
        sb.append(if (specialOk) "✓ " else "✗ ").append("Хотя бы один специальный символ")

        binding.tvPasswordRequirements.text = sb.toString()
    }

    private fun validateConfirmPassword() {
        val password = binding.etPassword.text.toString()
        val confirmPassword = binding.etConfirmPassword.text.toString()

        if (confirmPassword.isNotEmpty()) {
            if (password == confirmPassword) {
                binding.tilConfirmPassword.error = null
            } else {
                binding.tilConfirmPassword.error = "Пароли не совпадают"
            }
        } else {
            binding.tilConfirmPassword.error = null
        }
    }

    private fun register() {
        val username = binding.etUsername.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val confirmPassword = binding.etConfirmPassword.text.toString()

        binding.tilUsername.error = null
        binding.tilEmail.error = null
        binding.tilPassword.error = null
        binding.tilConfirmPassword.error = null
        binding.tvError.visibility = android.view.View.GONE

        if (username.isEmpty() || email.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
            if (username.isEmpty()) binding.tilUsername.error = "Введите логин"
            if (email.isEmpty()) binding.tilEmail.error = "Введите email"
            if (password.isEmpty()) binding.tilPassword.error = "Введите пароль"
            if (confirmPassword.isEmpty()) binding.tilConfirmPassword.error = "Повторите пароль"
            return
        }

        if (username.length !in 3..32 || !Regex("^[\\p{L}\\d_.-]+$").matches(username)) {
            binding.tilUsername.error = "3–32 символа: буквы, цифры, точка, дефис или _"
            return
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.error = "Введите корректный email"
            return
        }

        val passwordValidation = PasswordValidator.validatePassword(password)
        if (!passwordValidation.isValid) {
            binding.tilPassword.error = passwordValidation.unmetRequirements.joinToString("; ")
            return
        }

        if (password != confirmPassword) {
            binding.tilConfirmPassword.error = "Пароли не совпадают"
            return
        }

        binding.btnRegister.isEnabled = false
        binding.progressBar.visibility = android.view.View.VISIBLE

        lifecycleScope.launch {
            try {
                val response = authService.register(
                    com.example.agrocontrol.model.RegisterRequest(username, email, password, confirmPassword)
                )

                if (response.isSuccessful && response.body() != null) {
                    val auth = response.body()!!
                    tokenManager.saveAccessToken(auth.access_token)
                    tokenManager.saveRefreshToken(auth.refresh_token)
                    tokenManager.saveUserInfo(auth.user.id, auth.user.username, auth.user.role)
                    Toast.makeText(this@RegisterActivity, "Аккаунт создан", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@RegisterActivity, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                } else {
                    showError(ApiErrorParser.message(response, "Не удалось создать аккаунт"))
                }
            } catch (e: Exception) {
                showError("Сервер недоступен. Проверьте адрес в настройках и подключение к сети.")
            } finally {
                binding.btnRegister.isEnabled = true
                binding.progressBar.visibility = android.view.View.GONE
            }
        }
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = android.view.View.VISIBLE
    }
}
