package com.aram.ai.engine

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Process-wide owner for the single native model instance. */
object ModelRuntime {
    private val initLock = Mutex()
    private val generationLock = Mutex()
    @Volatile private var engine: InferenceEngine? = null
    @Volatile private var loaded: Boolean = false

    suspend fun load(context: Context, modelFile: File) = initLock.withLock {
        require(modelFile.isFile && modelFile.length() >= 1_500_000_000L) { "ملف النموذج غير موجود أو تنزيله غير مكتمل" }
        val instance = engine ?: withContext(Dispatchers.Default) {
            AiChat.getInferenceEngine(context.applicationContext)
        }.also { engine = it }
        withContext(Dispatchers.IO) { instance.loadModel(modelFile.absolutePath) }
        loaded = true
    }

    suspend fun generate(
        prompt: String,
        systemPrompt: String = "أنت مساعد Aram AI. أجب بدقة ووضوح.",
        maxTokens: Int = 256,
        onToken: (String) -> Unit
    ): String = generationLock.withLock {
        val instance = engine ?: error("لم يتم تحميل النموذج بعد")
        check(loaded) { "النموذج غير جاهز" }
        val output = StringBuilder()
        // prepare_native.sh patches the binding to allow one clean system-prompt reset per API request.
        instance.setSystemPrompt(systemPrompt)
        withContext(Dispatchers.Default) {
            instance.sendUserPrompt(prompt, maxTokens.coerceIn(32, 512)).collect { token ->
                output.append(token)
                onToken(token)
            }
        }
        output.toString()
    }

    suspend fun unload() = generationLock.withLock {
        initLock.withLock {
            engine?.let { current ->
                withContext(Dispatchers.IO) { current.cleanUp() }
            }
            loaded = false
        }
    }

    fun isLoaded(): Boolean = loaded
}
