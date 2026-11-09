# Aram AI

**Aram AI** is an Android-hosted local inference server for the official Spark-X2.6-1.7B GGUF model. The first delivery targets ARM64 / Android 13+ and uses the official `llama.cpp` Android JNI binding.

## What is included in this first build

- Native Android application with an Arabic, right-to-left, dark-violet dashboard.
- User-started foreground service so the model server can continue while the app screen is closed. A partial CPU wake lock is held only while the service is active so requests can still be handled with the screen off; this increases battery use. Android may still stop the service under system or memory pressure.
- In-app download of the official `Spark-X2.6-1.7B-Q4_K_M.gguf` file from the XHToken Hugging Face repository. New downloads are staged separately and replace the existing model only after reaching the expected minimum size.
- Local HTTP API on port `8765`.
- Optional LAN binding for devices on the same Wi-Fi network. It is disabled by default.
- API key creation, listing, request counters and revocation. Only SHA-256 digests are stored; the secret is shown once.
- `GET /health`, `GET /v1/models`, `POST /v1/chat/completions`; basic Server-Sent Events streaming when `stream: true`.
- GitHub Actions workflow that builds an ARM64 debug APK and uploads it as a workflow artifact.

## Build flow

The repository intentionally does not include the large `llama.cpp` source tree. GitHub Actions fetches the official upstream repository before Gradle configuration, then uses its Android module at `examples/llama.android/lib`. This keeps the Aram AI repository smaller and avoids a manually copied native engine.

On every push to `main` or `master`, open **GitHub → Actions → Build Aram AI APK**. Download the artifact named `Aram-AI-ARM64-debug-APK`; it contains `app-debug.apk`.

Before the build, `scripts/prepare_native.sh` fetches the official `ggml-org/llama.cpp` source and applies three narrowly scoped phone/API patches: a 2048-token initial context with a 256-token batch, correct output-token stopping after prompt truncation, and a clean system-prompt/context reset between API requests. This avoids carrying one customer's conversation into the next request. The upstream revision is recorded in the workflow summary; the patch script intentionally fails rather than silently applying if upstream code changes incompatibly.

## Device requirements

- Android 13 (API 33) or newer.
- ARM64 device (`arm64-v8a`).
- Free storage for the model (approximately 1.77 GB) plus temporary download and runtime headroom.
- The app downloads the model after installation; the GGUF is not embedded in the APK.

The model's advertised context limit is not the context size this phone can practically run. Aram AI starts with a 2048-token context and a 256-token batch as a conservative first setting. The model weights are still close to the phone's available-memory budget; device memory, first-token latency, sustained speed and thermals must be measured on the target phone before offering the service to other users.

## API quick start

Create an API key in the app, start the engine, then call:

```bash
curl http://127.0.0.1:8765/v1/chat/completions \
  -H 'Authorization: Bearer arai_YOUR_KEY' \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "spark-x2.6-1.7b",
    "messages": [{"role": "user", "content": "Write a Python hello world."}],
    "stream": false,
    "max_tokens": 128
  }'
```

For streaming, set `"stream": true` and read the response as Server-Sent Events. `GET /v1/models` requires a Bearer key. `/health` is a local status endpoint and does not expose model output.

### Compatibility scope in this starter

This is an initial OpenAI-style API surface, not a complete OpenAI API implementation. Chat history is flattened into a text transcript before being sent to the model. Token usage accounting, native tool-call schema, parallel generations, rate limits/quotas, billing, remote customer accounts, TLS termination and internet-facing access are not enabled yet. The initial server allows one generation at a time through the underlying inference binding.

## Network and API key safety

- The server binds to `127.0.0.1` by default.
- LAN mode binds to `0.0.0.0` only after the user enables it in Settings. This initial LAN listener uses plain HTTP without TLS: use it only on a trusted private network and never across public Wi-Fi.
- Do **not** expose port `8765` directly to the public internet. A public API product needs a hardened gateway/reverse proxy, HTTPS, abuse controls, quotas and operational monitoring.
- API keys are high-entropy random secrets. Only their SHA-256 digest and non-secret metadata are persisted. Each secret is shown once at creation; copy and store it securely.
- Do not store a customer API key in a mobile app source file or public repository.

## Foreground service note

The service starts only after an explicit user action and presents a persistent notification with a stop action. Android 14+ requires a foreground-service type and Play Console declaration for `specialUse`; Play distribution review may require a documented use case and demo video. The OS can still stop the service, particularly under memory pressure, battery management or force-stop. A foreground service is not a guarantee of 24/7 availability.

## Project dependencies

- Spark-X2.6-1.7B model: Apache-2.0 model series from XHToken.
- Native inference: official `ggml-org/llama.cpp` Android binding and source.
- Embedded HTTP server: NanoHTTPD (Modified BSD).
- Android Jetpack Compose / AndroidX.

Review each upstream project's license and notices before redistribution. The Aram AI application code has no separate license declaration yet; choose one before making this repository public or accepting contributions.

## Current release status

**Starter implementation — first CI/device validation pending.** The project files and workflow are prepared, but no APK has been built in this environment and no on-device inference, sustained background operation, API client, or streaming test has been run yet. Do not describe this package as production-certified or begin selling API access until those tests and the missing service controls are completed.
