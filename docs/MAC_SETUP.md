# Developer installation in Android Studio on your Mac

The cloud agent cannot control your Mac or its USB connection. Changes arrive through GitHub; use **Git → Pull → origin/main**, then sync Gradle. Preserve any local changes before pulling.

## One Run action installs the app and its assistant

1. Use a recent Android Studio supporting Android Gradle Plugin 8.13.2, Android SDK 35 and embedded JDK 17+. Python 3, curl and macOS `shasum` are used by developer setup.
2. Connect the S24 FE, unlock it, enable USB debugging and approve the computer. Keep one authorized phone connected. Multiple-device setups can set `ANDROID_SERIAL` in the Gradle process environment.
3. Select **Saathi — Install and run** in the configuration dropdown and press Run. This shared `.run` configuration runs `:app:runOnPhone`. Alternatively use Studio's Gradle tool window or `./gradlew :app:runOnPhone` in its Terminal.
4. The task downloads and SHA256-verifies 2.59 GB of installation data on the Mac once, builds/installs the debug APK, provisions private app storage over USB and launches Saathi. The user never sees model installation controls.
5. Wait for **Offline assistance installed and verified. Starting Saathi.** in the developer console before testing Describe. The transfer is streamed directly into app-private storage; no second phone-side source copy is retained.

Models are kept outside Git in `.assistant-models/`. No provider token is embedded in the app. The pinned public model comes from the [Google AI Edge Gallery-listed catalog](https://github.com/google-ai-edge/gallery/blob/main/model_allowlists/1_0_13.json), using [this exact Android conversion revision](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/6e5c4f1e395deb959c494953478fa5cec4b8008f). The host's binary CDN must be reachable from the Mac. The cloud environment has encountered a CDN access denial, so a full real-phone provisioning run remains outstanding.

If the APK signing certificate differs from an older installed build, Android rejects an update. Decide whether to uninstall the old prototype yourself; this clears all its app data. The installer never uninstalls or resets user data automatically.

If you choose the ordinary `app` configuration before provisioning, basic OCR/detection can run, but Describe will report that assistance is unavailable. The ordinary APK does not secretly contain the large weights. After provisioning once, normal debug updates preserve them.

## Test the intended experience

- **Places:** scan a real English washroom sign. A directional board is evidence, not arrival. Aim at an entrance label, choose Verify entrance, then confirm only after a fresh scan. Cover/move away from the sign and confirm the claim expires or is withdrawn.
- **Find:** use bottle, chair, cup or another supported COCO category. A model category is not your personal belonging.
- **Read:** test English and Hindi signs; Kannada-script direct OCR is unavailable.
- **Describe:** ask about the current view, checking language quality against the image. Model output never marks a destination confirmed.
- **Help:** opens instructions and, on request, the system dialer. It never places a call automatically.

TalkBack, large font settings, One UI camera lifecycle, GPU fallback, cold/warm model latency, RAM and battery still require measurements on your real S24 FE. See `PHONE_VALIDATION.md`. No phone performance is inferred from the cloud build.
