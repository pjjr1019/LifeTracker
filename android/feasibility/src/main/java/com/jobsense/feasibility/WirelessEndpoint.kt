package com.jobsense.feasibility

import java.net.URI

object WirelessEndpoint {
    fun validate(value: String): String {
        val uri = try { URI(value.trim()) } catch (error: Exception) {
            throw IllegalArgumentException("Invalid secure service address", error)
        }
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.path == "/snapshot" &&
            (uri.port == -1 || uri.port in 1..65535)) { "Enter the secure service address ending in /snapshot" }
        return uri.toASCIIString()
    }
}
