package com.jobsense.feasibility

import android.content.Context
import android.net.Uri

data class ArchiveThread(val id: Long, val participants: List<String>)

/** Metadata only: unrelated message bodies are never fetched for the group picker. */
object ArchiveThreads {
    fun read(context: Context): List<ArchiveThread> {
        val result = mutableListOf<ArchiveThread>()
        // Providers may ignore the requested projection for singular address URIs.
        // Resolve IDs from the collection using named columns instead.
        val addresses = mutableMapOf<Long, String>()
        context.contentResolver.query(Uri.parse("content://mms-sms/canonical-addresses"), arrayOf("_id", "address"), null, null, null)?.use { c ->
            val idColumn = c.getColumnIndexOrThrow("_id")
            val addressColumn = c.getColumnIndexOrThrow("address")
            while (c.moveToNext()) addresses[c.getLong(idColumn)] = c.getString(addressColumn).orEmpty()
        } ?: throw java.io.IOException("Conversation participants unavailable")
        context.contentResolver.query(Uri.parse("content://mms-sms/conversations?simple=true"), arrayOf("_id", "recipient_ids"), null, null, "date DESC")?.use { c ->
            val idColumn = c.getColumnIndexOrThrow("_id")
            val recipientsColumn = c.getColumnIndexOrThrow("recipient_ids")
            while (c.moveToNext()) {
                val recipientIds = c.getString(recipientsColumn).orEmpty().trim().split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.toLong() }
                val participants = recipientIds.map { requireNotNull(addresses[it]) { "Unknown conversation participant" } }.distinct().sorted()
                if (participants.isNotEmpty()) result.add(ArchiveThread(c.getLong(idColumn), participants))
            }
        } ?: throw java.io.IOException("Conversation metadata unavailable")
        return result
    }
}
