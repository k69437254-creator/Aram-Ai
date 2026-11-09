#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v git >/dev/null 2>&1; then
  echo "Git غير مثبت. ثبّته أولًا من Termux باستخدام: pkg install git"
  exit 1
fi
if [ -d .git ]; then
  echo "هذا المجلد مرتبط مسبقًا بمستودع Git. أوقف التنفيذ لتجنب تغيير تاريخه."
  exit 1
fi

read -r -p "الصق رابط مستودع GitHub الفارغ (HTTPS أو SSH): " REPO
REPO="$(printf '%s' "$REPO" | tr -d '\r' | xargs)"
[[ "$REPO" == github.com/* ]] && REPO="https://$REPO"

if [[ ! "$REPO" =~ ^https://github\.com/[^/]+/[^/]+/?$ && ! "$REPO" =~ ^git@github\.com:[^/]+/[^/]+\.git$ ]]; then
  echo "رابط GitHub غير صالح: تحقق من الرابط."
  exit 1
fi
REPO="${REPO%/}.git"
read -r -p "اسم صاحب التعديل (Git author): " AUTHOR
read -r -p "بريد Git author: " EMAIL
if [[ -z "$AUTHOR" || -z "$EMAIL" ]]; then
  echo "الاسم والبريد مطلوبان لإنشاء سجل Git."
  exit 1
fi

git init -b main
git add .
git -c user.name="$AUTHOR" -c user.email="$EMAIL" commit -m "Initial Aram AI Android project"
git remote add origin "$REPO"
git push -u origin main

echo
echo "تم رفع Aram AI. افتح GitHub ثم Actions وشغّل/راقب Build Aram AI APK."
