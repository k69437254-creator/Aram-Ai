#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="$ROOT/vendor/llama.cpp"
if [[ ! -f "$TARGET/examples/llama.android/lib/src/main/cpp/CMakeLists.txt" ]]; then
  mkdir -p "$ROOT/vendor"
  rm -rf "$TARGET"
  git clone --depth 1 https://github.com/ggml-org/llama.cpp.git "$TARGET"
fi

# Tune the upstream Android example for the target phone and isolate each API request.
python3 - "$TARGET" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
cpp = root / "examples/llama.android/lib/src/main/cpp/ai_chat.cpp"
kotlin = root / "examples/llama.android/lib/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt"
if not cpp.is_file() or not kotlin.is_file():
    raise SystemExit("The expected official llama.cpp Android binding files were not found; refusing a partial native patch.")

source = cpp.read_text()
for symbol, value in (("DEFAULT_CONTEXT_SIZE", "2048"), ("BATCH_SIZE", "256")):
    pattern = rf"constexpr int\s+{symbol}\s*=\s*\d+;"
    source, count = re.subn(pattern, f"constexpr int {symbol} = {value};", source)
    if count != 1:
        raise SystemExit(f"Expected one {symbol} constant in {cpp}, found {count}")

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

# Enable Jinja for custom model chat templates.
old_jinja = 'g_chat_templates.get(), chat_msgs, new_msg, role == ROLE_USER, /* use_jinja */ false);'
new_jinja = 'g_chat_templates.get(), chat_msgs, new_msg, role == ROLE_USER, /* use_jinja */ true);'
if source.count(old_jinja) != 1:
    raise SystemExit("Expected one chat-template Jinja call")
source = source.replace(old_jinja, new_jinja, 1)

if '#include <exception>' not in source:
    include_anchor = '#include <cmath>\n'
    if source.count(include_anchor) != 1:
        raise SystemExit("Cannot locate C++ include section")
    source = source.replace(
        include_anchor,
        include_anchor + '#include <exception>\n',
        1
    )

old_system = """    if (has_chat_template) {
        formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt);
    }
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);
"""
new_system = """    if (has_chat_template) {
        try {
            formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt);
        } catch (const std::exception & e) {
            env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);
            LOGe("%s: Chat template formatting failed: %s", __func__, e.what());
            return 3;
        } catch (...) {
            env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);
            LOGe("%s: Unknown chat template formatting failure", __func__);
            return 3;
        }
    }
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);
"""
if source.count(old_system) != 1:
    raise SystemExit("Expected one system-prompt formatting block")
source = source.replace(old_system, new_system, 1)

old_user = """    if (has_chat_template) {
        formatted_user_prompt = chat_add_and_format(ROLE_USER, user_prompt);
    }
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);
"""
new_user = """    if (has_chat_template) {
        try {
            formatted_user_prompt = chat_add_and_format(ROLE_USER, user_prompt);
        } catch (const std::exception & e) {
            env->ReleaseStringUTFChars(juser_prompt, user_prompt);
            LOGe("%s: User prompt template formatting failed: %s", __func__, e.what());
            return 3;
        } catch (...) {
            env->ReleaseStringUTFChars(juser_prompt, user_prompt);
            LOGe("%s: Unknown user prompt template formatting failure", __func__);
            return 3;
        }
    }
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);
"""
if source.count(old_user) != 1:
    raise SystemExit("Expected one user-prompt formatting block")
source = source.replace(old_user, new_user, 1)

cpp.write_text(source)

source = kotlin.read_text()
old_init_error = '            } catch (e: Exception) {\n                Log.e(TAG, "Failed to load native library", e)\n                throw e\n            }\n'
new_init_error = '            } catch (e: Throwable) {\n                Log.e(TAG, "Failed to load native library", e)\n                _state.value = InferenceEngine.State.Error(\n                    e as? Exception ?: RuntimeException("Native initialization failed", e)\n                )\n            }\n'
if source.count(old_init_error) != 1:
    raise SystemExit("Expected one native-library initialization error handler")
source = source.replace(old_init_error, new_init_error, 1)
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

old_bench = '''            _readyForSystemPrompt = false   // Just to be safe
            _state.value = InferenceEngine.State.Benchmarking
            benchModel(pp, tg, pl, nr).also {
                _state.value = InferenceEngine.State.ModelReady
            }
'''
new_bench = '''            _readyForSystemPrompt = false   // Just to be safe
            _state.value = InferenceEngine.State.Benchmarking
            try {
                benchModel(pp, tg, pl, nr)
            } finally {
                _state.value = InferenceEngine.State.ModelReady
                _readyForSystemPrompt = true
            }
'''
if source.count(old_bench) != 1:
    raise SystemExit("Expected one benchmark lifecycle block in InferenceEngineImpl.kt")
source = source.replace(old_bench, new_bench, 1)
kotlin.write_text(source)
print("Applied Aram AI phone-memory and per-request context patches.")
PY

printf 'Prepared official llama.cpp at %s\n' "$(git -C "$TARGET" rev-parse --short HEAD)"
