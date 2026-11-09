# Aram AI implementation roadmap

## Included in v0.1.0 source package
- Android shell and Arabic RTL dashboard.
- Foreground inference service with a service-scoped partial CPU wake lock (battery impact must be measured).
- Spark GGUF downloader with staged completion and minimum-size validation.
- Initial native context/batch tuning for the phone (2048 / 256) and per-request conversation isolation patch.
- Local API with per-request context reset and hashed API-key storage.
- CI APK artifact workflow.

## Must validate before external distribution
1. GitHub Actions native build completes against the fetched upstream llama.cpp revision.
2. Install APK on the target Infinix Android 13 / ARM64 device.
3. Download and load the official Spark-X2.6-1.7B Q4_K_M file.
4. Measure available RAM, peak memory, first-token latency, sustained tokens/sec, thermals and Android process survival.
5. Test Arabic, Python, Kotlin, multi-file code reasoning and repeated requests.
6. Test API authentication, invalid/revoked keys, non-streaming response, SSE streaming and LAN mode.
7. Fix or document tool-calling support before connecting Hermes Agent.
8. Add bounded request queues, per-key quotas, audit logging, abuse protection and a secure remote gateway before commercial API access.
9. Create a release signing keystore and store it only in GitHub Actions encrypted secrets before producing signed release APKs.

## Important constraints
- The app uses the official Android binding API; it does not embed a full `llama-server` process.
- The initial API flattens conversation turns into one prompt. It is not yet a fully conformant OpenAI API.
- The phone is the inference host, not a guaranteed always-on cloud server.
