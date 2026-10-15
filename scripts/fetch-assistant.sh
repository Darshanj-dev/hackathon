#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .assistant-models
model=.assistant-models/gemma-4-E2B-it.litertlm
expected=181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c
if command -v shasum >/dev/null 2>&1; then checksum=(shasum -a 256); else checksum=(sha256sum); fi
if [ -f "$model" ] && printf '%s  %s\n' "$expected" "$model" | "${checksum[@]}" -c - >/dev/null 2>&1; then
  echo 'Assistant installation data verified.'
  exit 0
fi
echo 'Preparing assistant installation data on this development computer (one-time 2.59 GB transfer).'
curl --fail --location --retry 2 --continue-at - 'https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm' -o "$model.partial"
printf '%s  %s\n' "$expected" "$model.partial" | "${checksum[@]}" -c -
mv "$model.partial" "$model"
