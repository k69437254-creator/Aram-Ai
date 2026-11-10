package com.aram.ai.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aram.ai.engine.ModelRuntime
import com.aram.ai.service.ModelService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val durationMs: Long? = null,
    val chunksPerSecond: Double? = null,
    val firstChunkMs: Long? = null,
    val failed: Boolean = false
)

data class ChatConversationSummary(
    val id: String,
    val title: String,
    val updatedAt: Long
)

/** Activity-scoped chat state backed by a bounded local conversation history. */
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("aram_ai_conversations_v1", Context.MODE_PRIVATE)
    private val storedMessages = LinkedHashMap<String, MutableList<ChatMessage>>()
    private val storedTitles = LinkedHashMap<String, String>()
    private val storedTimes = LinkedHashMap<String, Long>()
    private val storedModes = LinkedHashMap<String, String>()
    private val storedBudgets = LinkedHashMap<String, Int>()
    private val storedManualBudgets = LinkedHashMap<String, Boolean>()

    val messages = mutableStateListOf<ChatMessage>()
    val conversations = mutableStateListOf<ChatConversationSummary>()

    var activeConversationId by mutableStateOf("")
        private set
    val activeConversationTitle: String
        get() = conversations.firstOrNull { it.id == activeConversationId }?.title ?: "محادثة جديدة"

    var draft by mutableStateOf("")
        private set
    var selectedMode by mutableStateOf("عادي")
        private set
    var maxTokens by mutableStateOf(128)
        private set
    val effectiveMaxTokens: Int get() = maxTokens.coerceAtMost(MAX_SAFE_OUTPUT_TOKENS)
    val budgetIsCapped: Boolean get() = maxTokens > effectiveMaxTokens
    var isGenerating by mutableStateOf(false)
        private set
    var isCancelling by mutableStateOf(false)
        private set
    var error by mutableStateOf("")
        private set
    private var activeJob: Job? = null

    init {
        restoreConversations()
        if (storedMessages.isEmpty()) {
            createConversationInternal()
        } else {
            val latest = storedTimes.maxByOrNull { it.value }?.key ?: storedMessages.keys.last()
            activate(latest)
        }
        refreshConversationSummaries()
    }

    fun updateDraft(value: String) { draft = value }

    /** A mode is a task preset, not a hidden-reasoning switch. */
    fun setMode(value: String) {
        if (isGenerating) return
        val normalized = if (MODE_BUDGETS.containsKey(value)) value else "عادي"
        selectedMode = normalized
        maxTokens = MODE_BUDGETS.getValue(normalized)
        storedManualBudgets[activeConversationId] = false
        persistActive()
    }

    /** User may request up to 2,000 tokens; the safe runtime cap remains separately visible. */
    fun updateMaxTokens(value: Int) {
        if (isGenerating) return
        maxTokens = value.coerceIn(64, MAX_REQUESTED_OUTPUT_TOKENS)
        storedManualBudgets[activeConversationId] = true
        persistActive()
    }

    fun newConversation() {
        if (isGenerating) return
        persistActive()
        createConversationInternal()
        error = ""
    }

    fun selectConversation(id: String) {
        if (isGenerating || id == activeConversationId || !storedMessages.containsKey(id)) return
        persistActive()
        activate(id)
        error = ""
    }

    fun deleteConversation(id: String) {
        if (isGenerating) return
        storedMessages.remove(id)
        storedTitles.remove(id)
        storedTimes.remove(id)
        storedModes.remove(id)
        storedBudgets.remove(id)
        storedManualBudgets.remove(id)
        if (activeConversationId == id) {
            val next = storedTimes.maxByOrNull { it.value }?.key
            if (next != null) activate(next) else createConversationInternal()
        }
        persistAll()
        refreshConversationSummaries()
    }

    fun clearConversation() {
        if (!isGenerating) {
            messages.clear()
            error = ""
            storedTitles[activeConversationId] = "محادثة جديدة"
            persistActive()
        }
    }

    fun stopGeneration() {
        if (!isGenerating || isCancelling) return
        isCancelling = true
        activeJob?.cancel()
    }

    /** Requests a continuation using the existing conversation history, without replaying from scratch. */
    fun continueLast() {
        if (isGenerating || messages.none { it.role == "assistant" && it.content.isNotBlank() }) return
        draft = "أكمل إجابتك السابقة من حيث انتهيت مباشرة، دون إعادة ما سبق."
        sendMessage()
    }

    /** Replays the last user turn after removing its previous assistant response. */
    fun regenerateLast() {
        if (isGenerating) return
        val userIndex = messages.indexOfLast { it.role == "user" }
        if (userIndex < 0) return
        val userText = messages[userIndex].content
        while (messages.size > userIndex) messages.removeAt(messages.lastIndex)
        draft = userText
        persistActive()
        sendMessage()
    }

    fun sendMessage() {
        val userText = draft.trim()
        if (userText.isBlank() || isGenerating) return
        val requestMode = selectedMode
        val effectiveBudget = effectiveMaxTokens
        if (!ModelRuntime.isLoaded() || !ModelService.active) {
            error = "المحرك غير جاهز. شغّله من الصفحة الرئيسية أولًا."
            return
        }

        error = ""
        isCancelling = false
        messages.add(ChatMessage(UUID.randomUUID().toString(), "user", userText))
        draft = ""
        isGenerating = true

        // Only completed assistant messages enter the conversation history.
        val previous = messages.dropLast(1).takeLast(14)
        val prompt = buildString {
            previous.forEach { message ->
                if (message.content.isNotBlank()) {
                    append(if (message.role == "user") "المستخدم: " else "المساعد: ")
                    append(message.content)
                    append("\n")
                }
            }
            append("المستخدم: ")
            append(userText)
            append("\nالمساعد:")
        }
        val systemPrompt = when (requestMode) {
            "برمجة" -> "أنت مساعد برمجي خبير. اكتب حلولًا عملية قابلة للتنفيذ، اشرح الافتراضات، راعِ الحالات الحدّية، ولا تدّع اختبار كود لم تشغّله. أجب بلغة المستخدم. اعرض النتيجة النهائية فقط، ولا تعرض التفكير الداخلي أو المسودة الذهنية."
            "تحليل" -> "أنت مساعد تحليلي دقيق. ميّز الحقائق عن الافتراضات، وقدّم خلاصة واضحة وأسبابًا موجزة دون سرد التفكير الداخلي أو المسودة الذهنية. أجب بلغة المستخدم."
            else -> "أنت Aram AI، مساعد محلي واضح ودقيق. افهم طلب المستخدم، اسأل عند الحاجة، ولا تختلق حقائق أو نتائج أدوات. قدّم الإجابة النهائية مباشرة ولا تعرض التفكير الداخلي أو المسودة الذهنية. أجب بلغة المستخدم."
        }
        persistActive()

        val started = System.nanoTime()
        var firstChunkAt: Long? = null
        var chunkCount = 0
        val answer = StringBuilder()

        activeJob = viewModelScope.launch {
            try {
                val result = ModelRuntime.generate(prompt, systemPrompt, effectiveBudget, priority = 0) { piece ->
                    if (firstChunkAt == null) firstChunkAt = System.nanoTime()
                    answer.append(piece)
                    chunkCount++
                }
                val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
                val firstLatency = firstChunkAt?.let { ((it - started) / 1_000_000L).coerceAtLeast(0L) }
                val rate = chunkCount.toDouble() / (elapsed / 1000.0)
                withContext(Dispatchers.Main.immediate) {
                    messages.add(
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = "assistant",
                            content = result.ifBlank { "لم يُنتج النموذج نصًا في هذا الطلب." },
                            durationMs = elapsed,
                            chunksPerSecond = rate,
                            firstChunkMs = firstLatency
                        )
                    )
                    persistActive()
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    // Let the loading indicator finish its fade-out; no partial reply is kept.
                    delay(260L)
                    messages.removeAll { it.role == "assistant" && it.content.isBlank() }
                    persistActive()
                }
                throw cancelled
            } catch (failure: Throwable) {
                withContext(Dispatchers.Main.immediate) {
                    val detail = failure.message ?: failure.javaClass.simpleName
                    messages.add(
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = "assistant",
                            content = answer.toString().ifBlank { "تعذر توليد الإجابة: $detail" },
                            failed = true
                        )
                    )
                    error = "فشل التوليد: $detail"
                    persistActive()
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    isGenerating = false
                    isCancelling = false
                    activeJob = null
                }
            }
        }
    }

    private fun createConversationInternal() {
        val id = UUID.randomUUID().toString()
        storedMessages[id] = mutableListOf()
        storedTitles[id] = "محادثة جديدة"
        storedTimes[id] = System.currentTimeMillis()
        storedModes[id] = "عادي"
        storedBudgets[id] = MODE_BUDGETS.getValue("عادي")
        storedManualBudgets[id] = false
        activate(id)
        persistAll()
        refreshConversationSummaries()
    }

    private fun activate(id: String) {
        activeConversationId = id
        messages.clear()
        messages.addAll(storedMessages[id].orEmpty())
        if (!storedMessages.containsKey(id)) storedMessages[id] = messages.toMutableList()
        selectedMode = storedModes[id]?.takeIf { MODE_BUDGETS.containsKey(it) } ?: "عادي"
        maxTokens = (storedBudgets[id] ?: MODE_BUDGETS.getValue(selectedMode)).coerceIn(64, MAX_REQUESTED_OUTPUT_TOKENS)
        if (!storedManualBudgets.containsKey(id)) storedManualBudgets[id] = false
    }

    private fun persistActive() {
        if (activeConversationId.isBlank()) return
        storedMessages[activeConversationId] = messages.takeLast(MAX_MESSAGES).toMutableList()
        storedModes[activeConversationId] = selectedMode
        storedBudgets[activeConversationId] = maxTokens
        val firstUser = messages.firstOrNull { it.role == "user" }?.content.orEmpty()
        if (firstUser.isNotBlank()) storedTitles[activeConversationId] = firstUser.replace('\n', ' ').trim().take(38)
        storedTimes[activeConversationId] = System.currentTimeMillis()
        persistAll()
        refreshConversationSummaries()
    }

    private fun refreshConversationSummaries() {
        conversations.clear()
        conversations.addAll(storedMessages.keys.map { id ->
            ChatConversationSummary(id, storedTitles[id] ?: "محادثة جديدة", storedTimes[id] ?: 0L)
        }.sortedByDescending { it.updatedAt })
    }

    private fun persistAll() {
        val ordered = storedMessages.keys.sortedByDescending { storedTimes[it] ?: 0L }.take(MAX_CONVERSATIONS)
        val retained = ordered.toSet()
        (storedMessages.keys - retained).forEach { id ->
            storedMessages.remove(id)
            storedTitles.remove(id)
            storedTimes.remove(id)
            storedModes.remove(id)
            storedBudgets.remove(id)
            storedManualBudgets.remove(id)
        }
        val root = JSONArray()
        ordered.forEach { id ->
            val entry = JSONObject()
                .put("id", id)
                .put("title", storedTitles[id] ?: "محادثة جديدة")
                .put("updatedAt", storedTimes[id] ?: 0L)
                .put("mode", storedModes[id] ?: "عادي")
                .put("maxTokens", storedBudgets[id] ?: MODE_BUDGETS.getValue("عادي"))
                .put("manualBudget", storedManualBudgets[id] ?: false)
            val array = JSONArray()
            storedMessages[id].orEmpty().takeLast(MAX_MESSAGES).forEach { message ->
                array.put(JSONObject()
                    .put("id", message.id)
                    .put("role", message.role)
                    .put("content", message.content)
                    .put("durationMs", message.durationMs ?: JSONObject.NULL)
                    .put("chunksPerSecond", message.chunksPerSecond ?: JSONObject.NULL)
                    .put("firstChunkMs", message.firstChunkMs ?: JSONObject.NULL)
                    .put("failed", message.failed))
            }
            entry.put("messages", array)
            root.put(entry)
        }
        prefs.edit().putString("history", root.toString()).apply()
    }

    private fun restoreConversations() {
        val root = runCatching { JSONArray(prefs.getString("history", "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until root.length()) {
            val item = root.optJSONObject(i) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            val array = item.optJSONArray("messages") ?: JSONArray()
            val list = mutableListOf<ChatMessage>()
            for (j in 0 until array.length()) {
                val m = array.optJSONObject(j) ?: continue
                val duration = m.opt("durationMs")
                val rate = m.opt("chunksPerSecond")
                val first = if (m.has("firstChunkMs")) m.opt("firstChunkMs") else m.opt("firstTokenMs")
                list.add(ChatMessage(
                    id = m.optString("id", UUID.randomUUID().toString()),
                    role = m.optString("role", "assistant"),
                    content = m.optString("content"),
                    durationMs = if (duration is Number) duration.toLong() else null,
                    chunksPerSecond = if (rate is Number) rate.toDouble() else null,
                    firstChunkMs = if (first is Number) first.toLong() else null,
                    failed = m.optBoolean("failed", false)
                ))
            }
            // Discard empty assistant placeholders left by an older interrupted generation.
            val restored = list.filterNot { msg -> msg.role == "assistant" && msg.content.isBlank() }.toMutableList()
            storedMessages[id] = restored
            storedTitles[id] = item.optString("title", "محادثة جديدة")
            storedTimes[id] = item.optLong("updatedAt", 0L)
            val restoredMode = item.optString("mode", "عادي").takeIf { MODE_BUDGETS.containsKey(it) } ?: "عادي"
            storedModes[id] = restoredMode
            storedBudgets[id] = item.optInt("maxTokens", MODE_BUDGETS.getValue(restoredMode)).coerceIn(64, MAX_REQUESTED_OUTPUT_TOKENS)
            storedManualBudgets[id] = item.optBoolean("manualBudget", false)
        }
    }

    companion object {
        private const val MAX_CONVERSATIONS = 40
        private const val MAX_MESSAGES = 80
        private const val MAX_REQUESTED_OUTPUT_TOKENS = 2000
        private const val MAX_SAFE_OUTPUT_TOKENS = 512
        private val MODE_BUDGETS = mapOf("عادي" to 128, "تحليل" to 500, "برمجة" to 2000)
    }
}
