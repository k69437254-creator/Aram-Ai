#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="$ROOT/vendor/llama.cpp"
UPSTREAM_SHA="a4d4f2fd4ec85ec6a0a9ece4870416c1b6cc24f5"
mkdir -p "$ROOT/vendor"

if ! git -C "$TARGET" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  if [[ -e "$TARGET" ]]; then
    echo "vendor/llama.cpp exists but is not a Git checkout; refusing to move or overwrite it." >&2
    exit 1
  fi
  git clone --filter=blob:none https://github.com/ggml-org/llama.cpp.git "$TARGET"
  git -C "$TARGET" fetch --depth 1 origin "$UPSTREAM_SHA"
  git -C "$TARGET" checkout --detach "$UPSTREAM_SHA"
fi
CURRENT_SHA="$(git -C "$TARGET" rev-parse HEAD)"
if [[ "$CURRENT_SHA" != "$UPSTREAM_SHA" ]]; then
  echo "llama.cpp revision mismatch. Expected verified Spark-X2.6-compatible $UPSTREAM_SHA, found $CURRENT_SHA." >&2
  echo "No checkout/reset was performed; preserve the local vendor tree and inspect its revision." >&2
  exit 1
fi

NATIVE_BACKUP="$HOME/AramAI_BACKUPS/native-prepare-$(date +%Y%m%d_%H%M%S)"
mkdir -p "$NATIVE_BACKUP"
NATIVE_FILES=(
  examples/llama.android/lib/src/main/cpp/ai_chat.cpp
  common/chat.h
  common/chat.cpp
  examples/llama.android/lib/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt
  examples/llama.android/lib/src/main/java/com/arm/aichat/InferenceEngine.kt
)
for rel in "${NATIVE_FILES[@]}"; do
  mkdir -p "$NATIVE_BACKUP/$(dirname "$rel")"
  cp -p "$TARGET/$rel" "$NATIVE_BACKUP/$rel"
done
native_rollback() {
  local rc=$?
  trap - ERR
  if [[ "$rc" -ne 0 ]]; then
    for rel in "${NATIVE_FILES[@]}"; do cp -p "$NATIVE_BACKUP/$rel" "$TARGET/$rel"; done
    echo "Native patch failed; exact files restored from $NATIVE_BACKUP" >&2
  fi
  exit "$rc"
}
trap native_rollback ERR

python3 - "$TARGET" "$UPSTREAM_SHA" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
revision = sys.argv[2]
cpp = root / "examples/llama.android/lib/src/main/cpp/ai_chat.cpp"
chat_h = root / "common/chat.h"
chat_cpp = root / "common/chat.cpp"
kotlin = root / "examples/llama.android/lib/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt"
engine_api = root / "examples/llama.android/lib/src/main/java/com/arm/aichat/InferenceEngine.kt"
for path in (cpp, chat_h, chat_cpp, kotlin, engine_api):
    if not path.is_file():
        raise SystemExit(f"Missing expected file in verified llama.cpp {revision}: {path}")

source = cpp.read_text()
marker = "// ARAM_AI_SPARK_THINKING_DISABLED"
if marker not in source:
    for symbol, value in (("DEFAULT_CONTEXT_SIZE", "2048"), ("BATCH_SIZE", "256")):
        source, count = re.subn(rf"constexpr int\s+{symbol}\s*=\s*\d+;", f"constexpr int {symbol} = {value};", source)
        if count != 1:
            raise SystemExit(f"Expected exactly one {symbol}; found {count}")

    old_truncate = """    const int user_prompt_size = (int) user_tokens.size();
    const int max_batch_size = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    if (user_prompt_size > max_batch_size) {
        const int skipped_tokens = user_prompt_size - max_batch_size;
        user_tokens.resize(max_batch_size);
        LOGw("%s: User prompt too long! Skipped %d tokens!", __func__, skipped_tokens);
    }
"""
    new_truncate = """    int user_prompt_size = (int) user_tokens.size();
    const int available_user_tokens = std::max(1, DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM -
                                                 (int) current_position - std::max(0, (int) n_predict));
    if (user_prompt_size > available_user_tokens) {
        const int skipped_tokens = user_prompt_size - available_user_tokens;
        // Preserve the initial template marker, trimming the older transcript after it.
        const int header_tokens = std::min(8, user_prompt_size);
        const int removable_tokens = std::min(skipped_tokens, std::max(0, user_prompt_size - header_tokens));
        user_tokens.erase(user_tokens.begin() + header_tokens,
                          user_tokens.begin() + header_tokens + removable_tokens);
        user_prompt_size = (int) user_tokens.size();
        LOGw("%s: Trimmed %d older prompt tokens while preserving the message header and latest turn", __func__, removable_tokens);
    }
"""
    if "available_user_tokens" not in source:
        if source.count(old_truncate) != 1:
            raise SystemExit("The truncation block differs from the verified revision; refusing an unsafe partial patch")
        source = source.replace(old_truncate, new_truncate, 1)

    old_stop = "    stop_generation_position = current_position + user_prompt_size + n_predict;"
    new_stop = "    stop_generation_position = current_position + n_predict;"
    if old_stop in source:
        source = source.replace(old_stop, new_stop, 1)
    elif new_stop not in source:
        raise SystemExit("Native stop-position expression changed; refusing an unverified patch")

    call_pattern = re.compile(
        r"auto formatted = common_chat_format_single\(\s*"
        r"g_chat_templates\.get\(\),\s*chat_msgs,\s*new_msg,\s*role == ROLE_USER,\s*"
        r"/\*\s*use_jinja\s*\*/\s*(?:true|false)(?:\s*,[^;]*)?\);", re.S)
    desired_call = """auto formatted = common_chat_format_single(
            g_chat_templates.get(), chat_msgs, new_msg, role == ROLE_USER,
            /* use_jinja */ true, {{"enable_thinking", "false"}});"""
    matches = list(call_pattern.finditer(source))
    if len(matches) == 1:
        source = call_pattern.sub(desired_call, source, count=1)
    elif source.count('common_chat_format_single(\n            g_chat_templates.get(), chat_msgs, new_msg, role == ROLE_USER,') != 1:
        raise SystemExit("Could not uniquely locate Android chat formatting call")
    if '#include <exception>' not in source:
        anchor = '#include <cmath>\n'
        if source.count(anchor) != 1:
            raise SystemExit("Cannot locate native C++ include section for exception handling")
        source = source.replace(anchor, anchor + '#include <exception>\n', 1)

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
    if old_system in source:
        source = source.replace(old_system, new_system, 1)
    elif "Chat template formatting failed" not in source:
        raise SystemExit("System prompt error-handling block changed; refusing a partial native patch")

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
    if old_user in source:
        source = source.replace(old_user, new_user, 1)
    elif "User prompt template formatting failed" not in source:
        raise SystemExit("User prompt error-handling block changed; refusing a partial native patch")
    if "#include <algorithm>" not in source:
        anchor = "#include <cmath>\n"
        if source.count(anchor) == 1:
            source = source.replace(anchor, anchor + "#include <algorithm>\n", 1)
    source = source.replace("/**\n * Completion loop's long-term states:", marker + "\n/**\n * Completion loop's long-term states:", 1)
    cpp.write_text(source)

# The upstream structure already exposes chat_template_kwargs in Jinja inputs.
# This small adapter passes enable_thinking=false through the extra-context map,
# which overrides the template's default true value.
h = chat_h.read_text()
h_marker = "// ARAM_AI_CHAT_TEMPLATE_KWARGS"

header_pattern = re.compile(
    r"(std::string\s+common_chat_format_single\s*\(\s*"
    r"const struct common_chat_templates\s*\*\s*tmpls\s*,\s*"
    r"const std::vector<common_chat_msg>\s*&\s*past_msg\s*,\s*"
    r"const common_chat_msg\s*&\s*new_msg\s*,\s*"
    r"bool\s+add_ass\s*,\s*bool\s+use_jinja)"
    r"(?:\s*,\s*const std::map<std::string,\s*std::string>\s*&"
    r"\s*chat_template_kwargs\s*=\s*\{\})?"
    r"(\s*\);)", re.S
)

header_matches = list(header_pattern.finditer(h))
if len(header_matches) != 1:
    raise SystemExit(
        f"Header signature count mismatch: {len(header_matches)}"
    )

if "chat_template_kwargs" not in header_matches[0].group(0):
    h = header_pattern.sub(
        r"\1, const std::map<std::string, std::string> & chat_template_kwargs = {}\2",
        h, count=1
    )

if "#include <map>" not in h:
    raise SystemExit("Missing <map> include in common/chat.h")

header_matches = list(header_pattern.finditer(h))
if len(header_matches) != 1 or "chat_template_kwargs" not in header_matches[0].group(0):
    raise SystemExit("Header signature verification failed")

if h_marker not in h:
    pos = header_matches[0].start()
    h = h[:pos] + h_marker + "\n" + h[pos:]

cc = chat_cpp.read_text()
cc_marker = "// ARAM_AI_CHAT_TEMPLATE_KWARGS"

implementation_pattern = re.compile(
    r"(std::string\s+common_chat_format_single\s*\(\s*"
    r"const struct common_chat_templates\s*\*\s*tmpls\s*,\s*"
    r"const std::vector<common_chat_msg>\s*&\s*past_msg\s*,\s*"
    r"const common_chat_msg\s*&\s*new_msg\s*,\s*"
    r"bool\s+add_ass\s*,\s*bool\s+use_jinja)"
    r"(?:\s*,\s*const std::map<std::string,\s*std::string>\s*&"
    r"\s*chat_template_kwargs)?"
    r"(\s*\)\s*\{)", re.S
)

implementation_matches = list(implementation_pattern.finditer(cc))
if len(implementation_matches) != 1:
    raise SystemExit(
        f"Implementation signature count mismatch: {len(implementation_matches)}"
    )

if "chat_template_kwargs" not in implementation_matches[0].group(0):
    cc = implementation_pattern.sub(
        r"\1, const std::map<std::string, std::string> & chat_template_kwargs\2",
        cc, count=1
    )

implementation_matches = list(implementation_pattern.finditer(cc))
if len(implementation_matches) != 1:
    raise SystemExit("Implementation signature verification failed")

func_start = implementation_matches[0].start()
func_end = cc.find(
    "\nstd::string common_chat_format_example",
    implementation_matches[0].end()
)

if func_end < 0:
    raise SystemExit("Could not identify the end of the target function")

function_body = cc[func_start:func_end]
assignment = "    inputs.chat_template_kwargs = chat_template_kwargs;"

if assignment not in function_body:
    anchor = "    inputs.use_jinja = use_jinja;"
    if function_body.count(anchor) != 1:
        raise SystemExit("Cannot uniquely locate use_jinja initialization")

    function_body = function_body.replace(
        anchor, anchor + "\n" + assignment, 1
    )
    cc = cc[:func_start] + function_body + cc[func_end:]

if cc_marker not in cc:
    pos = cc.index("std::string common_chat_format_single(")
    cc = cc[:pos] + cc_marker + "\n" + cc[pos:]

# Final checks before writing either file.
hm = list(header_pattern.finditer(h))
im = list(implementation_pattern.finditer(cc))

if len(hm) != 1 or "chat_template_kwargs" not in hm[0].group(0):
    raise SystemExit("Final header check failed")

if len(im) != 1 or "chat_template_kwargs" not in im[0].group(0):
    raise SystemExit("Final implementation check failed")

fs = cc.index("std::string common_chat_format_single(")
fe = cc.find("\nstd::string common_chat_format_example", fs)

if fe < 0 or assignment not in cc[fs:fe]:
    raise SystemExit("Final kwargs forwarding check failed")

chat_h.write_text(h)
chat_cpp.write_text(cc)

print("PASS: exact header signature repaired.")
print("PASS: exact implementation signature repaired.")
print("PASS: chat_template_kwargs forwarded into template inputs.")
print("PASS: existing markers handled independently of signatures.")


# Recover the native wrapper's ready state after errors/cancellation.
k = kotlin.read_text()
if "ARAM_AI_READY_STATE_PATCH" not in k:
    def apply_once_or_accept(old, new, label):
        new_count = k.count(new)
        old_count = k.count(old)
        if new_count == 1:
            # Some old anchors are prefixes of their replacement. Remove the
            # accepted replacement before checking for any remaining pristine copy.
            remaining_old = k.replace(new, "", 1).count(old)
            if remaining_old == 0:
                return k
            raise SystemExit(
                f"Ambiguous Kotlin target for {label}: already-patched={new_count}, "
                f"additional-pristine={remaining_old}"
            )
        if new_count == 0 and old_count == 1:
            return k.replace(old, new, 1)
        raise SystemExit(
            f"Expected exactly one pristine or already-patched Kotlin target: "
            f"{label} (old={old_count}, new={new_count})"
        )

    old_init = '''            } catch (e: Exception) {
                Log.e(TAG, "Failed to load native library", e)
                throw e
            }
'''
    new_init = '''            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load native library", e)
                _state.value = InferenceEngine.State.Error(
                    e as? Exception ?: RuntimeException("Native initialization failed", e)
                )
            }
'''
    k = apply_once_or_accept(old_init, new_init, "native initialization error handler")

    changes = [
        ('            Log.i(TAG, "System prompt processed! Awaiting user prompt...")\n            _state.value = InferenceEngine.State.ModelReady\n',
         '            Log.i(TAG, "System prompt processed! Awaiting user prompt...")\n            _state.value = InferenceEngine.State.ModelReady\n            _readyForSystemPrompt = true\n', "system prompt ready reset"),
        ('            _state.value = InferenceEngine.State.ModelReady\n        } catch (e: CancellationException) {',
         '            _state.value = InferenceEngine.State.ModelReady\n            _readyForSystemPrompt = true\n        } catch (e: CancellationException) {', "generation ready reset"),
        ('            _state.value = InferenceEngine.State.ModelReady\n            throw e\n        } catch (e: Exception) {',
         '            _state.value = InferenceEngine.State.ModelReady\n            _readyForSystemPrompt = true\n            throw e\n        } catch (e: Exception) {', "cancel ready reset"),
        ('                    Log.e(TAG, "Failed to process user prompt: $result")\n                    return@flow',
         '                    Log.e(TAG, "Failed to process user prompt: $result")\n                    _state.value = InferenceEngine.State.ModelReady\n                    _readyForSystemPrompt = true\n                    return@flow', "failed request state reset")
    ]
    for old, new, label in changes:
        k = apply_once_or_accept(old, new, label)

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
    k = apply_once_or_accept(old_bench, new_bench, "benchmark lifecycle block")

    class_anchor = "internal class InferenceEngineImpl private constructor("
    if k.count(class_anchor) != 1:
        raise SystemExit("Cannot uniquely mark Kotlin ready-state patch")
    k = k.replace(class_anchor, "// ARAM_AI_READY_STATE_PATCH\n" + class_anchor, 1)
    kotlin.write_text(k)

# Add a public, per-request cancellation hook to the pinned Android binding.
# ModelRuntime calls beginGeneration() at the serialized request boundary and
# cancelGeneration() only for that request's active native flow.
api = engine_api.read_text()
api_marker = "// ARAM_AI_CANCEL_GENERATION_API"
if api_marker not in api:
    api_anchor = "    fun sendUserPrompt(message: String, predictLength: Int = DEFAULT_PREDICT_LENGTH): Flow<String>\n"
    if api.count(api_anchor) != 1:
        raise SystemExit("Cannot uniquely locate InferenceEngine.sendUserPrompt")
    api = api.replace(
        api_anchor,
        "    /** Reset the cancellation flag before one serialized generation. */\n    fun beginGeneration()\n\n    /** Stop the active generation after the current native decode step. */\n    fun cancelGeneration()\n\n" + api_marker + "\n" + api_anchor,
        1
    )
if api.count("fun beginGeneration()") != 1 or api.count("fun cancelGeneration()") != 1:
    raise SystemExit("InferenceEngine cancellation API verification failed")
engine_api.write_text(api)

impl = kotlin.read_text()
impl_marker = "// ARAM_AI_CANCEL_GENERATION_PATCH"
if impl_marker not in impl:
    send_anchor = "    override fun sendUserPrompt(\n"
    if impl.count(send_anchor) != 1:
        raise SystemExit("Cannot uniquely locate InferenceEngineImpl.sendUserPrompt")
    cancel_methods = """    override fun beginGeneration() {
        _cancelGeneration = false
    }

    override fun cancelGeneration() {
        _cancelGeneration = true
    }

"""
    impl = impl.replace(send_anchor, impl_marker + "\n" + cancel_methods + send_anchor, 1)
if impl.count("override fun beginGeneration()") != 1 or impl.count("override fun cancelGeneration()") != 1:
    raise SystemExit("InferenceEngineImpl native cancellation verification failed")
reset_start = impl.find("override fun beginGeneration()")
reset_end = impl.find("override fun cancelGeneration()", reset_start)
if reset_start < 0 or reset_end < 0 or "_cancelGeneration = false" not in impl[reset_start:reset_end]:
    raise SystemExit("Native per-request cancellation reset verification failed")
if "@Volatile\n    private var _cancelGeneration = false" not in impl:
    raise SystemExit("Native cancellation flag is not volatile in the pinned binding")
kotlin.write_text(impl)

assert marker in cpp.read_text()
assert h_marker in chat_h.read_text()
assert cc_marker in chat_cpp.read_text()
assert "_readyForSystemPrompt = true" in kotlin.read_text()
assert "fun beginGeneration()" in engine_api.read_text() and "fun cancelGeneration()" in engine_api.read_text()
assert "override fun beginGeneration()" in kotlin.read_text() and "override fun cancelGeneration()" in kotlin.read_text()
print(f"Patched Spark-X2.6 Jinja handling without changing the verified llama.cpp revision {revision}")
PY
