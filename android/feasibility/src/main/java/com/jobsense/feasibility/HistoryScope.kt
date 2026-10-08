package com.jobsense.feasibility

import java.security.MessageDigest

data class HistoryScope(val name: String, val number: String, val rawNumber: String, val country: String) {
    init {
        require(TestContact.normalizedNumber(number) == number)
        val rawDigits = rawNumber.filterNot { it.isWhitespace() || it in "()-" }.removePrefix("+")
        val nationalDigits = rawDigits.trimStart('0')
        require(rawNumber.isBlank() || (rawDigits.matches(Regex("[0-9]{7,15}")) &&
            nationalDigits.length >= 7 && number.removePrefix("+").endsWith(nationalDigits)))
    }

    val key: String get() = MessageDigest.getInstance("SHA-256")
        .digest(number.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun compact(value: String) = value.filterNot { it.isWhitespace() || it in "()-" }

    val addresses: List<String> get() = linkedSetOf(number, number.removePrefix("+"), rawNumber, compact(rawNumber))
        .apply {
            // A national number is meaningful only in an explicitly known NANP region.
            if (country in setOf("US", "CA") && number.startsWith("+1") && number.length == 12) {
                add(number.substring(2))
            }
        }.filter { it.isNotBlank() && it.matches(Regex("\\+?[0-9 ()-]+")) }

    val selectionArgs: Array<String> get() = addresses.map(::compact).distinct().toTypedArray()

    // Build placeholders from the exact same arguments used by the provider.
    val querySelection: String get() = "REPLACE(REPLACE(REPLACE(REPLACE(address, ' ', ''), '-', ''), '(', ''), ')', '') IN (" +
        selectionArgs.joinToString(",") { "?" } + ")"

    fun matchesAddress(value: String): Boolean = compact(value) in selectionArgs
}
