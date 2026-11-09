package com.aram.ai.data

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

data class ApiKeyRecord(
    val id: String,
    val name: String,
    val prefix: String,
    val createdAt: Long,
    val requests: Long,
    val revoked: Boolean
)

data class IssuedApiKey(val secret: String, val record: ApiKeyRecord)

/** Persists only SHA-256 digests; the plaintext token is displayed once. */
class ApiKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("aram_ai_api_keys", Context.MODE_PRIVATE)
    private val lock = Any()

    fun issue(name: String): IssuedApiKey = synchronized(lock) {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val secret = "arai_" + Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val record = ApiKeyRecord(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "مفتاح جديد" },
            prefix = secret.take(13),
            createdAt = System.currentTimeMillis(),
            requests = 0,
            revoked = false
        )
        val json = readJson()
        json.put(toJson(record, digest(secret)))
        prefs.edit().putString("records", json.toString()).apply()
        IssuedApiKey(secret, record)
    }

    fun list(): List<ApiKeyRecord> = synchronized(lock) {
        val json = readJson()
        (0 until json.length()).mapNotNull { index ->
            runCatching { fromJson(json.getJSONObject(index)) }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    fun authenticate(secret: String): ApiKeyRecord? = synchronized(lock) {
        if (secret.isBlank()) return@synchronized null
        val candidate = digest(secret).toByteArray(Charsets.US_ASCII)
        val json = readJson()
        for (index in 0 until json.length()) {
            val item = json.optJSONObject(index) ?: continue
            if (item.optBoolean("revoked", false)) continue
            val saved = item.optString("hash", "").toByteArray(Charsets.US_ASCII)
            if (saved.isNotEmpty() && MessageDigest.isEqual(saved, candidate)) return@synchronized fromJson(item)
        }
        null
    }

    fun recordRequest(id: String) = synchronized(lock) {
        val json = readJson()
        for (index in 0 until json.length()) {
            val item = json.optJSONObject(index) ?: continue
            if (item.optString("id") == id && !item.optBoolean("revoked", false)) {
                item.put("requests", item.optLong("requests", 0) + 1)
                break
            }
        }
        prefs.edit().putString("records", json.toString()).apply()
    }

    fun revoke(id: String) = synchronized(lock) {
        val json = readJson()
        for (index in 0 until json.length()) {
            val item = json.optJSONObject(index) ?: continue
            if (item.optString("id") == id) item.put("revoked", true)
        }
        prefs.edit().putString("records", json.toString()).apply()
    }

    private fun readJson(): JSONArray = runCatching { JSONArray(prefs.getString("records", "[]")) }.getOrDefault(JSONArray())

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun toJson(record: ApiKeyRecord, hash: String) = JSONObject()
        .put("id", record.id).put("name", record.name).put("prefix", record.prefix)
        .put("createdAt", record.createdAt).put("requests", record.requests)
        .put("revoked", record.revoked).put("hash", hash)

    private fun fromJson(item: JSONObject) = ApiKeyRecord(
        id = item.optString("id"), name = item.optString("name"), prefix = item.optString("prefix"),
        createdAt = item.optLong("createdAt"), requests = item.optLong("requests"),
        revoked = item.optBoolean("revoked", false)
    )
}
