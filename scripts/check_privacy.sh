#!/usr/bin/env bash
# 저장소를 공개/공유하기 전에 개인정보 문자열이 남아 있는지 검사한다.
# 패턴은 커밋하지 않는다: scripts/privacy_patterns.local 파일(한 줄에 정규식 하나)을 로컬에서 만들어 채운다.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PATTERNS="$ROOT/scripts/privacy_patterns.local"

if [[ ! -s "$PATTERNS" ]]; then
  echo "패턴 파일이 비어 있습니다: $PATTERNS" >&2
  echo "실명, 전화번호, 이메일 등을 한 줄에 하나씩 정규식으로 적어 주세요." >&2
  exit 2
fi

found=0
while IFS= read -r pattern; do
  [[ -z "$pattern" || "$pattern" == \#* ]] && continue
  if git -C "$ROOT" grep -nIE -e "$pattern" -- . ':!scripts/privacy_patterns.local'; then
    found=1
  fi
  if git -C "$ROOT" log --all --format='%an %ae %cn %ce %B' | grep -qE -e "$pattern"; then
    echo "[커밋 메타데이터] 패턴 일치: $pattern"
    found=1
  fi
done < "$PATTERNS"

if [[ $found -eq 1 ]]; then
  echo "개인정보로 의심되는 문자열이 있습니다." >&2
  exit 1
fi
echo "개인정보 패턴 없음."
