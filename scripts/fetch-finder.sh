#!/usr/bin/env bash
# Builds the open-vocabulary Find models (YOLOE-11s + MobileCLIP text encoder) into app assets.
# Needs Python 3.11 and ~2 GB of downloads the first time. The ONNX files are git-ignored (>100 MB).
set -euo pipefail
cd "$(dirname "$0")/.."
assets=app/src/main/assets
if [ -f "$assets/finder_yoloe11s.onnx" ] && [ -f "$assets/finder_text.onnx" ]; then echo 'Finder models present.'; exit 0; fi
python=${PYTHON:-python3.11}
[ -x .model-tools/bin/python ] || "$python" -m venv .model-tools
.model-tools/bin/pip install -q "ultralytics>=8.3.150" onnx onnxslim onnxruntime
.model-tools/bin/python scripts/export-finder.py .model-tools/work
.model-tools/bin/python - <<'PY'
from onnxruntime.quantization import quantize_dynamic, QuantType
quantize_dynamic(".model-tools/work/finder_text.onnx", ".model-tools/work/finder_text_int8.onnx", weight_type=QuantType.QInt8)
PY
cp .model-tools/work/finder_yoloe11s.onnx "$assets/finder_yoloe11s.onnx"
cp .model-tools/work/finder_text_int8.onnx "$assets/finder_text.onnx"
echo 'Finder models exported to app assets.'
