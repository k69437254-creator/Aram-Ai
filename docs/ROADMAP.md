# Aram AI implementation roadmap

## Included in v0.2.0 source
- Android RTL dark-violet interface with Home, Chat, Developer, API Keys and Settings pages.
- Local native chat connected to the loaded engine with incremental output, three system modes, output-length presets, stop, clear and copy actions.
- Startup state machine and surfaced errors for foreground-service start, model load and API bind.
- Serialized service start/stop lifecycle and service-scoped partial CPU wake lock.
- Spark GGUF downloader with staged completion and minimum-size validation.
- Initial native context/batch tuning (2048 / 256) and per-request context reset patch.
- Local API with hashed API-key storage, authenticated model/chat endpoints, non-streaming and SSE responses.
- Developer diagnostics: RAM, model file, health, API key acceptance/rejection and actual generation/stream tests.
- GitHub Actions ARM64 debug APK workflow.

## Required before external distribution
1. Build v0.2.0 successfully in GitHub Actions.
2. Install on the target Infinix Android 13 / ARM64 device as an update.
3. Confirm engine state, model load and native generation through Chat.
4. Run Developer API tests, including valid/invalid/revoked keys and SSE.
5. Measure peak RAM, first-token latency, sustained speed, temperature and background survival.
6. Evaluate Arabic quality, instruction following, Python/Kotlin tasks and long-context behavior.
7. Add true OpenAI tool-call support before connecting Hermes Agent.
8. Add bounded request queue, rate limits, per-key quotas, audit logs, abuse protection and secure remote gateway before selling API access.
9. Configure a release signing keystore exclusively through encrypted GitHub Actions secrets before publishing a signed release APK.

## Known limits
- Native inference is a single shared model instance; generation requests serialize.
- The API currently flattens role messages into a plain transcript; it is not fully OpenAI-conformant.
- Chat screen calls native inference directly; the Developer page separately validates the actual HTTP API.
- LAN mode is plain HTTP and is disabled by default.
- The smartphone is not guaranteed to be an always-on public API host.
