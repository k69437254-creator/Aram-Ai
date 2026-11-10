package com.aram.ai.engine

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.aram.ai.data.ModelFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicLong

class GenerationQueueFullException(message: String = "طابور التوليد ممتلئ؛ أعد المحاولة بعد اكتمال الطلبات الحالية.") : IllegalStateException(message)

/** Process-wide owner for the single native model instance and its prioritized serial queue. */
object ModelRuntime {
    private val initLock = Mutex()
    private val generationLock = Mutex()
    @Volatile private var engine: InferenceEngine? = null
    @Volatile private var loaded: Boolean = false
    @Volatile private var acceptingRequests: Boolean = false

    private class QueuedGeneration(
        val priority: Int,
        val sequence: Long,
        val prompt: String,
        val systemPrompt: String,
        val maxTokens: Int,
        val onToken: (String) -> Unit
    ) {
        val result = CompletableDeferred<String>()
        @Volatile var activeJob: Job? = null
        @Volatile var cancelRequested: Boolean = false
        @Volatile var nativeStarted: Boolean = false
    }

    private val sequence = AtomicLong(0L)
    private val queueMonitor = Any()
    private val pending = PriorityQueue<QueuedGeneration>(
        compareBy<QueuedGeneration> { it.priority }.thenBy { it.sequence }
    )
    private val schedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val schedulerWake = Channel<Unit>(Channel.CONFLATED)

    init {
        schedulerScope.launch {
            for (ignored in schedulerWake) drainQueue()
        }
    }

    suspend fun load(context: Context, modelFile: File) = initLock.withLock {
        require(modelFile.isFile && modelFile.length() >= ModelFiles.MIN_MODEL_BYTES) {
            "ملف النموذج غير موجود أو تنزيله غير مكتمل"
        }
        if (loaded) return@withLock
        val instance = engine ?: withContext(Dispatchers.Default) {
            AiChat.getInferenceEngine(context.applicationContext)
        }.also { engine = it }
        try {
            val initializedState = withTimeout(30_000L) {
                instance.state.first { state ->
                    state is InferenceEngine.State.Initialized || state is InferenceEngine.State.Error
                }
            }
            if (initializedState is InferenceEngine.State.Error) throw initializedState.exception
            withContext(Dispatchers.IO) { instance.loadModel(modelFile.absolutePath) }
            loaded = true
            acceptingRequests = true
        } catch (failure: Throwable) {
            acceptingRequests = false
            loaded = false
            throw failure
        }
    }

    /** Lower priority number runs first. Owner and on-device chat use 0; customer API uses 10. */
    suspend fun generate(
        prompt: String,
        systemPrompt: String = "أنت مساعد Aram AI. أجب بدقة ووضوح.",
        maxTokens: Int = 256,
        priority: Int = 10,
        onToken: (String) -> Unit
    ): String {
        check(loaded && acceptingRequests) { "النموذج غير جاهز لاستقبال الطلبات" }
        val request = QueuedGeneration(
            priority = priority.coerceIn(0, 1000),
            sequence = sequence.getAndIncrement(),
            prompt = prompt,
            systemPrompt = systemPrompt,
            maxTokens = maxTokens.coerceIn(32, MAX_SAFE_OUTPUT_TOKENS),
            onToken = onToken
        )
        synchronized(queueMonitor) {
            check(loaded && acceptingRequests) { "المحرك يتوقف ولا يقبل طلبات جديدة" }
            if (pending.size >= MAX_PENDING_REQUESTS) {
                // Priority 0 (device owner/local chat) may displace the lowest-priority
                // waiting customer request. A request already running is never preempted.
                val lowestPriority = pending.maxByOrNull { it.priority }
                if (request.priority == OWNER_PRIORITY && lowestPriority != null && lowestPriority.priority > OWNER_PRIORITY) {
                    pending.remove(lowestPriority)
                    lowestPriority.result.completeExceptionally(
                        GenerationQueueFullException("تمت إزاحة هذا الطلب من الطابور لإعطاء أولوية لطلب المالك.")
                    )
                } else {
                    throw GenerationQueueFullException()
                }
            }
            pending.add(request)
        }
        schedulerWake.trySend(Unit)
        return try {
            request.result.await()
        } catch (cancelled: CancellationException) {
            synchronized(request) {
                request.cancelRequested = true
                if (request.nativeStarted) engine?.cancelGeneration()
            }
            request.result.cancel(cancelled)
            throw cancelled
        }
    }

    private suspend fun drainQueue() {
        while (true) {
            val request = synchronized(queueMonitor) {
                while (pending.peek()?.result?.isCancelled == true) pending.poll()
                pending.poll()
            } ?: return
            if (request.result.isCancelled) continue

            val task = schedulerScope.async {
                try {
                    request.result.complete(runGeneration(request))
                } catch (cancelled: CancellationException) {
                    if (!request.result.isCancelled) request.result.completeExceptionally(cancelled)
                } catch (failure: Throwable) {
                    request.result.completeExceptionally(failure)
                }
            }
            request.activeJob = task
            if (request.cancelRequested || request.result.isCancelled) {
                synchronized(request) {
                    request.cancelRequested = true
                    if (request.nativeStarted) engine?.cancelGeneration()
                }
                task.cancel()
            }
            request.result.invokeOnCompletion {
                if (request.result.isCancelled) {
                    synchronized(request) {
                        request.cancelRequested = true
                        if (request.nativeStarted && request.activeJob === task) engine?.cancelGeneration()
                    }
                    task.cancel()
                }
            }
            task.join()
            request.activeJob = null
        }
    }

    private suspend fun runGeneration(request: QueuedGeneration): String = generationLock.withLock {
        val instance = engine ?: error("لم يتم تحميل النموذج بعد")
        check(loaded) { "النموذج غير جاهز" }
        val output = StringBuilder()
        instance.setSystemPrompt(request.systemPrompt)
        synchronized(request) {
            if (request.cancelRequested || request.result.isCancelled) {
                throw CancellationException("تم إلغاء الطلب قبل بدء التوليد")
            }
            instance.beginGeneration()
            request.nativeStarted = true
        }
        withContext(Dispatchers.Default) {
            instance.sendUserPrompt(request.prompt, request.maxTokens).collect { token ->
                output.append(token)
                request.onToken(token)
            }
        }
        output.toString()
    }

    fun pendingRequestCount(): Int = synchronized(queueMonitor) { pending.count { !it.result.isCancelled } }

    private const val MAX_PENDING_REQUESTS = 12
    private const val OWNER_PRIORITY = 0
    private const val MAX_SAFE_OUTPUT_TOKENS = 512

    suspend fun benchmark(): String = generationLock.withLock {
        val instance = engine ?: error("لم يتم إنشاء محرك الاستدلال بعد")
        check(loaded) { "النموذج غير جاهز لإجراء اختبار الأداء" }
        withContext(Dispatchers.IO) { instance.bench(pp = 256, tg = 64, pl = 1, nr = 1) }
    }

    suspend fun unload() {
        acceptingRequests = false
        synchronized(queueMonitor) {
            while (pending.isNotEmpty()) {
                pending.poll().result.completeExceptionally(IllegalStateException("تم إيقاف المحرك قبل تنفيذ الطلب."))
            }
        }
        // Let the active native call finish cleanly; cancelling during JNI template or
        // decode work can leave the singleton in a non-unloadable transient state.
        generationLock.withLock {
            initLock.withLock {
                val current = engine
                engine = null
                loaded = false
                if (current != null) withContext(Dispatchers.IO) { current.cleanUp() }
            }
        }
    }

    fun isLoaded(): Boolean = loaded && acceptingRequests
}
