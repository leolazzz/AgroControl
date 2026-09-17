package com.example.agrocontrol.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordValidatorTest {
    @Test
    fun rejectsPasswordWithoutRequiredCharacterGroups() {
        val result = PasswordValidator.validatePassword("password")

        assertFalse(result.isValid)
        assertTrue(result.unmetRequirements.contains("Хотя бы одна заглавная буква"))
        assertTrue(result.unmetRequirements.contains("Хотя бы одна цифра"))
        assertTrue(result.unmetRequirements.contains("Хотя бы один специальный символ"))
        assertEquals(PasswordStrength.WEAK, result.strength)
    }

    @Test
    fun acceptsPasswordThatMeetsAllRequirements() {
        val result = PasswordValidator.validatePassword("Agro2026!")

        assertTrue(result.isValid)
        assertTrue(result.unmetRequirements.isEmpty())
        assertEquals(PasswordStrength.MEDIUM, result.strength)
    }

    @Test
    fun marksLongValidPasswordAsStrong() {
        val result = PasswordValidator.validatePassword("HotBedAgro2026!")

        assertTrue(result.isValid)
        assertEquals(PasswordStrength.STRONG, result.strength)
    }

    @Test
    fun unicodeUppercaseIsRecognized() {
        val result = PasswordValidator.validatePassword("Теплица9!")

        assertTrue(result.isValid)
    }
}
