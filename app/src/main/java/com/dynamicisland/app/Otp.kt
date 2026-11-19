package com.dynamicisland.app

/** Deteksi kode OTP / verifikasi di teks notifikasi. */
object Otp {
    private val keyword = Regex(
        "(otp|kode|code|verif|pin|sandi|password|passcode|token|masuk|login|one[- ]time)",
        RegexOption.IGNORE_CASE,
    )
    private val number = Regex("(?<![\\d.,])(\\d{4,8})(?![\\d.,])")
    private val split = Regex("(?<!\\d)(\\d{3})[- ](\\d{3})(?!\\d)")

    fun find(text: String): String? {
        if (!keyword.containsMatchIn(text)) return null
        number.find(text)?.let { return it.groupValues[1] }
        split.find(text)?.let { return it.groupValues[1] + it.groupValues[2] }
        return null
    }
}
