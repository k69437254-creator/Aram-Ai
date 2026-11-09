#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
python3 - <<'PY'
from pathlib import Path
import re
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
    '.github/workflows/android-build.yml',
    'scripts/prepare_native.sh',
    'PUSH_UPDATE.sh',
]
missing = [name for name in required if not (root / name).is_file()]
if missing:
    raise SystemExit('Missing required files: ' + ', '.join(missing))
ET.parse(root / 'app/src/main/AndroidManifest.xml')
ET.parse(root / 'app/src/main/res/drawable/ic_aram_ai.xml')
ET.parse(root / 'app/src/main/res/values/strings.xml')
ET.parse(root / 'app/src/main/res/values/styles.xml')
with (root / 'gradle/libs.versions.toml').open('rb') as f:
    catalog = tomllib.load(f)
assert catalog['versions']['agp'] == '8.13.2'
assert 'androidx-lifecycle-viewmodel-compose' in catalog['libraries']
assert 'androidx-lifecycle-viewmodel-ktx' in catalog['libraries']
manifest = (root / 'app/src/main/AndroidManifest.xml').read_text()
assert 'usesCleartextTraffic="true"' in manifest
assert 'foregroundServiceType="specialUse"' in manifest
main = (root / 'app/src/main/java/com/aram/ai/MainActivity.kt').read_text()
assert 'startForegroundService(context' in main and 'AppPrefs.setEngineState(context, "ERROR"' in main
chat = (root / 'app/src/main/java/com/aram/ai/ui/ChatScreen.kt').read_text()
assert 'viewModel()' in chat and 'chat.sendMessage()' in chat
viewmodel = (root / 'app/src/main/java/com/aram/ai/ui/ChatViewModel.kt').read_text()
assert 'ModelRuntime.generate(' in viewmodel and 'activeJob?.cancel()' in viewmodel
developer = (root / 'app/src/main/java/com/aram/ai/ui/DeveloperScreen.kt').read_text()
assert 'runFullApiTest' in developer and 'ModelRuntime.benchmark()' in developer
runtime = (root / 'app/src/main/java/com/aram/ai/engine/ModelRuntime.kt').read_text()
assert 'instance.state.first' in runtime and 'fun benchmark()' in runtime
assert 'old_init_error' in (root / 'scripts/prepare_native.sh').read_text() and 'old_bench' in (root / 'scripts/prepare_native.sh').read_text()
print(f'Source verification passed: {len(required)} required files, XML resources, Gradle catalog, startup feedback, chat generation, and developer diagnostics.')
PY
bash -n scripts/prepare_native.sh
bash -n scripts/verify_source.sh
bash -n PUSH_UPDATE.sh
