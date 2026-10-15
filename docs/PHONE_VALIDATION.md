# S24 FE / One UI validation

These checks are not yet executed on a phone. Use the optimized **demo** variant on the Galaxy S24 FE (8 GB RAM); it is ARM64-only and locally debug-signed.

1. Install `app-demo.apk`; permit the camera. Deny permission once first to check the recovery message, then enable camera in Android app settings.
2. Turn on TalkBack. Navigate every action, enter an object, trigger a scan, repeat feedback, stop audio and pause scanning. Check whether TalkBack and app speech interrupt each other; this needs user testing.
3. Test a bottle and chair in centre/left/right of the camera view, portrait and landscape. Confirm boxes align, speech does not repeat every frame, and absent objects produce an honest response. Similar objects are not individually identified.
4. Read an English room sign in good lighting, then test blurred/no-text frames. Toggle airplane mode: bundled OCR and detection should work; Android's voice input may not.
5. Switch apps and lock the screen. Confirm the camera indicator turns off and there is no background narration. Return and confirm scanning can resume.
6. Run Find continuously for 10 minutes and compare to an idle camera preview at the same brightness. Record thermal changes, missed frames and battery percentage (percentage alone is a coarse measure).
7. Measure real behavior with Android Studio's Memory Profiler on **debug**, and system tracing/Perfetto on **demo**. No profiler or debug overhead should be included in claimed demo latency. Sample `adb shell dumpsys meminfo com.findback.app` at launch, after scanning, and after 10 minutes; distinguish Java heap, native heap and total PSS. Track growth rather than just the first sample.
8. On the local connected computer, use `adb shell dumpsys gfxinfo com.findback.app` to inspect UI rendering and record a system trace for camera/inference stalls. Model inference time and spoken-response latency are different metrics.

The cloud build verifies compilation, lint, detector overlap math, model inference on a test image, package signature and native alignment. It does not establish phone usability, accuracy across environments, battery life, or safe navigation.

## Gemma 4 / multilingual checks

- Download the pinned E2B model with the Mac guide; import it. Try a wrong file and confirm rejection without losing the previous model. Both exact length and SHA256 must match.
- Ask one scene question in English, Hindi and Kannada. Check the answer against the image, language quality, voice availability, and hallucinations. These responses have not been validated in the cloud.
- Record cold total time separately from warm first-token latency. The app displays both; first-token timing excludes native model initialization. Speech starts when the short answer completes, not on each text token.
- Try GPU initialization; observe whether the app falls back to CPU. A fallback is not evidence of GPU performance on the S24 FE.
- Cancel during generation and leave the app while loading. Initialization can finish before unloading takes effect; check that memory then falls and no stale answer is spoken.
- Use Release AI RAM, then re-open Find and Read. Confirm they still work without Gemma. Check memory after repeated language changes and model reloads.
- Run Hindi OCR on a printed Devanagari sign. Kannada-script direct OCR is not included; do not present it as a tested capability.
