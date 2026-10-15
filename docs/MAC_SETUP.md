# Android Studio on your Mac → Samsung S24 FE

This cloud agent cannot operate Android Studio or USB devices on your Mac. Open the supplied source ZIP locally; the commands below run on your Mac, not in the cloud.

## Update from GitHub

If this repository is already open in Android Studio, commit or stash your local changes first, then use **Git → Pull**, remote `origin`, branch `main`. Pulling is required: GitHub changes do not automatically overwrite your local files. Accept Gradle sync after the update. If no project is open yet, use **Get from VCS** with `https://github.com/Darshanj-dev/hackathon.git`.

## Build and run

1. Unzip `FindBack-Android-Studio.zip` and open its `FindBack` folder in Android Studio.
2. Use a recent Android Studio supporting Android Gradle Plugin 8.13.2. Select the embedded JDK 17+ in Settings → Build Tools → Gradle. Let Studio install Android SDK 35 and sync dependencies. Do not run `scripts/cloud-build.sh` on your Mac: it uses cloud-only paths.
3. The ZIP includes YOLO. For a Git checkout, the first build automatically downloads and checksum-verifies YOLO; it needs access to raw.githubusercontent.com. You can also run `bash scripts/fetch-model.sh` manually.
4. On your S24 FE, enable Developer options → USB debugging. Connect it to your Mac and approve the device prompt. Choose the phone in Studio's device selector. The default project builds only ARM64, not Intel emulator images.
5. Select **app**, choose **debug** for development or **demo** for optimized performance, then Run. The project uses native Java Android code; Kotlin runtime dependencies are supplied by LiteRT-LM. No Kotlin plugin is needed to call its Java-compatible API.
6. Uninstall the earlier prototype first if Studio reports mismatched signing certificates. This clears its local data.

## Get the correct Gemma 4 model

The source is the [Google AI Edge Gallery catalog](https://github.com/google-ai-edge/gallery/blob/main/model_allowlists/1_0_13.json), which lists [Gemma 4 E2B in the LiteRT Community repository](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm). This is Google's recommended Android conversion/distribution, not a claim that the conversion is hosted under the `google` account.

Chosen file: `gemma-4-E2B-it.litertlm`, revision `6e5c4f1e395deb959c494953478fa5cec4b8008f`, exactly **2,588,147,712 bytes**. Google Gallery lists an 8 GB device minimum; its E4B model has a 12 GB minimum and is not the choice for this phone. Read the model's license and access terms on its page.

In Terminal, from the project folder:

```bash
python3 -m venv .model-tools
source .model-tools/bin/activate
python -m pip install 'huggingface_hub==0.35.3'
python scripts/fetch-gemma.py
```

The script obtains the pinned repository's authoritative LFS SHA256, downloads the single model file, verifies the bytes and hash, and writes it to `~/Downloads/FindBack-model/`. It never disables TLS or stores a token in source. If the host requires authentication, use the Hugging Face CLI's secure login (`hf auth login`), never put your token in chat or project files. A proxy's CONNECT denial is a network issue, not proof that a token is missing.

Transfer the verified `.litertlm` file to Downloads on the phone using your preferred Android file-transfer tool, or download that exact revision with the phone's browser. In FindBack, tap **Import Gemma** and pick the file. Keep the app open during the copy. Import requires room for the app-private copy plus temporary headroom. It replaces the model only after completing the copy and size and SHA256 checks. Both the Mac downloader and Android importer check the pinned SHA256. Import only the verified file.

The 2.59 GB model is deliberately outside the APK. The source ZIP and APK do not include these weights while the cloud host blocks the download. Deleting FindBack deletes its private model copy. After a successful import, you can delete the phone’s Downloads copy to recover about 2.59 GB; keep the verified copy on your Mac. Releasing AI RAM unloads the engine but deliberately retains its model file and compilation cache for later use.

## Use Gemma and languages

- Choose **English, Hindi, or Kannada** for Gemma's response language and Android speech-recognition locale.
- Ask a short scene question, then tap **Ask Gemma**. The app captures one image (long edge at most 512 pixels), not a continuous stream.
- The first request loads the model. GPU is attempted first; initialization failure falls back to two CPU threads. Runtime compatibility and actual speed still require phone testing.
- Replies stream as text, then are spoken after completion, with a 64-token output cap, a 1,024-token context, a 280-token visual budget, and thinking disabled. The visual budget uses LiteRT-LM’s experimental flag with runtime version pinned to 0.16.1. First-token timing excludes cold model load; total timing includes it. A 30-second generation timeout does not cover native model initialization.
- Install the corresponding voice in Samsung/Android text-to-speech settings. If a voice is unavailable, the app shows that status and keeps text visible rather than claiming it can speak that language. Speech recognition depends on the installed provider.
- Fixed detection cues localize common object names and positions. Some status/UI text remains English. OCR bundles Latin and Devanagari recognition; **Kannada-script OCR is not supported** by this build. Kannada spoken model descriptions are a separate capability and still need quality testing.
- **Release AI RAM** unloads Gemma. Leaving the app cancels generation and requests unload. Native initialization itself is not interruptible by this wrapper; resources release after it returns. Find and Read remain independent of Gemma.

## Before the demo

Use `docs/PHONE_VALIDATION.md` and compare warm/cold requests in each language. Measure total PSS, peak RAM, first-token latency, end-to-end speech delay and thermals on the actual One UI installation. An 8 GB minimum listing does not guarantee smooth operation alongside other apps. No latency, RAM or battery measurements on an S24 FE have been made in the cloud.

Gemma output can be wrong. It is never used to authorize movement or establish safe navigation. No camera data is uploaded by FindBack; Android speech/TTS providers may use network services independently.
