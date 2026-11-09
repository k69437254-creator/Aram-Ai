#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
python3 - <<'PY'
from pathlib import Path
import tomllib
import xml.etree.ElementTree as ET

root = Path('.')
required = [
    'app/src/main/AndroidManifest.xml',
    'app/src/main/java/com/aram/ai/MainActivity.kt',
    'app/src/main/java/com/aram/ai/ui/ChatScreen.kt',
    'app/src/main/java/com/aram/ai/ui/ChatViewModel.kt',
    'app/src/main/java/com/aram/ai/ui/DeveloperScreen.kt',
    'app/src/main/java/com/aram/ai/service/ModelService.kt',
    'app/src/main/java/com/aram/ai/engine/ModelRuntime.kt',
    'app/src/main/java/com/aram/ai/server/ApiHttpServer.kt',
    'app/src/main/java/com/aram/ai/data/ApiKeyStore.kt',
    'app/src/main/java/com/aram/ai/data/AppPrefs.kt',
    'app/src/main/java/com/aram/ai/data/ModelFiles.kt',
    'README.md',
    '.github/workflows/android-build.yml',
    'scripts/prepare_native.sh',
    'PUSH_UPDATE.sh',
]
missing = [p for p in required if not (root / p).is_file()]
if missing:
    raise SystemExit('Missing required files: ' + ', '.join(missing))
for xml in (
    'app/src/main/AndroidManifest.xml',
    'app/src/main/res/drawable/ic_aram_ai.xml',
    'app/src/main/res/values/strings.xml',
    'app/src/main/res/values/styles.xml',
):
    ET.parse(root / xml)
with (root / 'gradle/libs.versions.toml').open('rb') as f:
    catalog = tomllib.load(f)
assert catalog['versions']['agp'] == '8.13.2'
assert 'androidx-lifecycle-viewmodel-compose' in catalog['libraries']

build = (root / 'app/build.gradle.kts').read_text()
assert 'versionCode = 6' in build and 'versionName = "0.4.0"' in build
assert '## v0.4.0 additions' in (root / 'README.md').read_text()
chat = (root / 'app/src/main/java/com/aram/ai/ui/ChatScreen.kt').read_text()
vm = (root / 'app/src/main/java/com/aram/ai/ui/ChatViewModel.kt').read_text()
developer = (root / 'app/src/main/java/com/aram/ai/ui/DeveloperScreen.kt').read_text()
assert 'chat.newConversation()' in chat and 'chat.selectConversation(' in chat
assert 'chat.regenerateLast()' in chat and 'firstChunkMs' in chat
assert 'MAX_CONVERSATIONS = 40' in vm and 'persistAll()' in vm
assert 'ModelRuntime.generate(' in vm and 'priority = 0' in vm
assert 'pendingRequests = ModelRuntime.pendingRequestCount()' in developer
assert 'issueClient("اختبار حصة مؤقت", 1)' in developer
assert 'secondBlocked' in developer and 'ownerPass' in developer
assert 'سلوك مفتاح المالك' in developer
assert 'ownerExists = store.ownerKeyExists()' in (root / 'app/src/main/java/com/aram/ai/MainActivity.kt').read_text()
main = (root / 'app/src/main/java/com/aram/ai/MainActivity.kt').read_text()
assert 'الإصدار 0.4.0' in main and 'حصص مفاتيح العملاء اليومية مفعّلة محليًا' in main
assert 'MODE_BUDGETS = mapOf("عادي" to 128, "تحليل" to 500, "برمجة" to 2000)' in vm
assert 'storedModes' in vm and 'storedBudgets' in vm and 'manualBudget' in vm
assert 'MAX_SAFE_OUTPUT_TOKENS = 512' in vm and 'effectiveMaxTokens' in vm
assert 'continueLast()' in vm and 'chat.continueLast()' in chat
assert 'أول مقطع' in chat and 'مقطع/ث' in chat


runtime = (root / 'app/src/main/java/com/aram/ai/engine/ModelRuntime.kt').read_text()
assert 'PriorityQueue' in runtime and 'pending.poll()' in runtime
assert 'GenerationQueueFullException' in runtime and 'MAX_PENDING_REQUESTS = 12' in runtime
assert 'lowestPriority.result.completeExceptionally' in runtime and 'OWNER_PRIORITY = 0' in runtime
assert 'acceptingRequests = false' in runtime and 'generationLock.withLock' in runtime and 'Let the active native call finish cleanly' in runtime
assert 'pendingRequestCount()' in runtime and 'generationLock.withLock' in runtime

keys = (root / 'app/src/main/java/com/aram/ai/data/ApiKeyStore.kt').read_text()
assert 'issueOwnerKey' in keys and 'issueClient' in keys and 'consumeRequest' in keys
assert 'role == "owner"' in keys and 'dailyQuota' in keys
server = (root / 'app/src/main/java/com/aram/ai/server/ApiHttpServer.kt').read_text()
assert 'getRequestStatus(): Int = 429' in server
assert 'if (key.isOwner) OWNER_PRIORITY else CLIENT_PRIORITY' in server
assert 'GenerationQueueFullException' in server and 'Response.Status.SERVICE_UNAVAILABLE' in server
assert 'catch (error: GenerationQueueFullException)' in server
assert 'ModelRuntime.generate(prompt.toString(), systemPrompt, maxTokens, priority)' in server

native = (root / 'scripts/prepare_native.sh').read_text()
assert 'a4d4f2fd4ec85ec6a0a9ece4870416c1b6cc24f5' in native
assert 'enable_thinking' in native and 'chat_template_kwargs' in native
assert 'ARAM_AI_SPARK_THINKING_DISABLED' in native
assert 'int user_prompt_size = (int) user_tokens.size()' in native
assert 'available_user_tokens' in native and 'user_tokens.erase(user_tokens.begin()' in native and '(int) n_predict' in native
assert 'header_tokens = std::min(8, user_prompt_size)' in native and 'removable_tokens' in native
print(f'Source verification passed: {len(required)} required files, XML/Gradle metadata, persistent chat, priority queue, owner/client quota paths, and pinned native template patch.')
PY
bash -n scripts/prepare_native.sh
bash -n scripts/verify_source.sh
bash -n PUSH_UPDATE.sh
