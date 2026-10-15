#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
model=app/src/main/assets/yolov8n.onnx
mkdir -p "$(dirname "$model")"
if command -v shasum >/dev/null 2>&1; then
  checksum=(shasum -a 256)
else
  checksum=(sha256sum)
fi
expected=505648ada344cd9f3f31e51d49c489c070819bc96cc758258a8fd51488e00579
if [ -f "$model" ] && printf '%s  %s\n' "$expected" "$model" | "${checksum[@]}" -c - >/dev/null 2>&1; then
  echo 'YOLO model already verified.'
  exit 0
fi
temp=$(mktemp)
trap 'rm -f "$temp"' EXIT
curl --fail --location --retry 2 'https://raw.githubusercontent.com/Hyuto/yolov8-onnxruntime-web/fc4a52c466d15ad4519873a0cef22fbc935b93b6/public/model/yolov8n.onnx' -o "$temp"
printf '%s  %s\n' "$expected" "$temp" | "${checksum[@]}" -c -
mv "$temp" "$model"
