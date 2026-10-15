#!/usr/bin/env python3
"""Export the open-vocabulary finder used by Saathi's Find mode.

Produces two ONNX files:
  finder_yoloe11s.onnx   image [1,3,640,640] + text [1,N,512] -> [1,4+N,8400] (xywh pixels, sigmoid scores)
  finder_text.onnx       CLIP tokens [N,77] int32 -> raw MobileCLIP-BLT text features [N,512]

The text embedding is a model input, so the phone can search for any phrase typed or spoken by the user.
Run from the repo root with the export tooling venv:  .model-tools/bin/python scripts/export-finder.py
Model: Ultralytics YOLOE-11s (AGPL-3.0, same licence family as the bundled YOLOv8n).
"""
import os
import sys
from pathlib import Path

import numpy as np
import torch
from torch import nn

from ultralytics import YOLOE
from ultralytics.utils.tal import dist2bbox, make_anchors

OUT = Path(sys.argv[1] if len(sys.argv) > 1 else ".model-tools/work").resolve()
OUT.mkdir(parents=True, exist_ok=True)
os.chdir(OUT)  # Ultralytics downloads weights into the working directory; keep them out of the repo.


class Finder(nn.Module):
    """Backbone + neck + YOLOE text-prompted head, decoded like a YOLOv8 detection export."""

    def __init__(self, net):
        super().__init__()
        self.net = net
        self.head = net.model[-1]

    def forward(self, image, text):
        y, x = [], image
        for m in self.net.model[:-1]:
            if m.f != -1:
                x = y[m.f] if isinstance(m.f, int) else [x if j == -1 else y[j] for j in m.f]
            x = m(x)
            y.append(x if m.i in self.net.save else None)
        head = self.head
        feats = [y[j] for j in head.f]
        tpe = head.get_tpe(text)  # reprta + L2 normalise, inside the graph
        # Detection branch only (no masks). flatten(2) keeps the phrase count dynamic in the ONNX graph.
        b = image.shape[0]
        boxes = torch.cat([head.cv2[i](f).view(b, 4 * head.reg_max, -1) for i, f in enumerate(feats)], -1)
        scores = torch.cat([head.cv4[i](head.cv3[i](f), tpe).flatten(2) for i, f in enumerate(feats)], -1)
        anchors, strides = (t.transpose(0, 1) for t in make_anchors(feats, head.stride, 0.5))
        dbox = dist2bbox(head.dfl(boxes), anchors.unsqueeze(0), xywh=True, dim=1) * strides
        return torch.cat([dbox, scores.sigmoid()], 1)


def main():
    yoloe = YOLOE("yoloe-11s-seg.pt")
    net = yoloe.model.eval().float()
    for p in net.parameters():
        p.requires_grad_(False)
    finder = Finder(net).eval()

    from ultralytics.nn.text_model import build_text_model
    text_model = build_text_model("mobileclip:blt", device="cpu")
    names = ["person", "bus", "earbuds case"]
    tokens = text_model.tokenize(names)
    with torch.no_grad():
        feats = text_model.encode_text(tokens).float().unsqueeze(0)  # [1,N,512]

    image = torch.rand(1, 3, 640, 640)
    finder_path = OUT / "finder_yoloe11s.onnx"
    torch.onnx.export(finder, (image, feats), finder_path, opset_version=17, dynamo=False,
                      input_names=["images", "text"], output_names=["output0"],
                      dynamic_axes={"text": {1: "n"}, "output0": {1: "c"}})

    # Text encoder: TorchScript MobileCLIP wrapped for export.
    text_path = OUT / "finder_text.onnx"
    enc = text_model.encoder.eval()  # TorchScript MobileCLIP-BLT text tower; forward(tokens) -> features
    torch.onnx.export(enc, (tokens,), text_path, opset_version=17, dynamo=False,
                      input_names=["tokens"], output_names=["features"], dynamic_axes={"tokens": {0: "n"}, "features": {0: "n"}})

    # Verify both against PyTorch.
    import onnxruntime as ort
    with torch.no_grad():
        ref = finder(image, feats).numpy()
        ref_text = enc(tokens).numpy()
    got = ort.InferenceSession(str(finder_path)).run(None, {"images": image.numpy(), "text": feats.numpy()})[0]
    got_text = ort.InferenceSession(str(text_path)).run(None, {"tokens": tokens.numpy().astype(np.int32)})[0]
    print("finder", got.shape, "max abs diff", float(np.abs(got - ref).max()))
    print("text", got_text.shape, "max abs diff", float(np.abs(got_text - ref_text).max()))
    print("token ids for 'earbuds case':", tokens[2][:8].tolist())


if __name__ == "__main__":
    main()
