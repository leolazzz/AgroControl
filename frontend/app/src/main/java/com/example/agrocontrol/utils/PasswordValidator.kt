package com.example.agrocontrol.utils

enum class PasswordStrength {
    WEAK,
    MEDIUM,
    STRONG
}

data class PasswordValidationResult(
    val isValid: Boolean,
    val unmetRequirements: List<String>,
    val strength: PasswordStrength
)

object PasswordValidator {

    private const val MIN_LENGTH = 8

    fun validatePassword(password: String): PasswordValidationResult {
        val errors = mutableListOf<String>()

        if (password.length < MIN_LENGTH) {
            errors.add("Минимум $MIN_LENGTH символов")
        }

        if (password.toByteArray(Charsets.UTF_8).size > 72) {
            errors.add("Пароль слишком длинный")
        }

        if (!password.any { it.isUpperCase() }) {
            errors.add("Хотя бы одна заглавная буква")
        }

        if (!password.any { it.isDigit() }) {
            errors.add("Хотя бы одна цифра")
        }

        if (!password.any { !it.isLetterOrDigit() && !it.isWhitespace() }) {
            errors.add("Хотя бы один специальный символ")
        }

        val strength = when {
            errors.isEmpty() && password.length >= 12 -> PasswordStrength.STRONG
            errors.size <= 1 -> PasswordStrength.MEDIUM
            else -> PasswordStrength.WEAK
        }

        return PasswordValidationResult(
            isValid = errors.isEmpty(),
            unmetRequirements = errors,
            strength = strength
        )
    }
}
