package com.aram.ai.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aram.ai.engine.ModelRuntime
import com.aram.ai.service.ModelService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val durationMs: Long? = null,
    val chunksPerSecond: Double? = null,
    val failed: Boolean = false
)

/** Activity-scoped chat state; conversation and an in-flight generation survive tab switches. */
class ChatViewModel : ViewModel() {
    val messages = mutableStateListOf<ChatMessage>()
    var draft by mutableStateOf("")
        private set
    var selectedMode by mutableStateOf("مساعد")
        private set
    var maxTokens by mutableStateOf(128)
        private set
    var isGenerating by mutableStateOf(false)
        private set
    var error by mutableStateOf("")
        private set
    private var activeJob: Job? = null

    fun setDraft(value: String) { draft = value }
    fun setMode(value: String) { selectedMode = value }
    fun setMaxTokens(value: Int) { maxTokens = value.coerceIn(64, 512) }

    fun clearConversation() {
        if (!isGenerating) {
            messages.clear()
            error = ""
        }
    }

    fun stopGeneration() { activeJob?.cancel() }

    fun sendMessage() {
        val userText = draft.trim()
        if (userText.isBlank() || isGenerating) return
        if (!ModelRuntime.isLoaded() || !ModelService.active) {
            error = "المحرك غير جاهز. شغّله من الصفحة الرئيسية أولًا."
            return
        }
        error = ""
        messages.add(ChatMessage(UUID.randomUUID().toString(), "user", userText))
        val assistantId = UUID.randomUUID().toString()
        messages.add(ChatMessage(assistantId, "assistant", ""))
        draft = ""
        isGenerating = true

        val previous = messages.dropLast(2).takeLast(14)
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
        val systemPrompt = when (selectedMode) {
            "برمجة" -> "أنت مساعد برمجي خبير. اكتب حلولًا عملية قابلة للتنفيذ، اشرح الافتراضات، راعِ الحالات الحدّية، ولا تدّع اختبار كود لم تشغّله. أجب بلغة المستخدم."
            "تحليل" -> "أنت مساعد تحليلي دقيق. فكك المسألة إلى خطوات، ميّز الحقائق عن الافتراضات، اعرض الاستنتاجات وحدود الثقة، وأجب بلغة المستخدم."
            else -> "أنت Aram AI، مساعد محلي واضح ودقيق. افهم طلب المستخدم، اسأل عند الحاجة، ولا تختلق حقائق أو نتائج أدوات. أجب بلغة المستخدم."
        }
        val started = System.nanoTime()
        val chunkCount = intArrayOf(0)
        val answer = StringBuilder()
        val mainHandler = Handler(Looper.getMainLooper())

        activeJob = viewModelScope.launch {
            try {
                val result = ModelRuntime.generate(prompt, systemPrompt, maxTokens) { piece ->
                    answer.append(piece)
                    chunkCount[0]++
                    val snapshot = answer.toString()
                    mainHandler.post {
                        val index = messages.indexOfFirst { it.id == assistantId }
                        if (index >= 0) messages[index] = messages[index].copy(content = snapshot)
                    }
                }
                val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
                val rate = chunkCount[0].toDouble() / (elapsed / 1000.0)
                withContext(Dispatchers.Main.immediate) {
                    val index = messages.indexOfFirst { it.id == assistantId }
                    if (index >= 0) messages[index] = messages[index].copy(
                        content = result.ifBlank { "لم يُنتج النموذج نصًا في هذا الطلب." },
                        durationMs = elapsed,
                        chunksPerSecond = rate
                    )
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    val index = messages.indexOfFirst { it.id == assistantId }
                    if (index >= 0) messages[index] = messages[index].copy(content = answer.toString() + "\n\n[أُوقف التوليد]", failed = true)
                }
                throw cancelled
            } catch (failure: Throwable) {
                withContext(Dispatchers.Main.immediate) {
                    val index = messages.indexOfFirst { it.id == assistantId }
                    val detail = failure.message ?: failure.javaClass.simpleName
                    if (index >= 0) messages[index] = messages[index].copy(
                        content = answer.toString().ifBlank { "تعذر توليد الإجابة: $detail" },
                        failed = true
                    )
                    error = "فشل التوليد: $detail"
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    isGenerating = false
                    activeJob = null
                }
            }
        }
    }
}
