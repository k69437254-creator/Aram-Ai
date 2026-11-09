package com.aram.ai.engine

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.aram.ai.data.ModelFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/** Process-wide owner for the single native model instance. */
object ModelRuntime {
    private val initLock = Mutex()
    private val generationLock = Mutex()
    @Volatile private var engine: InferenceEngine? = null
    @Volatile private var loaded: Boolean = false

    suspend fun load(context: Context, modelFile: File) = initLock.withLock {
        require(modelFile.isFile && modelFile.length() >= ModelFiles.MIN_MODEL_BYTES) {
            "ملف النموذج غير موجود أو تنزيله غير مكتمل"
        }
        if (loaded) return@withLock
        val instance = engine ?: withContext(Dispatchers.Default) {
            AiChat.getInferenceEngine(context.applicationContext)
        }.also { engine = it }
        try {
            // InferenceEngineImpl initializes its native library asynchronously after getInstance().
            // Do not race loadModel() against that initialization on the first launch.
            val initializedState = withTimeout(30_000L) {
                instance.state.first { state ->
                    state is InferenceEngine.State.Initialized || state is InferenceEngine.State.Error
                }
            }
            if (initializedState is InferenceEngine.State.Error) {
                throw initializedState.exception
            }
            withContext(Dispatchers.IO) { instance.loadModel(modelFile.absolutePath) }
            loaded = true
        } catch (failure: Throwable) {
            // Keep the singleton reference so the service's failure path can reset its native Error state.
            loaded = false
            throw failure
        }
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
        // prepare_native.sh patches the upstream binding to reset the per-request system context.
        instance.setSystemPrompt(systemPrompt)
        withContext(Dispatchers.Default) {
            instance.sendUserPrompt(prompt, maxTokens.coerceIn(32, 512)).collect { token ->
                output.append(token)
                onToken(token)
            }
        }
        output.toString()
    }

    suspend fun benchmark(): String = generationLock.withLock {
        val instance = engine ?: error("لم يتم إنشاء محرك الاستدلال بعد")
        check(loaded) { "النموذج غير جاهز لإجراء اختبار الأداء" }
        withContext(Dispatchers.IO) { instance.bench(pp = 256, tg = 64, pl = 1, nr = 1) }
    }

    suspend fun unload() = generationLock.withLock {
        initLock.withLock {
            val current = engine
            engine = null
            loaded = false
            if (current != null) withContext(Dispatchers.IO) { current.cleanUp() }
        }
    }

    fun isLoaded(): Boolean = loaded
}
