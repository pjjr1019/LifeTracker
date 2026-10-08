package com.jobsense.feasibility

data class NotificationIdentity(
    val titles: List<String>,
    val senderNames: List<String>,
    val senderUris: List<String>,
    val isGroupConversation: Boolean,
)

object TestContact {
    fun normalizedNumber(value: String): String? {
        val stripped = value.filterNot { it.isWhitespace() || it in "()-" }
        return stripped.takeIf { it.matches(Regex("\\+[1-9][0-9]{6,14}")) }
    }

    fun matchesNumber(expected: String, actual: String): Boolean =
        normalizedNumber(expected)?.let { it == normalizedNumber(actual) } ?: false

    fun matchesNotification(expectedName: String, expectedNumber: String, identity: NotificationIdentity): Boolean {
        val titleMatches = expectedName.isNotBlank() && expectedName in identity.titles
        val directSenderMatches = expectedName.isNotBlank() && !identity.isGroupConversation &&
            expectedName in identity.senderNames
        val numberMatches = hasNumberIdentity(expectedNumber, identity)
        return titleMatches || directSenderMatches || numberMatches
    }

    private fun hasNumberIdentity(expectedNumber: String, identity: NotificationIdentity): Boolean = !identity.isGroupConversation &&
            (identity.senderUris.any { matchesNumber(expectedNumber, it.removePrefix("tel:")) } ||
                identity.titles.any { matchesNumber(expectedNumber, it) } ||
                identity.senderNames.any { matchesNumber(expectedNumber, it) })

    fun matchingConversations(scopes: List<HistoryScope>, identity: NotificationIdentity): List<HistoryScope> {
        if (identity.isGroupConversation) return emptyList()
        val numberMatches = scopes.filter { hasNumberIdentity(it.number, identity) }
        if (numberMatches.isNotEmpty()) return numberMatches.takeIf { it.size == 1 }.orEmpty()
        return scopes.filter { matchesNotification(it.name, it.number, identity) }.takeIf { it.size == 1 }.orEmpty()
    }
}
