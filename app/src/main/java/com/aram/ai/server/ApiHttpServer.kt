package com.aram.ai.server

import com.aram.ai.data.ApiKeyStore
import com.aram.ai.data.ModelFiles
import com.aram.ai.engine.ModelRuntime
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

class ApiHttpServer(
    host: String,
    private val keyStore: ApiKeyStore,
    port: Int = 8765
) : NanoHTTPD(host, port) {

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) return json(Response.Status.OK, "{}")
        if (session.uri == "/health" && session.method == Method.GET) {
            return json(Response.Status.OK, JSONObject()
                .put("status", "ok")
                .put("model_loaded", ModelRuntime.isLoaded())
                .put("model", ModelFiles.MODEL_ID)
                .toString())
        }
        if (session.uri == "/v1/models" && session.method == Method.GET) {
            val key = authenticate(session) ?: return unauthorized()
            keyStore.recordRequest(key.id)
            val data = JSONObject().put("object", "list").put("data", JSONArray().put(
                JSONObject().put("id", ModelFiles.MODEL_ID).put("object", "model").put("owned_by", "aram-ai")
            ))
            return json(Response.Status.OK, data.toString())
        }
        if (session.uri != "/v1/chat/completions" || session.method != Method.POST) {
            return json(Response.Status.NOT_FOUND, errorBody("not_found", "المسار غير موجود"))
        }

        val key = authenticate(session) ?: return unauthorized()
        val contentLength = session.headers.entries.firstOrNull { it.key.equals("content-length", true) }
            ?.value?.toLongOrNull() ?: 0L
        if (contentLength > MAX_BODY_BYTES) {
            return json(Response.Status.BAD_REQUEST, errorBody("request_too_large", "حجم الطلب يتجاوز 1 ميغابايت"))
        }

        return try {
            val bodyMap = HashMap<String, String>()
            session.parseBody(bodyMap)
            val rawBody = bodyMap["postData"] ?: "{}"
            if (rawBody.toByteArray(StandardCharsets.UTF_8).size > MAX_BODY_BYTES) {
                return json(Response.Status.BAD_REQUEST, errorBody("request_too_large", "حجم الطلب يتجاوز 1 ميغابايت"))
            }
            val request = JSONObject(rawBody)
            val messages = request.optJSONArray("messages") ?: JSONArray()
            if (messages.length() == 0) {
                return json(Response.Status.BAD_REQUEST, errorBody("invalid_request", "حقل messages مطلوب"))
            }
            val system = StringBuilder()
            val prompt = StringBuilder()
            for (i in 0 until messages.length()) {
                val item = messages.optJSONObject(i) ?: continue
                val role = item.optString("role", "user")
                val content = item.opt("content")?.toString().orEmpty()
                if (role == "system") {
                    if (system.isNotEmpty()) system.append('\n')
                    system.append(content)
                } else {
                    prompt.append(role.uppercase()).append(": ").append(content).append('\n')
                }
            }
            prompt.append("ASSISTANT:")
            val systemPrompt = system.toString().ifBlank { "أنت مساعد Aram AI. أجب بدقة ووضوح." }
            val maxTokens = request.optInt("max_tokens", 256).coerceIn(32, 512)
            keyStore.recordRequest(key.id)
            if (request.optBoolean("stream", false)) {
                streamResponse(prompt.toString(), systemPrompt, maxTokens)
            } else {
                val answer = runBlocking(Dispatchers.IO) {
                    ModelRuntime.generate(prompt.toString(), systemPrompt, maxTokens) { }
                }
                val response = JSONObject()
                    .put("id", "chatcmpl-${UUID.randomUUID()}")
                    .put("object", "chat.completion")
                    .put("created", System.currentTimeMillis() / 1000)
                    .put("model", ModelFiles.MODEL_ID)
                    .put("choices", JSONArray().put(JSONObject()
                        .put("index", 0)
                        .put("message", JSONObject().put("role", "assistant").put("content", answer))
                        .put("finish_reason", "stop")))
                json(Response.Status.OK, response.toString())
            }
        } catch (error: Exception) {
            json(Response.Status.INTERNAL_ERROR, errorBody("inference_error", error.message ?: "حدث خطأ أثناء التوليد"))
        }
    }

    private fun authenticate(session: IHTTPSession) = run {
        val header = session.headers.entries.firstOrNull { it.key.equals("authorization", true) }?.value.orEmpty()
        val scheme = header.substringBefore(' ', "")
        val token = if (scheme.equals("Bearer", ignoreCase = true)) header.substringAfter(' ', "").trim() else ""
        keyStore.authenticate(token)
    }

    private fun streamResponse(prompt: String, system: String, maxTokens: Int): Response {
        val input = PipedInputStream(64 * 1024)
        val output = PipedOutputStream(input)
        val id = "chatcmpl-${UUID.randomUUID()}"
        val created = System.currentTimeMillis() / 1000
        Thread {
            try {
                runBlocking(Dispatchers.IO) {
                    ModelRuntime.generate(prompt, system, maxTokens) { token ->
                        val chunk = JSONObject().put("id", id).put("object", "chat.completion.chunk")
                            .put("created", created).put("model", ModelFiles.MODEL_ID)
                            .put("choices", JSONArray().put(JSONObject().put("index", 0)
                                .put("delta", JSONObject().put("content", token)).put("finish_reason", JSONObject.NULL)))
                        output.write(("data: $chunk\n\n").toByteArray(StandardCharsets.UTF_8))
                        output.flush()
                    }
                }
                val finish = JSONObject().put("id", id).put("object", "chat.completion.chunk")
                    .put("created", created).put("model", ModelFiles.MODEL_ID)
                    .put("choices", JSONArray().put(JSONObject().put("index", 0)
                        .put("delta", JSONObject()).put("finish_reason", "stop")))
                output.write(("data: $finish\n\ndata: [DONE]\n\n").toByteArray(StandardCharsets.UTF_8))
                output.flush()
            } catch (error: Exception) {
                runCatching {
                    val payload = JSONObject().put("error", JSONObject().put("message", error.message ?: "generation failed"))
                    output.write(("data: $payload\n\ndata: [DONE]\n\n").toByteArray(StandardCharsets.UTF_8))
                }
            } finally {
                runCatching { output.close() }
            }
        }.apply { name = "AramAI-Streaming"; isDaemon = true }.start()

        return newChunkedResponse(Response.Status.OK, "text/event-stream; charset=utf-8", input).apply {
            addHeader("Cache-Control", "no-cache")
            addHeader("Connection", "keep-alive")
            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
            addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        }
    }

    private fun unauthorized() = json(Response.Status.UNAUTHORIZED, errorBody("unauthorized", "مفتاح API مفقود أو غير صالح"))

    private fun errorBody(type: String, message: String) = JSONObject()
        .put("error", JSONObject().put("type", type).put("message", message)).toString()

    private fun json(status: Response.IStatus, body: String) =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body).apply {
            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
            addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        }

    companion object {
        private const val MAX_BODY_BYTES = 1_048_576L
    }
}
