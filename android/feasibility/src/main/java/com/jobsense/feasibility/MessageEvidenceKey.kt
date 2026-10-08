package com.jobsense.feasibility

import java.security.MessageDigest

object MessageEvidenceKey {
    fun from(source: String, vararg fields: String): String {
        // Length framing prevents ambiguity when a body contains separators.
        val value = (listOf(source) + fields).joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
