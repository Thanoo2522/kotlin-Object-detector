# CenixAI Object Detection (Android + Kotlin + ONNX Runtime)

ตรวจจับวัตถุแบบเรียลไทม์ผ่านกล้อง โดยใช้ ONNX Runtime + CameraX + Jetpack Compose

## สเปกที่ต้องใช้
- Android Studio (RAM แนะนำ 16 GB, พื้นที่ว่าง 20-25 GB)
- Minimum SDK: API 26 (Android 8.0) / Kotlin / Kotlin DSL (`build.gradle.kts`)

## ขั้นตอนการสร้างโปรเจกต์
1. **สร้างโปรเจกต์ใหม่** — New Project > Empty Activity
   - Name: `AI Object Detection`
   - Package: `com.cenixai.AIObjectDetection`
   - Language: Kotlin
2. **รันทดสอบ Hello Android** ก่อน ด้วย Emulator หรือมือถือจริง (เชื่อมต่อ USB + เปิด USB Debugging แล้วเช็คด้วย `adb devices`)

## ติดตั้งไฟล์โค้ด/โมเดล
วางโครงสร้างไฟล์ตามนี้ใน `app/src/main/`:
```
assets/models/<ชื่อโมเดล>/best.onnx, classes.json

java/com/cenixai/AIObjectDetection/
  MainActivity.kt
  detector/  (Detection.kt, Detector.kt, Nms.kt, OnnxDetector.kt)
  model/     (ModelManager.kt)
  camera/    (IpCameraSource.kt)
  ui/        (DetectionOverlay.kt)
```

**Dependencies** (`app/build.gradle.kts`):
```kotlin
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
implementation("androidx.camera:camera-core:1.4.1")
implementation("androidx.camera:camera-camera2:1.4.1")
implementation("androidx.camera:camera-lifecycle:1.4.1")
implementation("androidx.camera:camera-view:1.4.1")
implementation("com.squareup.okhttp3:okhttp:4.12.0")
```

**Permissions** (`AndroidManifest.xml`, ก่อน `<application>`):
```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-feature android:name="android.hardware.camera.any" android:required="true" />
```

## ปัญหาที่พบและวิธีแก้หลัก
| ปัญหา | วิธีแก้ |
|---|---|
| Gradle sync ช้า | รัน `gradlew.bat assembleDebug` แทนการรอ sync |
| Build fail จากปีพุทธศักราช (Windows ไทย) | เพิ่ม `-Duser.language=en -Duser.country=US` ใน `gradle.properties` แล้ว `.\gradlew.bat --stop` |
| compileSdk ไม่พอ | อัปเดต SDK Platform ที่ error แจ้ง แล้วแก้ `compileSdk` ให้ตรง |
| กล้องทับ UI / ไม่เห็นกรอบตรวจจับ | ตั้ง `implementationMode = COMPATIBLE`, `scaleType = FIT_CENTER` ใน `PreviewView` |
| กรอบตรวจจับตำแหน่งไม่ตรงวัตถุ | ต้องทำครบ 3 อย่าง: (1) หมุนภาพตาม `rotationDegrees` (2) คำนวณ offset/scale แบบ FIT_CENTER ใน overlay (3) ใช้ `UseCaseGroup` + `ViewPort` |

## ทดสอบบนมือถือจริง
1. อนุญาตกล้อง → เลือกโมเดลจาก dropdown (`assets/models/`)
2. ตั้งค่า Confidence (~0.5) และ IoU (~0.45)
3. กด **Start** เพื่อเริ่มตรวจจับ, กด **Stop** เพื่อหยุด

## คำสั่งที่ใช้บ่อย
```bash
adb devices                  # เช็คมือถือเชื่อมต่อ
.\gradlew.bat --stop         # หยุด Gradle Daemon
.\gradlew.bat assembleDebug  # build APK
.\gradlew.bat clean          # เคลียร์ build cache
```

## โครงสร้างไฟล์หลัก
| ไฟล์ | หน้าที่ |
|---|---|
| `MainActivity.kt` | UI หลัก + CameraX + ควบคุม Start/Stop |
| `detector/OnnxDetector.kt` | letterbox + parse output ของ ONNX |
| `detector/Nms.kt` | Non-Maximum Suppression |
| `detector/Detection.kt` | data class เก็บผลลัพธ์กล่องตรวจจับ |
| `model/ModelManager.kt` | อ่านรายชื่อโมเดล/classes.json |
| `camera/IpCameraSource.kt` | รองรับกล้อง IP (MJPEG) |
| `ui/DetectionOverlay.kt` | วาดกรอบ+label ทับกล้อง |
