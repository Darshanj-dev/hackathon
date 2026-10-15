# Saathi — Verify the next step

Native Android accessibility prototype combining Goal–Evidence–Verify with local object detection, OCR and scene descriptions. App ID remains `com.findback.app` so existing developer installs can update.

## User experience

White background, black typography, large controls and four task modes:

- **Places:** stationary washroom-sign scans, a bounded session evidence ledger, a fresh entrance-label verification scan, then explicit user acknowledgment. Direction signs never count as confirmed entrances. Evidence expires after eight seconds, is invalidated by detected camera rotation, and is cleared on cancellation/completion. The prepared corridor flow currently matches English washroom/restroom/toilet text.
- **Find:** YOLOv8n COCO object categories, camera-relative position, limited repeated speech and vibration. No personal-object identification or distance estimates.
- **Read:** bundled Latin and Devanagari OCR. Kannada-script OCR is not included.
- **Describe:** a single captured image and a brief on-device explanation in the selected response language. Local Gemma 4 E2B uses LiteRT-LM 0.16.1, bounded context and output, lazy loading and background unload. It never controls destination confirmation.

The interface has no model names, browser downloads, import screens, backend choices, RAM controls or token metrics. English, Hindi and Kannada response-language selection remains. Fixed task-state prompts are currently English. Speech voices and recognition depend on the phone's installed providers. TalkBack handles automatic result announcements when touch exploration is active; Replay remains user-controlled.

## Android Studio on the connected S24 FE

1. Pull `main` and sync Gradle in a recent Android Studio supporting AGP 8.13.2, SDK 35 and JDK 17+.
2. Connect one authorized Android phone (or set `ANDROID_SERIAL` for multiple devices).
3. Select the shared **Saathi — Install and run** configuration, then Run. If the IDE has not listed it, open the Gradle tool window and run `:app:runOnPhone`, or run `./gradlew :app:runOnPhone` in Studio's Terminal.
4. The developer computer builds the debug app, downloads/checksum-verifies installation data once, installs the APK, streams the assistant into the app's private storage using ADB `run-as`, verifies the phone copy and launches Saathi. Keep USB connected. This is developer provisioning, not an end-user onboarding step.
5. Later runs reuse verified local/phone data. Uninstalling the app deletes its private assistant copy. The phone requires about 2.59 GB for the assistant plus the APK and runtime headroom.

No manual model download, file transfer, import, login or API key is required from the app user. The one-time developer installation still needs internet on the Mac. The cloud network has denied the binary CDN download; full provisioning and actual model execution have not been validated on a physical S24 FE. Do not describe them as tested until that run completes.

A standalone APK does not contain the 2.59 GB weights. Use the developer installation task to provision the demo phone. Public APK-only distribution needs a separately designed managed delivery/preload mechanism; this repository does not claim that is implemented.

YOLO is automatically downloaded and verified on a fresh build. Both models are pinned by revision and SHA256 and excluded from Git. See `docs/MAC_SETUP.md`, `docs/PRODUCT.md` and `docs/PHONE_VALIDATION.md`.

## Build checks

`./gradlew assembleDebug testDebugUnitTest lintDebug`

Optimized developer-signed build: `./gradlew assembleDemo`. The `demo` variant is not debuggable, so provision using the debug install task first; it shares the same app ID/signing key and retains the private model on an update. A different developer signing key requires a manual uninstall first, which deletes data.

Cloud-only build helper: `bash scripts/cloud-build.sh` uses retained tooling under `/workspace/tooling`. It must not be run on your Mac. Each cloud task already has an isolated checkout; no Git worktree is needed.

## Boundaries and privacy

No images/video are stored or uploaded by Saathi. Session sign text is memory-only and cleared on task completion/cancellation. Object-category last sightings contain category/time/camera side, not personal identity. Android speech/TTS services may use network independently. Gyroscope motion invalidation is optional and threshold-based; it cannot detect every spatial change. Short evidence expiry is also enforced.

The app does not determine a safe walking route, obstacle clearance, road-crossing safety or room accessibility. A user-framed entrance-label scan plus user confirmation is not automated door recognition. Use existing mobility support. This is a controlled prototype; no phone latency, RAM, battery or field-safety claims are established by compilation or unit tests.

Model provenance: Google's AI Edge Gallery lists the LiteRT Community Gemma 4 E2B conversion, revision `6e5c4f1e395deb959c494953478fa5cec4b8008f`, SHA256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`. YOLO export: Hyuto/yolov8-onnxruntime-web revision `fc4a52c466d15ad4519873a0cef22fbc935b93b6`, SHA256 `505648ada344cd9f3f31e51d49c489c070819bc96cc758258a8fd51488e00579`. Review upstream model/runtime licenses before redistribution; Ultralytics models carry AGPL/commercial licensing obligations.
