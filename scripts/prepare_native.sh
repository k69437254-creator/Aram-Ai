#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="$ROOT/vendor/llama.cpp"
if [[ ! -f "$TARGET/examples/llama.android/lib/src/main/cpp/CMakeLists.txt" ]]; then
  mkdir -p "$ROOT/vendor"
  rm -rf "$TARGET"
  git clone --depth 1 https://github.com/ggml-org/llama.cpp.git "$TARGET"
fi

# Tune the upstream Android example for a memory-constrained phone and make
# each API request start from a clean chat context instead of sharing history.
python3 - "$TARGET" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
cpp = root / "examples/llama.android/lib/src/main/cpp/ai_chat.cpp"
kotlin = root / "examples/llama.android/lib/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt"

source = cpp.read_text()
for symbol, value in (("DEFAULT_CONTEXT_SIZE", "2048"), ("BATCH_SIZE", "256")):
    pattern = rf"constexpr int\s+{symbol}\s*=\s*\d+;"
    source, count = re.subn(pattern, f"constexpr int {symbol} = {value};", source)
    if count != 1:
        raise SystemExit(f"Expected one {symbol} constant in {cpp}, found {count}")
# Keep token accounting correct when a prompt is truncated to the phone-sized context.
old_cpp = """    const int user_prompt_size = (int) user_tokens.size();
    const int max_batch_size = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    if (user_prompt_size > max_batch_size) {
        const int skipped_tokens = user_prompt_size - max_batch_size;
        user_tokens.resize(max_batch_size);
        LOGw("%s: User prompt too long! Skipped %d tokens!", __func__, skipped_tokens);
    }
"""
new_cpp = """    int user_prompt_size = (int) user_tokens.size();
    const int max_batch_size = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    if (user_prompt_size > max_batch_size) {
        const int skipped_tokens = user_prompt_size - max_batch_size;
        user_tokens.resize(max_batch_size);
        user_prompt_size = (int) user_tokens.size();
        LOGw("%s: User prompt too long! Skipped %d tokens!", __func__, skipped_tokens);
    }
"""
if source.count(old_cpp) != 1:
    raise SystemExit("Expected one user-prompt truncation block in ai_chat.cpp")
source = source.replace(old_cpp, new_cpp, 1)
old_stop = "    stop_generation_position = current_position + user_prompt_size + n_predict;"
if source.count(old_stop) != 1:
    raise SystemExit("Expected one generation stop-position calculation in ai_chat.cpp")
source = source.replace(old_stop, "    stop_generation_position = current_position + n_predict;", 1)
cpp.write_text(source)

source = kotlin.read_text()
patches = [
    (
        '            Log.i(TAG, "System prompt processed! Awaiting user prompt...")\n'
        '            _state.value = InferenceEngine.State.ModelReady\n',
        '            Log.i(TAG, "System prompt processed! Awaiting user prompt...")\n'
        '            _state.value = InferenceEngine.State.ModelReady\n'
        '            _readyForSystemPrompt = true\n',
        "system-prompt reset readiness",
    ),
    (
        '            _state.value = InferenceEngine.State.ModelReady\n'
        '        } catch (e: CancellationException) {',
        '            _state.value = InferenceEngine.State.ModelReady\n'
        '            _readyForSystemPrompt = true\n'
        '        } catch (e: CancellationException) {',
        "normal generation reset readiness",
    ),
    (
        '            _state.value = InferenceEngine.State.ModelReady\n'
        '            throw e\n'
        '        } catch (e: Exception) {',
        '            _state.value = InferenceEngine.State.ModelReady\n'
        '            _readyForSystemPrompt = true\n'
        '            throw e\n'
        '        } catch (e: Exception) {',
        "cancelled generation reset readiness",
    ),
    (
        '                    Log.e(TAG, "Failed to process user prompt: $result")\n'
        '                    return@flow',
        '                    Log.e(TAG, "Failed to process user prompt: $result")\n'
        '                    _state.value = InferenceEngine.State.ModelReady\n'
        '                    _readyForSystemPrompt = true\n'
        '                    return@flow',
        "failed prompt state recovery",
    ),
]
for old, new, label in patches:
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"Expected one patch target for {label}, found {count}")
    source = source.replace(old, new, 1)
kotlin.write_text(source)
print("Applied Aram AI mobile-memory and per-request context patches.")
PY

printf 'Prepared official llama.cpp at %s\n' "$(git -C "$TARGET" rev-parse --short HEAD)"
