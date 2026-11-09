package com.aram.ai.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aram.ai.AramAiApplication
import com.aram.ai.data.AppPrefs
import com.aram.ai.data.EngineStatus
import com.aram.ai.data.ModelFiles
import com.aram.ai.engine.ModelRuntime
import com.aram.ai.service.ModelService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private val Ink = Color(0xFF0B0914)
private val Panel = Color(0xFF171226)
private val Panel2 = Color(0xFF211934)
private val Violet = Color(0xFF9B7BFF)
private val Lilac = Color(0xFFD5C7FF)
private val Muted = Color(0xFFAAA1BE)
private val Green = Color(0xFF57D6A2)
private val Danger = Color(0xFFFF8295)
private const val API_BASE = "http://127.0.0.1:8765"

@Composable
fun DeveloperScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(AppPrefs.engineStatus(context)) }
    var availableRam by remember { mutableStateOf("—") }
    var totalRam by remember { mutableStateOf("—") }
    var appRam by remember { mutableStateOf("—") }
    var modelBytes by remember { mutableStateOf("—") }
    var pendingRequests by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Pair<Boolean, String>>>(emptyList()) }
    var sample by remember { mutableStateOf("") }
    var benchmarkResult by remember { mutableStateOf("") }
    val engineReady = ModelService.active && ModelRuntime.isLoaded()

    LaunchedEffect(Unit) {
        while (true) {
            AppPrefs.reconcileEngineState(context, ModelService.serviceAlive)
            status = AppPrefs.engineStatus(context)
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memory = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memory)
            availableRam = formatBytes(memory.availMem)
            totalRam = formatBytes(memory.totalMem)
            val processMemory = Debug.MemoryInfo()
            Debug.getMemoryInfo(processMemory)
            appRam = "${processMemory.totalPss / 1024} MB"
            val model = ModelFiles.modelFile(context)
            modelBytes = if (model.exists()) formatBytes(model.length()) else "غير موجود"
            pendingRequests = ModelRuntime.pendingRequestCount()
            delay(1500)
        }
    }

    LazyColumn(
        modifier.fillMaxSize().background(Ink),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        item {
            Text("واجهة المطوّر", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("مراقبة المحرك، الذاكرة، واجهة API، واختبارات قبول فعلية.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Surface(color = Panel, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Memory, null, tint = Violet)
                        Spacer(Modifier.width(8.dp))
                        Text("حالة المحرك", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (engineReady) Green else if (status.state == "ERROR") Danger else Muted))
                        Spacer(Modifier.width(6.dp))
                        Text(if (engineReady) "جاهز" else status.state, color = if (engineReady) Green else Muted, style = MaterialTheme.typography.labelMedium)
                    }
                    val statusMessage = if (status.state == "READY" && !engineReady)
                        "كانت الخدمة جاهزة سابقًا لكنها غير نشطة حاليًا."
                    else status.message
                    Text(statusMessage, color = if (status.state == "ERROR") Danger else Lilac, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(color = Panel2)
                    MetricLine("ملف النموذج", modelBytes)
                    MetricLine("النموذج محمّل في الذاكرة", if (ModelRuntime.isLoaded()) "نعم" else "لا")
                    MetricLine("خدمة Android نشطة", if (ModelService.serviceAlive) "نعم" else "لا")
                    MetricLine("طلبات التوليد المنتظرة", pendingRequests.toString())
                    MetricLine("مدة التشغيل", if (engineReady && status.startedAt > 0L) formatDuration(System.currentTimeMillis() - status.startedAt) else "—")
                    MetricLine("منفذ الخادم", "8765")
                    MetricLine("العنوان المحلي", API_BASE)
                }
            }
        }
        item {
            Text("موارد الجهاز", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                ResourceCard(Modifier.weight(1f), "RAM المتاحة", availableRam)
                ResourceCard(Modifier.weight(1f), "إجمالي RAM", totalRam)
                ResourceCard(Modifier.weight(1f), "ذاكرة التطبيق", appRam)
            }
            Text("قراءات الذاكرة آنية وقد تتغير أثناء التوليد. لا تمثل هذه القيم وحدها ذروة الاستهلاك.", color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        item {
            Surface(color = Panel, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Speed, null, tint = Violet)
                        Spacer(Modifier.width(8.dp))
                        Text("اختبارات القبول", fontWeight = FontWeight.Bold)
                    }
                    Text("الاختبار الكامل يفحص الصحة والمصادقة وتوليد الإجابة، ثم يختبر حصة العميل اليومية وسلوك مفتاح المالك. تُلغى مفاتيح الاختبار المؤقتة تلقائيًا.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = {
                            if (busy) return@Button
                            busy = true
                            stage = "يجري فحص الخادم والمصادقة ثم طلب توليد حقيقي…"
                            results = emptyList()
                            sample = ""
                            scope.launch {
                                val outcome = withContext(Dispatchers.IO) { runFullApiTest(context) }
                                results = outcome.first
                                sample = outcome.second
                                stage = if (outcome.first.all { it.first }) "اكتملت الاختبارات المحددة." else "اكتملت الفحوص مع وجود إخفاقات."
                                busy = false
                            }
                        },
                        enabled = engineReady && !busy,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Violet),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        else Icon(Icons.Rounded.Speed, null)
                        Spacer(Modifier.width(8.dp)); Text(if (busy) "جارٍ الاختبار…" else "تشغيل اختبار API الكامل")
                    }
                    OutlinedButton(
                        onClick = {
                            if (busy) return@OutlinedButton
                            busy = true
                            stage = "يجري تنفيذ اختبار أداء قصير على المحرك…"
                            benchmarkResult = ""
                            scope.launch {
                                try {
                                    benchmarkResult = withContext(Dispatchers.IO) { ModelRuntime.benchmark() }
                                    stage = "اكتمل اختبار الأداء الأصلي للمحرك."
                                } catch (failure: Throwable) {
                                    benchmarkResult = "فشل اختبار الأداء: ${failure.message ?: failure.javaClass.simpleName}"
                                    stage = "انتهى اختبار الأداء بخطأ."
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = engineReady && !busy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("قياس أداء النموذج") }
                    if (benchmarkResult.isNotBlank()) {
                        Surface(color = Panel2, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(benchmarkResult, modifier = Modifier.padding(12.dp), color = Lilac, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            if (busy) return@OutlinedButton
                            busy = true
                            stage = "يجري إرسال طلب Streaming إلى API وقراءة أحداث SSE…"
                            results = emptyList()
                            sample = ""
                            scope.launch {
                                val outcome = withContext(Dispatchers.IO) { runStreamApiTest(context) }
                                results = outcome.first
                                sample = outcome.second
                                stage = if (outcome.first.all { it.first }) "اختبار البث اكتمل." else "اختبار البث انتهى بإخفاق."
                                busy = false
                            }
                        },
                        enabled = engineReady && !busy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("اختبار Streaming عبر API") }
                    if (!engineReady) Text("شغّل المحرك أولًا لتفعيل الاختبارات.", color = Muted, style = MaterialTheme.typography.labelSmall)
                    if (stage.isNotBlank()) Text(stage, color = Lilac, style = MaterialTheme.typography.bodySmall)
                    results.forEach { (passed, line) ->
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (passed) "✓" else "×", color = if (passed) Green else Danger, fontWeight = FontWeight.Bold)
                            Text(line, color = if (passed) Color(0xFFECE6FF) else Danger, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (sample.isNotBlank()) {
                        HorizontalDivider(color = Panel2)
                        Text("مقتطف من إجابة النموذج", color = Lilac, fontWeight = FontWeight.SemiBold)
                        Text(sample, color = Color(0xFFF4F0FF), style = MaterialTheme.typography.bodySmall, lineHeight = 21.sp)
                    }
                }
            }
        }
        item {
            Surface(color = Panel, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("تفاصيل المطوّر", fontWeight = FontWeight.Bold)
                    Text("التوليد يعمل في طابور تسلسلي بأولوية للمالك على طلبات العملاء المنتظرة، مع حد انتظار لحماية الذاكرة. مفاتيح المالك بلا حصة يومية، ومفاتيح العملاء لها حصص منفصلة. لا يوجد حاليًا Tool Calling أو تنفيذ متوازٍ أو TLS أو بوابة إنترنت عامة.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Text("قياسات السرعة في شاشة المحادثة تعرض مقاطع التدفق المستلمة في الثانية، وليست قياسًا رسميًا لعدد رموز tokenizer في الثانية.", color = Lilac, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
        Text(value, color = Color(0xFFECE6FF), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ResourceCard(modifier: Modifier, label: String, value: String) {
    Surface(modifier, color = Panel2, shape = RoundedCornerShape(15.dp)) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(value, color = Lilac, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun runFullApiTest(context: Context): Pair<List<Pair<Boolean, String>>, String> {
    val results = mutableListOf<Pair<Boolean, String>>()
    val store = (context.applicationContext as AramAiApplication).apiKeyStore
    val issued = store.issue("اختبار مطوّر مؤقت")
    var sample = ""
    try {
        val quotaProbe = store.issueClient("اختبار حصة مؤقت", 1)
        val quotaPass = try {
            val firstAllowed = store.consumeRequest(quotaProbe.record.id)
            val secondBlocked = !store.consumeRequest(quotaProbe.record.id)
            firstAllowed && secondBlocked
        } finally {
            store.revoke(quotaProbe.record.id)
        }
        results += quotaPass to if (quotaPass) "حصة العميل فُرضت: طلب واحد مقبول والثاني مرفوض." else "فشل اختبار الحصة اليومية لمفتاح العميل."

        val ownerRecord = store.list().firstOrNull { it.isOwner && !it.revoked }
        if (ownerRecord != null) {
            val ownerPass = store.consumeRequest(ownerRecord.id) && store.consumeRequest(ownerRecord.id)
            results += ownerPass to if (ownerPass) "مفتاح المالك قبل طلبين متتاليين دون حصة يومية." else "فشل اختبار تجاوز حصة المالك."
        } else {
            results += true to "اختبار حصة المالك مؤجل حتى إنشاء مفتاح المالك من شاشة مفاتيح API."
        }

        val health = request("GET", "$API_BASE/health")
        val healthJson = runCatching { JSONObject(health.second) }.getOrNull()
        val healthy = health.first == 200 && healthJson?.optBoolean("model_loaded", false) == true
        results += healthy to if (healthy) "فحص /health نجح؛ الخادم يرى النموذج محمّلًا." else "فشل فحص /health أو أبلغ الخادم أن النموذج غير محمّل (HTTP ${health.first})."
        if (!healthy) return results to sample

        val goodModels = request("GET", "$API_BASE/v1/models", issued.secret)
        results += (goodModels.first == 200) to if (goodModels.first == 200) "المفتاح الصحيح قُبل في /v1/models." else "رفض الخادم المفتاح الصحيح في /v1/models (HTTP ${goodModels.first})."

        val badModels = request("GET", "$API_BASE/v1/models", "arai_invalid_key_for_test")
        results += (badModels.first == 401) to if (badModels.first == 401) "المفتاح غير الصحيح رُفض كما ينبغي (HTTP 401)." else "اختبار المفتاح الخاطئ غير سليم؛ النتيجة HTTP ${badModels.first}."

        val payload = JSONObject()
            .put("model", ModelFiles.MODEL_ID)
            .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", "أجب بالعربية بجملة قصيرة: ما وظيفة الذاكرة العشوائية في الهاتف؟")))
            .put("max_tokens", 64)
            .put("stream", false)
        val started = System.nanoTime()
        val generation = request("POST", "$API_BASE/v1/chat/completions", issued.secret, payload.toString(), 240_000)
        val elapsed = (System.nanoTime() - started) / 1_000_000L
        val json = runCatching { JSONObject(generation.second) }.getOrNull()
        sample = json?.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        val generated = generation.first == 200 && sample.isNotBlank()
        results += generated to if (generated) "نجح التوليد الفعلي عبر HTTP خلال ${elapsed / 1000.0} ثانية." else "فشل التوليد عبر API (HTTP ${generation.first}): ${generation.second.take(180)}"

        store.revoke(issued.record.id)
        val revokedModels = request("GET", "$API_BASE/v1/models", issued.secret)
        results += (revokedModels.first == 401) to if (revokedModels.first == 401) "إلغاء المفتاح فُعّل؛ المفتاح الملغى رُفض (HTTP 401)." else "فشل اختبار الإلغاء؛ المفتاح الملغى ما زال مقبولًا (HTTP ${revokedModels.first})."
    } catch (error: Throwable) {
        results += false to "توقف الاختبار بسبب خطأ: ${error.message ?: error.javaClass.simpleName}"
    } finally {
        store.revoke(issued.record.id)
        results += true to "أُلغي مفتاح الاختبار المؤقت تلقائيًا."
    }
    return results to sample
}

private fun runStreamApiTest(context: Context): Pair<List<Pair<Boolean, String>>, String> {
    val results = mutableListOf<Pair<Boolean, String>>()
    val store = (context.applicationContext as AramAiApplication).apiKeyStore
    val issued = store.issue("اختبار بث مؤقت")
    val received = StringBuilder()
    var done = false
    try {
        val connection = (URL("$API_BASE/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 240_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${issued.secret}")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
        }
        try {
            val payload = JSONObject()
                .put("model", ModelFiles.MODEL_ID)
                .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", "اكتب جملة عربية قصيرة عن التعلم.")))
                .put("max_tokens", 48)
                .put("stream", true)
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code != 200) {
                results += false to "فشل بدء البث عبر API (HTTP $code)."
                return results to received.toString()
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data: ")) continue
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") { done = true; break }
                    val event = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    val delta = event.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content").orEmpty()
                    received.append(delta)
                }
            }
        } finally {
            connection.disconnect()
        }
        results += (received.isNotBlank()) to if (received.isNotBlank()) "وصلت أجزاء نصية تدريجيًا من النموذج عبر SSE." else "لم تصل أي مقاطع نصية من البث."
        results += done to if (done) "وصل حدث الإنهاء [DONE] بصورة صحيحة." else "انتهى الاتصال دون حدث [DONE]."
    } catch (error: Throwable) {
        results += false to "فشل اختبار البث: ${error.message ?: error.javaClass.simpleName}"
    } finally {
        store.revoke(issued.record.id)
        results += true to "أُلغي مفتاح البث المؤقت تلقائيًا."
    }
    return results to received.toString()
}

private fun request(
    method: String,
    endpoint: String,
    bearer: String? = null,
    body: String? = null,
    readTimeout: Int = 15_000
): Pair<Int, String> {
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 10_000
        this.readTimeout = readTimeout
        setRequestProperty("Accept", "application/json")
        if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
        if (body != null) {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
    }
    return try {
        if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        code to text
    } finally {
        connection.disconnect()
    }
}

private fun formatDuration(millis: Long): String {
    val seconds = (millis / 1000L).coerceAtLeast(0L)
    val hours = seconds / 3600L
    val minutes = (seconds % 3600L) / 60L
    val remainder = seconds % 60L
    return if (hours > 0L) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, remainder)
    else String.format(Locale.US, "%d:%02d", minutes, remainder)
}

private fun formatBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) String.format(Locale.US, "%.2f GB", gb)
    else String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
}
