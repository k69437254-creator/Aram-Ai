# Aram AI — Android Local Inference

**Aram AI 0.4.0** is an ARM64 Android application that hosts the Spark-X2.6-1.7B GGUF model locally using the official `llama.cpp` Android binding.

## v0.4.0 additions

- Persistent independent chat conversations: create, select, delete, clear, restore after process restart, and regenerate the most recent assistant response.
- Improved chat feedback with first-chunk latency, incremental stream updates, stop handling, and explicit interrupted-generation recovery.
- Three user-selectable task presets: ordinary (128 requested tokens), analysis (500), and programming (2,000); mode and budget are saved independently per conversation and can be manually overridden.
- The app explicitly reports requested versus effective output budget. The effective cap stays at 512 until a larger context window and device memory/thermal behavior are measured; it does not claim that a 2,000-token run has been validated.
- A continuation action submits a follow-up using the previous conversation, instead of replaying the original user request. Continuation is user-triggered because the current native API does not expose a reliable stop-reason/token-count signal.
- Spark-X2.6 thinking disabled through the native Jinja `enable_thinking=false` template argument rather than post-processing generated text.
- Corrected native output-stop accounting and reserved context space for the requested output while trimming older transcript content without dropping the opening chat-template marker.
- A single serialized native generation queue with owner/local priority, bounded waiting capacity, and customer-request back-pressure. Priority affects waiting requests only; stopping the service drains the active native request before unloading to avoid cancelling JNI work mid-call.
- One owner API key without a daily request quota, plus separate customer API keys with configurable daily quotas (100, 500, 1,000 or 5,000 requests).
- Developer diagnostics now show queued requests and include quota/owner acceptance checks.
- Keep the Spark-X2.6-compatible llama.cpp source revision already verified in this checkout; the updater no longer downgrades the submodule to an older incompatible commit.

## Earlier v0.2.0 additions

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
