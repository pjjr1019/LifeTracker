package com.jobsense.feasibility

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** New assistant records are encrypted; existing message storage is not migrated by this class. */
class PrivateRecordCipher(private val key: SecretKey) {
    fun encrypt(value: ByteArray, associatedData: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        return cipher.iv + cipher.doFinal(value)
    }
    fun decrypt(value: ByteArray, associatedData: String): ByteArray {
        require(value.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, value.copyOfRange(0,12)))
        cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8)); return cipher.doFinal(value.copyOfRange(12,value.size))
    }
}

object PrivateWorkRecords {
    private const val ALIAS = "phils-life-tracker-assistant-records-v1"
    private fun cipher(): PrivateRecordCipher {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey(ALIAS,null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
        return PrivateRecordCipher(key)
    }
    private fun db(context: Context) = ConversationLedger.archiveDatabase(context).also {
        it.execSQL("CREATE TABLE IF NOT EXISTS assistant_records(id TEXT PRIMARY KEY,kind TEXT NOT NULL,created_at INTEGER NOT NULL,sealed BLOB NOT NULL)")
    }
    @Synchronized fun save(context: Context, kind: String, value: JSONObject) {
        require(kind in listOf("SHIFT","EQUIPMENT","ASSIGNMENT"))
        value.optString("amendsId").takeIf { it.isNotBlank() }?.let { amended ->
            require(read(context,kind).any { it.getString("id")==amended }) { "This record has already changed. Reload it before correcting." }
        }
        val id = UUID.randomUUID().toString(); val at = System.currentTimeMillis()
        val sealed = cipher().encrypt(value.put("id",id).put("recordedAt",at).put("confirmedByUser",true).toString().toByteArray(Charsets.UTF_8),"$id:$kind:$at")
        db(context).execSQL("INSERT INTO assistant_records(id,kind,created_at,sealed) VALUES(?,?,?,?)", arrayOf(id,kind,at,sealed))
    }
    @Synchronized fun read(context: Context, kind: String, includeAmended: Boolean = false): List<JSONObject> {
        val cipher = cipher()
        return db(context).rawQuery("SELECT id,created_at,sealed FROM assistant_records WHERE kind=? ORDER BY created_at DESC LIMIT 1001",arrayOf(kind)).use { c ->
            require(c.count <= 1000) { "Record limit reached; totals were withheld rather than truncating older records." }
            val rows=buildList { while(c.moveToNext()) add(JSONObject(cipher.decrypt(c.getBlob(2),"${c.getString(0)}:$kind:${c.getLong(1)}").toString(Charsets.UTF_8))) }
            val amended=rows.mapNotNull { it.optString("amendsId").takeIf { id->id.isNotBlank() } }.toSet()
            if(includeAmended) rows else rows.filter { it.getString("id") !in amended }
        }
    }
}
