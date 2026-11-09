# Aram AI — Android Local Inference

**Aram AI 0.2.0** is an ARM64 Android application that hosts the Spark-X2.6-1.7B GGUF model locally using the official `llama.cpp` Android binding.

## v0.2.0 additions

- Premium dark-violet Arabic RTL navigation with five sections: Home, Chat, Developer, API Keys, Settings.
- A real local chat workspace connected directly to the loaded native inference engine. Responses are shown incrementally as the model emits output.
- Chat modes for general assistance, programming, and analysis; output limit presets; clear conversation; stop generation; copy responses; and visible generation timing.
- A Developer console with current service/model state, live RAM readings, model file size, API endpoint, authentication checks, a real non-streaming generation test, and a separate Server-Sent Events test.
- Foreground service states: STARTING, LOADING_MODEL, STARTING_SERVER, READY, STOPPING, STOPPED and ERROR. Android service-start exceptions and native-load/API-bind failures are surfaced in the UI and notification.
- Lifecycle serialization so stopping during native model loading does not race server startup; partial CPU wake lock is released on stop or failure.
- Version code 2 / version name 0.2.0.

## Included in the project

- Android application with Jetpack Compose and Arabic RTL styling.
- Android foreground service for the local model/API server.
- In-app download of `Spark-X2.6-1.7B-Q4_K_M.gguf` from the XHToken Hugging Face model repository. The download is staged separately and promoted only after minimum-size validation; it is not embedded in the APK or repository.
- Local API on port `8765`: `GET /health`, `GET /v1/models`, and `POST /v1/chat/completions`.
- Bearer API keys with 256-bit random secrets, SHA-256 digest storage, one-time secret display, request counter and revocation.
- Optional LAN binding, disabled by default.
- GitHub Actions workflow to build and publish an ARM64 debug APK artifact.

## Apply this source update to the existing Termux checkout

This ZIP is a complete project source snapshot and intentionally does not contain `.git/` or the large `vendor/llama.cpp/` tree.

1. Download the ZIP to the phone.
2. Extract its contents **directly into the existing `~/AramAI` folder**, choosing overwrite/merge when asked. Keep the existing hidden `.git` folder.
3. In Termux run:

   ```bash
   cd ~/AramAI
   bash PUSH_UPDATE.sh
   ```

4. The script checks the repository path/remote/branch, validates the source resources and shell scripts, creates a normal Git commit, and pushes it to `main`. It does not force-push or recreate Git history.
5. Open GitHub → Actions → `Build Aram AI APK`. Download the artifact named `Aram-AI-ARM64-debug-APK` from the successful run.

**Do not run `UPLOAD_TO_GITHUB.sh` for this existing repository.** That script is reserved for creating a brand-new empty repository. Use `PUSH_UPDATE.sh` for this update.

## Native build

The repository does not include the large `llama.cpp` source tree. GitHub Actions runs `scripts/prepare_native.sh` to fetch the official upstream repository and apply guarded patches for a 2048-token initial context, a 256-token batch, corrected output-token stop accounting, and per-request system/context reset. The script aborts if an expected upstream patch location changes instead of silently producing a partially patched build.

The workflow uses JDK 17, Android API 36, NDK 29.0.13113456, CMake 3.31.6 and Gradle 8.13. The build is ARM64-only (`arm64-v8a`).

## First run on the phone

1. Install the new APK as an update over the previous Aram AI debug build. Its application ID remains `com.aram.ai.debug`, and versionCode is incremented, so app data and downloaded model should be retained.
2. Open Home and start the engine. The status now moves through explicit startup stages; if startup fails, inspect the error shown on the card and in the Developer page.
3. Wait until the card says the engine is ready.
4. Open Chat and send a prompt. The answer should appear incrementally. Try the Programming mode and a short Python request.
5. Open Developer → run the full API test. It checks `/health`, accepts a temporary valid key, rejects an invalid key, then sends a real generation request through `/v1/chat/completions`. A temporary test key is revoked automatically.
6. Run the separate Streaming test to verify the Server-Sent Events endpoint.

The chat composer is wired to `ModelRuntime.generate`, not a fake preview. The Developer screen sends real local HTTP requests to the in-app API. Actual successful inference still depends on the native library, available RAM and the phone's thermal/resource state.

## API quick start

Create a key from the API Keys screen after the engine is ready:

```bash
curl http://127.0.0.1:8765/v1/chat/completions \
  -H 'Authorization: Bearer arai_REPLACE_WITH_YOUR_KEY' \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "spark-x2.6-1.7b",
    "messages": [{"role": "user", "content": "Write a Python hello world."}],
    "stream": false,
    "max_tokens": 128
  }'
```

For streaming set `"stream": true` and read the response as Server-Sent Events. The initial API accepts a small OpenAI-style subset; it is not a complete OpenAI API implementation. Conversation turns are flattened into a transcript before native inference. Tool calls, usage accounting, parallel generations, rate limits, quotas, billing and remote customer accounts are not implemented in this version.

## Device and resource requirements

- Android 13 (API 33) or newer.
- ARM64 (`arm64-v8a`).
- Approx. 1.77 GB model storage, plus temporary download and native runtime memory.
- Initial context is limited to 2048 tokens as a conservative baseline.

The GGUF file size does not equal total RAM demand. The Helio G88 phone must be measured under generation for first-token latency, output speed, peak RAM, thermal throttling and service survival. The app does not guarantee 24/7 availability; Android can stop services due to pressure, battery policy or a force-stop.

## Network and security

- Default listener: `127.0.0.1:8765`.
- LAN mode binds to `0.0.0.0` only when explicitly enabled. It uses HTTP without TLS, so it is only suitable for a trusted private Wi-Fi network.
- Cleartext HTTP is enabled in the app manifest because the local service/API uses HTTP. Do not expose port 8765 directly to the public internet.
- API secrets are high-entropy random tokens; only SHA-256 digests and metadata are persisted. The secret is shown once.
- Public or commercial API access requires a hardened HTTPS gateway, quotas, abuse protection, monitoring and uptime planning. The phone itself is not a cloud host.

## Validation status

The source package includes static source verification. **It has not been built by Gradle inside this workspace, installed, or run against the native model here.** GitHub Actions must produce a successful new APK, and device-side tests must pass before calling the new chat, native generation, API and streaming fully validated. The previous repository build succeeded, but it predates this 0.2.0 update.
