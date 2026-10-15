# FindBack — Android accessibility prototype

Native Android app for finding COCO objects and reading signs with brief spoken feedback.
Built with Java 17, Android Views, CameraX, YOLOv8n / ONNX Runtime, bundled ML Kit OCR, Android TTS and speech recognition. Minimum Android 8 (API 26). ARM64-only build, targeting the Samsung Galaxy S24 FE (8 GB RAM). An x86 emulator requires changing the ABI filter. Use the ARM64 emulator or a real phone with the default configuration. No API keys or backend required.

## Open in Android Studio

1. Open this directory as a project. Use Android Studio's embedded JDK (17 or newer) and install Android SDK 35 when prompted.
2. A fresh build automatically runs `scripts/fetch-model.sh` to download the pinned, checksum-verified 12 MB YOLO model. You can also run `bash scripts/fetch-model.sh` manually. The prepared cloud checkout already includes it, but the binary is ignored by Git.
3. Sync Gradle, connect a physical Android phone with USB debugging enabled, select **app**, and Run. Accept camera permission. Configure an English Android text-to-speech voice if necessary.
4. Emulator testing requires a configured camera. A physical phone is recommended for latency and accessibility validation.

CLI: `./gradlew assembleDebug assembleDemo testDebugUnitTest lintDebug`
APKs: `app/build/outputs/apk/debug/app-debug.apk` for development; `app/build/outputs/apk/demo/app-demo.apk` for the optimized, non-debuggable demo. The demo uses the local debug signing key for installation convenience; it is not a production release. In Android Studio choose the **demo** build variant for the smaller optimized build. If a locally built APK cannot update the provided APK because the signing keys differ, uninstall this prototype first; this clears saved sightings.

## Try it

- **Find object**: enter `bottle`, `chair`, `cup`, `book`, or `cell phone`, tap **Scan now**, and slowly scan. Detections produce a box, a short positional cue and vibration. Detection runs approximately once per second, with repeated audio suppressed.
- **Read text**: aim at a well-lit English sign, tap **Read text**, then **Scan now**. OCR reads the next available frame aloud.
- **Voice command**: try “find a bottle,” “read text,” or “repeat.” Android's recognition provider may require internet; typing and buttons remain available.
- **Last seen** recalls a timestamp and past camera-relative position for the entered object class. It is not personal-object identification or room-level location memory.
- **Repeat** and **Stop audio** control spoken feedback. Controls have large touch targets and support TalkBack. Actual TalkBack usability still needs testing with users.

## Model contract

`assets/yolov8n.onnx`: float32 RGB `[1,3,640,640]` input, `[1,84,8400]` output, 80 COCO classes, no embedded NMS. Images are resized directly to 640×640; decoded coordinates map back to the original frame. Confidence threshold 0.45; class-wise NMS IoU 0.45. The model is not trained specifically for accessibility hazards. Doors and restroom symbols are not COCO classes; use Read for printed signs. Devanagari OCR is bundled for Hindi; Kannada-script OCR is not supported.

Source: Hyuto/yolov8-onnxruntime-web, commit `fc4a52c466d15ad4519873a0cef22fbc935b93b6`, `public/model/yolov8n.onnx`.
SHA256: `505648ada344cd9f3f31e51d49c489c070819bc96cc758258a8fd51488e00579`.
Ultralytics models have licensing obligations (AGPL-3.0 or commercial licensing as applicable); review upstream terms before distribution. The ONNX export is third-party; no claim of upstream signed provenance is made.

## Scope and privacy

Camera processing, Gemma inference (when a model is imported), and sighting memory stay on-device. No camera frames are uploaded by this app. External Android speech recognition may process voice remotely under the selected provider's policy. This prototype describes camera-relative positions, not safe routes, reliable distance, or obstacle clearance. It cannot distinguish your bottle from another bottle. It is not a substitute for a cane, guide dog, or mobility training.

Gemma 4 E2B scene-question integration now uses Google's LiteRT-LM Android runtime 0.16.1. It supports one captured image per request and streamed text with a short output cap. Model weights are imported separately; the cloud network has blocked their download, so actual Gemma inference is **not validated**. See [Mac setup and model download](docs/MAC_SETUP.md). English/Hindi/Kannada selection controls model prompts and speech locales; fixed detection cues cover common objects. Hindi OCR is bundled; Kannada OCR is not supported. Personal-object embeddings and persistent tracking remain unimplemented.

## Cloud build

The prepared machine stores SDK/JDK/Gradle under `/workspace/tooling`; these are outside the source checkout. Android Studio's graphical UI is not running in the cloud. See `scripts/cloud-build.sh` for the tested cloud build command; it uses this environment's proxy without disabling TLS verification. Existing checkouts are isolated: no Git worktree is needed.

## Performance profile

ARM64-only native libraries reduce APK size. YOLO nano uses two CPU threads; analysis requests 640×480 camera frames, samples at most about once per 900 ms, and retains only the latest camera frame. Inference runs off the UI thread, never queues unbounded frames, is idle until Find starts, and pauses in the background. OCR runs only on request. Release builds enable code/resource shrinking. Gemma weights are not bundled in the APK; its runtime is included. The separate 2.59 GB model loads lazily, caps context at 1,024 tokens and output at 64 tokens, disables thinking, and is unloaded when the app is backgrounded or the user requests it. Model loading can still temporarily require substantial RAM and takes time; these controls do not guarantee low peak RAM. One UI 9 behavior, latency, RAM, thermal and battery consumption have not been measured on an S24 FE. Do not treat these design choices as measured performance claims.
