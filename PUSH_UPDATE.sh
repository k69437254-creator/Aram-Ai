#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

if [[ ! -d .git ]]; then
  echo "لم يتم العثور على .git. فك ضغط هذا التحديث فوق مجلد ~/AramAI الحالي الذي يحتوي مستودع Git."
  exit 1
fi
if [[ "$(git rev-parse --show-toplevel)" != "$ROOT" ]]; then
  echo "أوقف التنفيذ: مجلد العمل ليس جذر مستودع Git المتوقع."
  exit 1
fi
BRANCH="$(git branch --show-current)"
if [[ "$BRANCH" != "main" && "$BRANCH" != "master" ]]; then
  echo "الفرع الحالي هو $BRANCH وليس main أو master. لم يتم الرفع."
  exit 1
fi
REMOTE="$(git remote get-url origin 2>/dev/null || true)"
if [[ "$REMOTE" != *"github.com/k69437254-creator/Aram-Ai"* ]]; then
  echo "عنوان origin غير متوقع: $REMOTE"
  echo "لم يتم تغيير المستودع أو رفع الملفات."
  exit 1
fi
if [[ -f .git/MERGE_HEAD || -d .git/rebase-merge || -d .git/rebase-apply ]]; then
  echo "يوجد دمج أو rebase غير مكتمل. عالجه أولًا ثم أعد تشغيل السكربت."
  exit 1
fi

bash scripts/verify_source.sh

git add -A
if git diff --cached --quiet; then
  echo "لا توجد تغييرات جديدة لرفعها."
  exit 0
fi

git commit -m "Add chat workspace and developer diagnostics"
git push origin "$BRANCH"
echo
echo "تم رفع تحديث Aram AI بنجاح. راقب GitHub Actions لبناء APK جديد."
echo "Commit: $(git rev-parse --short HEAD)"
