# Aram AI API (v0.1)

Base URL on the phone: `http://127.0.0.1:8765`

All routes except `/health` require:

```http
Authorization: Bearer arai_<secret>
```

## GET `/health`

Returns server readiness. This route does not return prompts, outputs, or key information.

## GET `/v1/models`

Returns the active model in an OpenAI-style model list.

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

- `model` is accepted for client compatibility; this device serves Spark-X2.6-1.7B.
- `messages` is required.
- `max_tokens` is clamped to 32–512 in this starter to limit device load.
- `stream: true` returns Server-Sent Events (`text/event-stream`) and ends with `data: [DONE]`.
- `stream: false` returns an OpenAI-style `choices[0].message.content` response.
- Conversation turns are currently flattened into a text transcript. Native role-aware message formatting and tool-call objects are not yet implemented.
- The native context is reset for every request, and that request's supplied messages are passed as the conversation transcript. This keeps separate API calls from inheriting another caller's conversation.
- The initial native context is set to 2048 tokens to reduce memory pressure on the target phone; long histories are therefore truncated by the native binding.
- Request bodies above 1 MiB are rejected.

## Example (same phone)

```bash
curl http://127.0.0.1:8765/v1/chat/completions \
  -H 'Authorization: Bearer arai_REPLACE_WITH_YOUR_KEY' \
  -H 'Content-Type: application/json' \
  -d '{"model":"spark-x2.6-1.7b","messages":[{"role":"user","content":"Explain a Kotlin coroutine."}],"max_tokens":160}'
```

## LAN clients

Enable **Settings → Local network access** in the app, then restart the service. Use the phone's LAN IP as the host, for example `http://192.168.1.20:8765`.

The v0.1 LAN listener is plain HTTP. It is suitable only for a trusted private Wi-Fi network. Do not send API keys across public Wi-Fi and do not expose this port directly to the internet.
