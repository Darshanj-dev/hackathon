# Saathi physical-phone validation

These device checks remain unexecuted in the cloud. Unit tests establish policy behavior, not camera accuracy, accessibility usability or field safety.

## Install

Pull main, sync Android Studio, connect one authorized S24 FE and run **Saathi — Install and run**. Wait for private assistant provisioning/checksum verification and launch. The ordinary APK alone does not contain the 2.59 GB model. Never present a non-provisioned standalone APK as a complete offline installation.

## Appearance and accessibility

- Verify white screens, black text, readable contrast, rounded controls and predictable focus order in Places/Find/Read/Describe.
- Test with TalkBack and large system font settings. Automatic results should use TalkBack rather than overlapping app speech; Replay is explicit.
- Check Language, camera Switch, Replay, Stop, Evidence, Cancel and Help. Help may open the dialer but must never place a call.

## Goal–Evidence–Verify

1. In Places, scan an actual English washroom sign. Irrelevant text must not become a destination.
2. A directional board remains a candidate, even during a verification attempt. Arrow orientation must not be announced as verified.
3. Aim at an entrance label and choose Verify entrance. Only a separate newer scan may enable Confirm entrance. Confirm manually; no automatic arrival.
4. Cover the sign, substitute Exit, show competing labels, move/rotate the phone, or wait beyond eight seconds. Current confirmation must be withdrawn and require a new scan.
5. Cancel or complete the task. Session sign evidence must clear. Previously seen text must never be announced as a current direction.
6. Test poor light and missing camera permission. No “path clear,” fabricated distance or walking-route claims should appear.

## Existing capabilities

- Find a bottle/chair/cup and compare camera-relative boxes and speech. Similar objects are not personally identified.
- Read a real Latin and Hindi sign. Kannada-script direct OCR is not implemented.
- Describe a real still image in English, Hindi and Kannada; verify factual/language quality and installed voices. Generated descriptions cannot change destination confirmation.
- Test airplane-mode OCR/detection/model inference after provisioning. Android speech providers may still require network depending on their installed assets.

## Performance and lifecycle

Measure cold total delay, warm first-token latency, end-to-end speech delay, total PSS, peak native/Java memory, battery and thermals on the named phone/One UI version. The model is lazy-loaded, uses a bounded context and output, and unloads on background/task switches; these are design choices, not measured savings. Leaving the app during native initialization can delay release until initialization returns.

On the local connected computer, use Android Studio profiling/Perfetto and `adb shell dumpsys meminfo com.findback.app`. Test repeated task/language changes for memory growth. Confirm the camera indicator stops on background and stale answers are not spoken after returning.

Use a controlled stationary demonstration with the user's usual mobility support. Intended-user co-design and supervised evaluation are future work, not replaced by these engineering checks.

## Report a failing mode

After reproducing the failure, run `bash scripts/check-phone.sh` from the Mac checkout. This prints installation version, camera permission, private model file sizes and recent Saathi/runtime errors. It does not capture images or export recognized text. Share this output with the developer; no credentials are needed. Check each mode separately, including switching modes while OCR is running.

The current camera path uses CameraX RGBA frames with explicit row-stride handling, a shared cropped preview/analysis viewport and higher resolution OCR input. Pure decoding tests cover channel order, row padding, buffer offsets and truncated input. These tests do not replace actual CameraX execution on a phone.

## Integrated image interpretation

Find, Read and Places feed a captured JPEG and bounded detector/OCR observations into the same installed Gemma 4 engine used by Describe. The interpretation replaces the main response in the selected language; there is no separate opinion panel. Initial recognition remains visible while interpretation loads. A Find request makes at most one interpretation, then stops scanning until the next request. Read/Find interpretation failure retains the original recognition result. Missing private weights are explicitly reported as reduced functionality; they are not silently treated as a complete installation.

Places interpretation is descriptive only. It cannot set, verify or confirm a destination. Its completed description is labelled as a captured view and accompanied by the current deterministic sign status. Verify still requires fresh OCR, and Confirm still requires explicit acknowledgment.

On the phone, test all four modes with the model provisioned, all three response languages, cancellation during model initialization/inference, and a deliberately missing model installation. Check that changing modes prevents old interpretations from appearing and that a generation failure restores the original recognition result. Runtime model behavior remains unverified in the cloud because the full weights/phone are unavailable here.
