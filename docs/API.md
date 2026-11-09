# Aram AI API (v0.2.0)

Base URL on the phone: `http://127.0.0.1:8765`

All routes except `/health` require:

```http
Authorization: Bearer arai_<secret>
```

## GET `/health`

Returns `status`, `model_loaded` and model ID. It does not return prompts, output text or API key data.

## GET `/v1/models`

Requires a valid Bearer key and returns the current model in a minimal OpenAI-style model list.

## POST `/v1/chat/completions`

Request:

```json
{
  "model": "spark-x2.6-1.7b",
  "messages": [
    {"role": "system", "content": "You are a careful coding assistant."},
    {"role": "user", "content": "Write a Python hello world."}
  ],
  "max_tokens": 128,
  "stream": false
}
```

- `messages` is required; `model` is accepted for client compatibility. This device serves Spark-X2.6-1.7B.
- `max_tokens` is clamped to 32–512 to bound phone workload.
- `stream: false` returns an OpenAI-style `choices[0].message.content` result.
- `stream: true` returns Server-Sent Events (`text/event-stream`), text deltas and a terminal `data: [DONE]` event.
- Messages are flattened into a transcript prompt. Native role-aware templates and tool-call objects are not implemented.
- Each request resets the model's system/context state through the guarded native binding patch; caller-supplied message history is what is passed as context.
- Initial native context is 2048 tokens. Long transcripts can be truncated by the native binding.
- Request body limit: 1 MiB.
- Inference is serialized; simultaneous requests wait for the single native engine.

## Same-device example

```bash
curl http://127.0.0.1:8765/v1/chat/completions \
  -H 'Authorization: Bearer arai_REPLACE_WITH_YOUR_KEY' \
  -H 'Content-Type: application/json' \
  -d '{"model":"spark-x2.6-1.7b","messages":[{"role":"user","content":"Explain a Kotlin coroutine."}],"max_tokens":128,"stream":false}'
```

## LAN clients

Enable **Settings → Local network access** and restart the service. Replace the host with the phone's Wi-Fi IP, for example `http://192.168.1.20:8765`.

The LAN listener uses unencrypted HTTP. Use a trusted private network only. Do not expose this port directly to the internet.
