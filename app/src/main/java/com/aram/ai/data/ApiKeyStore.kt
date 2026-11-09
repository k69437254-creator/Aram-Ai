package com.aram.ai.data

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.util.UUID

data class ApiKeyRecord(
    val id: String,
    val name: String,
    val prefix: String,
    val createdAt: Long,
    val requests: Long,
    val revoked: Boolean,
    val role: String = "client",
    val dailyQuota: Int = 100,
    val requestsToday: Long = 0,
    val quotaDay: String = ""
) {
    val isOwner: Boolean get() = role == "owner"
}

data class IssuedApiKey(val secret: String, val record: ApiKeyRecord)

/** Stores only SHA-256 token digests. Owner keys are unique, non-expiring and quota-free. */
class ApiKeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("aram_ai_api_keys", Context.MODE_PRIVATE)
    private val lock = Any()

    /** Backward-compatible default: newly issued regular keys receive 100 requests/day. */
    fun issue(name: String): IssuedApiKey = issueClient(name, 100)

    fun issueClient(name: String, dailyQuota: Int): IssuedApiKey = synchronized(lock) {
        issueLocked(name, "client", dailyQuota.coerceIn(1, 100_000))
    }

    fun issueOwnerKey(name: String = "مفتاح المالك"): IssuedApiKey = synchronized(lock) {
        check(!ownerKeyExistsLocked()) { "يوجد مفتاح مالك فعال بالفعل" }
        issueLocked(name, "owner", 0)
    }

    fun ownerKeyExists(): Boolean = synchronized(lock) { ownerKeyExistsLocked() }

    private fun ownerKeyExistsLocked(): Boolean {
        val json = readJson()
        return (0 until json.length()).any { i ->
            val item = json.optJSONObject(i)
            item != null && item.optString("role", "client") == "owner" && !item.optBoolean("revoked", false)
        }
    }

    private fun issueLocked(name: String, role: String, dailyQuota: Int): IssuedApiKey {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val secret = "arai_" + Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val record = ApiKeyRecord(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { if (role == "owner") "مفتاح المالك" else "مفتاح عميل" },
            prefix = secret.take(13),
            createdAt = System.currentTimeMillis(),
            requests = 0,
            revoked = false,
            role = role,
            dailyQuota = dailyQuota,
            requestsToday = 0,
            quotaDay = LocalDate.now().toString()
        )
        val json = readJson()
        json.put(toJson(record, digest(secret)))
        prefs.edit().putString("records", json.toString()).apply()
        return IssuedApiKey(secret, record)
    }

    fun list(): List<ApiKeyRecord> = synchronized(lock) {
        val json = readJson()
        (0 until json.length()).mapNotNull { index ->
            runCatching { fromJson(json.getJSONObject(index)) }.getOrNull()
        }.sortedWith(compareByDescending<ApiKeyRecord> { it.isOwner }.thenByDescending { it.createdAt })
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

    /** Atomically checks quota and counts one accepted API request. Owner requests never hit a quota. */
    fun consumeRequest(id: String): Boolean = synchronized(lock) {
        val json = readJson()
        val today = LocalDate.now().toString()
        for (index in 0 until json.length()) {
            val item = json.optJSONObject(index) ?: continue
            if (item.optString("id") != id || item.optBoolean("revoked", false)) continue
            val role = item.optString("role", "client")
            val quota = item.optInt("dailyQuota", 100).coerceAtLeast(1)
            val day = item.optString("quotaDay", "")
            var todayCount = if (day == today) item.optLong("requestsToday", 0L) else 0L
            if (role != "owner" && todayCount >= quota) return@synchronized false
            item.put("requests", item.optLong("requests", 0L) + 1L)
            if (role != "owner") todayCount++
            item.put("requestsToday", todayCount)
            item.put("quotaDay", today)
            prefs.edit().putString("records", json.toString()).apply()
            return@synchronized true
        }
        false
    }

    /** Compatibility shim for older in-app diagnostics. */
    fun recordRequest(id: String) { consumeRequest(id) }

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
        .put("revoked", record.revoked).put("role", record.role)
        .put("dailyQuota", record.dailyQuota).put("requestsToday", record.requestsToday)
        .put("quotaDay", record.quotaDay).put("hash", hash)

    private fun fromJson(item: JSONObject) = ApiKeyRecord(
        id = item.optString("id"),
        name = item.optString("name"),
        prefix = item.optString("prefix"),
        createdAt = item.optLong("createdAt"),
        requests = item.optLong("requests"),
        revoked = item.optBoolean("revoked", false),
        role = item.optString("role", "client"),
        dailyQuota = item.optInt("dailyQuota", 100),
        requestsToday = item.optLong("requestsToday", 0L),
        quotaDay = item.optString("quotaDay", "")
    )
}
