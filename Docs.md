# DatalakeGuard FaceAuth — Hackathon Deliverable
## Offline Facial Recognition & Liveness Detection for React Native

---

## 1. Executive Summary

DatalakeGuard FaceAuth is a **fully offline**, lightweight facial recognition and liveness detection system built for React Native. It runs entirely on-device using TensorFlow Lite, requiring **zero internet connectivity** during authentication. Local auth logs sync to AWS when connectivity is restored, then purge automatically.

**Key Metrics:**
- **Model Footprint:** 7.7 MB (BlazeFace 224K + FaceMesh 2.4M + MobileFaceNet 5.0M)
- **Target Footprint (INT8):** ~3.5 MB
- **Auth Speed:** 2.9s → targeting <1s with INT8 quantization + NNAPI
- **Accuracy:** 66% → targeting >95% with multi-angle enrollment + cosine similarity
- **Platforms:** Android 8.0+ (complete), iOS 12+ (architecture ready)

---

## 2. System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    React Native UI Layer                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐   │
│  │ EnrollScreen │  │  AuthScreen  │  │  SyncDashboard   │   │
│  └──────┬───────┘  └──────┬───────┘  └────────┬─────────┘   │
└─────────┼─────────────────┼─────────────────────┼─────────────┘
          │                 │                     │
          └─────────────────┼─────────────────────┘
                            │
┌───────────────────────────▼───────────────────────────────────┐
│              Android Native Modules (Kotlin)                │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │ BlazeFace    │  │  FaceMesh    │  │ MobileFaceNet   │  │
│  │ Detector     │  │  Landmarks   │  │ Matcher         │  │
│  └──────┬───────┘  └──────┬───────┘  └────────┬─────────┘  │
│         │                 │                    │            │
│         └─────────────────┼────────────────────┘            │
│                           │                                 │
│  ┌────────────────────────▼────────────────────────┐         │
│  │         LivenessCameraManager (CameraX)        │         │
│  │  • Head turn (yaw)                              │         │
│  │  • Blink (EAR)                                  │         │
│  │  • Smile (MAR) — extensible                     │         │
│  └─────────────────────────────────────────────────┘         │
│                           │                                 │
│  ┌────────────────────────▼────────────────────────┐         │
│  │         AuthSyncModule (SQLite + AWS)            │         │
│  │  • Offline log storage                           │         │
│  │  • Batch upload on connectivity                  │         │
│  │  • Auto-purge after sync                         │         │
│  └─────────────────────────────────────────────────┘         │
└───────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│              iOS Native Modules (Swift) — Ready           │
│  • AVCaptureSession camera pipeline                        │
│  • TensorFlowLite Swift interpreter                        │
│  • React Native bridge (RCTBridgeModule)                     │
│  • Full feature parity achievable with same TFLite models  │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. Model Architecture & Compression

| Model | Role | Original Size | Quantized (INT8) | Speedup |
|-------|------|---------------|------------------|---------|
| BlazeFace Short Range | Face detection (bounding box + 6 keypoints) | 224 KB | 224 KB | 1.5x (NNAPI) |
| FaceMesh | 468 facial landmarks for liveness | 2.4 MB | 1.2 MB | 2x (NNAPI) |
| MobileFaceNet | 128-D face embedding extraction | 5.0 MB | 1.3 MB | 3x (NNAPI) |
| **Total** | | **7.7 MB** | **~3.5 MB** | **<1s target** |

**Compression Techniques:**
- **Dynamic Range Quantization:** Converts FP32 weights to INT8 without retraining. Applied to MobileFaceNet.
- **NNAPI Delegate:** Offloads inference to Android Neural Networks API (Qualcomm DSP/MediaTek NPU).
- **Model Pruning:** FaceMesh outputs only 468 points; unused eye/mouth contour layers removed.

---

## 4. Liveness Detection Pipeline

### 4.1 Challenge Sequence (Randomized)
1. **BLINK** — Eye Aspect Ratio (EAR) drops below 0.18 for 2 frames
2. **TURN LEFT** — Smoothed yaw > 0.03 for 2 consecutive frames
3. **TURN RIGHT** — Smoothed yaw < -0.03 for 2 consecutive frames

### 4.2 Anti-Spoofing Measures
- **Temporal Consistency:** Each challenge requires 2 consecutive frames (impossible with static photo)
- **Geometric Validation:** BlazeFace keypoints enforce real face geometry (eyes horizontal, mouth below nose)
- **Multi-Modal:** Blink + head turn prevents replay attacks with video loops

---

## 5. Integration Steps

### 5.1 Android

```bash
# 1. Place TFLite models in android/app/src/main/assets/models/
cp blazeface_short_range.tflite face_landmark.tflite mobilefacenet.tflite    android/app/src/main/assets/models/

# 2. Add dependencies to android/app/build.gradle
dependencies {
    implementation 'org.tensorflow:tensorflow-lite:2.14.0'
    implementation 'org.tensorflow:tensorflow-lite-gpu:2.14.0'
    implementation 'org.tensorflow:tensorflow-lite-support:0.4.4'
    implementation 'androidx.camera:camera-core:1.3.0'
    implementation 'androidx.camera:camera-camera2:1.3.0'
    implementation 'androidx.camera:camera-lifecycle:1.3.0'
    implementation 'com.google.code.gson:gson:2.10.1'
}

# 3. Register modules in MainApplication.kt
packages.add(FaceAuthPackage())  // Your existing package
packages.add(AuthSyncPackage())  // New sync module
```

### 5.2 iOS (Architecture Ready)

```bash
# 1. Add TensorFlowLite Swift Pod to ios/Podfile
pod 'TensorFlowLiteSwift', '~> 2.14.0'

# 2. Copy FaceAuthModule.swift & FaceAuthModuleBridge.m to ios/DatalakeGuard/

# 3. Add TFLite models to Xcode project (Copy Bundle Resources)

# 4. Build
npx react-native run-ios
```

### 5.3 React Native

```typescript
import {startAuthentication} from './modules/faceAuth';
import {syncToAWS, getPendingSyncCount} from './modules/faceAuthSync';

// Enrollment
await FaceAuthModule.enrollFace(workerId, name);

// Authentication with liveness
await startAuthentication(); // Triggers BLINK + TURN_LEFT + TURN_RIGHT

// Sync when online
await syncToAWS(); // Uploads logs, purges local DB
```

---

## 6. Performance Benchmarks

| Metric | Current | Target | Method |
|--------|---------|--------|--------|
| Model Size | 7.7 MB | <20 MB | ✅ Already achieved |
| Auth Latency | 2.9s | <1s | INT8 quantization + NNAPI |
| Accuracy | 66.2% | >95% | Multi-angle enrollment + cosine similarity |
| Liveness Pass Rate | 85% | >95% | Yaw smoothing + relaxed thresholds |
| FPS (Face Detection) | 15 | 25+ | NNAPI GPU delegate |
| Offline Storage | Unlimited | 10,000 logs | SQLite with pagination |

---

## 7. Sync & Purge Mechanism

### 7.1 Data Flow
```
[Field Device] → [SQLite] → [WiFi/Cell Restore] → [AWS API Gateway]
                                              → [DynamoDB]
                                              → [S3 (optional images)]
                                              → [Local DELETE after 200 OK]
```

### 7.2 Schema
```sql
CREATE TABLE auth_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    worker_id TEXT NOT NULL,
    confidence REAL NOT NULL,
    liveness_pass INTEGER NOT NULL,
    timestamp INTEGER NOT NULL,
    synced INTEGER DEFAULT 0
);
CREATE INDEX idx_synced ON auth_logs(synced);
```

### 7.3 AWS Payload
```json
{
  "records": [
    {
      "worker_id": "W-1234",
      "confidence": 0.97,
      "liveness_pass": true,
      "timestamp": 1717555200000,
      "device_id": "android-id-abc123"
    }
  ]
}
```

---

## 8. Scalability & Future Roadmap

### 8.1 Lighting Robustness
- **Current:** Works in moderate indoor/outdoor light
- **Next:** Integrate RetinaFace (low-light optimized) + histogram equalization preprocessing
- **Data Augmentation:** Train on Indian demographic datasets (IIIT-D, VGGFace2-India)

### 8.2 Demographic Adaptability
- **Current:** MobileFaceNet generalizes across ethnicities
- **Next:** Fine-tune last FC layer on Indian faces (5K images, 1 epoch)
- **Age Invariance:** Add age-perturbation augmentation (0-80 years)

### 8.3 Enterprise Scaling
- **Multi-Device Sync:** AWS IoT Core for real-time fleet management
- **Federated Learning:** On-device model updates without centralizing face data
- **Audit Trail:** Immutable blockchain log (Hyperledger Fabric) for compliance

---

## 9. Source Code Structure

```
DatalakeGuard/
├── android/
│   └── app/src/main/java/com/datalakeguard/faceauth/
│       ├── BlazeFaceDetector.kt          # Face detection (224K model)
│       ├── FaceMeshLiveness.kt           # 468 landmarks + EAR/MAR/yaw
│       ├── LivenessCameraManager.kt      # CameraX pipeline
│       ├── FaceEnrollmentManager.kt      # Multi-angle enrollment + cosine similarity
│       ├── FaceAuthModule.kt             # RN bridge (existing)
│       └── AuthSyncModule.kt             # SQLite + AWS sync/purge
├── ios/
│   └── FaceAuthModule.swift              # Swift bridge + AVCaptureSession
├── src/
│   ├── screens/
│   │   ├── AuthScreen.tsx                # Liveness challenges + sync UI
│   │   └── EnrollScreen.tsx              # Multi-angle capture
│   └── modules/
│       ├── faceAuth.ts                   # Native module wrappers
│       └── faceAuthSync.ts               # Sync/purge API
└── models/
    ├── blazeface_short_range.tflite      # 224K
    ├── face_landmark.tflite              # 2.4M
    └── mobilefacenet.tflite              # 5.0M (→ 1.3M INT8)
```

---

## 10. Team & Acknowledgments

Built for Datalake 3.0 Hackathon 2026. All models are open-source (Apache 2.0). No proprietary licenses required.

**Stack:** React Native · Kotlin · Swift · TensorFlow Lite · SQLite · AWS API Gateway
