# Saathi: combined product scope

The supplied “SAATHI — Verify the next step” blueprint provides the goal-aware task policy. The earlier app contributes native CameraX capture, YOLO category finding, Latin/Devanagari OCR, local multilingual scene explanations, speech and haptics. The user-facing design is white with black typography and task names, with model provisioning handled by the developer installation.

## Goal–Evidence–Verify

The controlled task is finding and acknowledging a washroom entrance through readable existing English signs, while stationary at each scan. An observation contains OCR text, capture time and camera-relative side. Relevant observations are kept in a bounded, session-only ledger.

A first observation is a candidate. It cannot directly complete the task. A separate Verify entrance action requests another frame; that frame must be newer and within the freshness limit. An explicit user acknowledgment is still required. Recognized directional text/symbols make the observation a direction sign and prevent entrance confirmation. Arrow orientation is not reported as verified.

Expired frames, competing labels, a new scan without relevant text and detected camera rotation withdraw current confirmation. A low-light check requests a clearer view rather than claiming the route is clear. Gyroscope thresholds and OCR cannot catch every change; these are prototype mechanisms, not safety guarantees. Partial labels or arrows missed by OCR remain limitations. The system does not automatically recognize a physical doorway; the user frames and acknowledges the entrance label.

Read and Find remain available independently of the conversational model. Generated scene descriptions cannot change the task state or authorize movement. Object detections are camera-relative categories, not depth or safe-path estimates.

## Included versus deferred

Included: four modes, user-controlled speech/voice alternatives, help/dialer, session evidence, freshness/confirmation policy, optional motion invalidation, local still-image explanations, developer-managed model installation.

Deferred: venue-wide routing, room accessibility verification, comprehensive obstacle avoidance, depth, SLAM, personalized object learning, persistent route trails, Kannada-script OCR, accounts, public app-store model delivery, and validation with intended users. No novelty, accuracy, latency or battery metrics have been measured here.
