## 📦 Android App from Project: MaixSense A010 Development Board for Package Dimension Calculation and Ahamove Driver App Integration 

This Android app is part of Project 2, focusing on the hardware side.
It connects to external hardware (via Bluetooth) and runs on-device ML to scan and measure packages in real-time.

👉 This repo contains the Android app only. The CAD/PCB side is handled in a separate part.

## 🚀 Features
- Bluetooth Low Energy (BLE) device scanning & connection
- TensorFlow Lite model (best-fp16.tflite) for package detection
- Real-time overlay & visualization (OverlayView, RoiOverlayView)
- Measurement conversion from pixels → metrics (PixelToMetric.kt)
- Interactive UI: splash screen, welcome screen, and scanner activity
- Utility tools for sharing & zipping results

## 📂 Project Structure

```
Android-app-main/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/example/android_app/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── PackageScannerActivity.kt
│   │   │   │   ├── BluetoothLeManager.kt
│   │   │   │   ├── BoxDetector.kt
│   │   │   │   ├── Colormaps.kt
│   │   │   │   ├── DataViewerActivity.kt
│   │   │   │   ├── DevicesFound.kt
│   │   │   │   ├── FindingDevices.kt
│   │   │   │   ├── FrameWaitingActivity.kt
│   │   │   │   ├── MyApp.kt
│   │   │   │   ├── OverlayView.kt
│   │   │   │   ├── RoiOverlayView.kt
│   │   │   │   ├── ShareUtil.kt
│   │   │   │   ├── SplashActivity.kt
│   │   │   │   ├── WelcomeActivity.kt
│   │   │   │   ├── ZipUtil.kt
│   │   │   │   └── geometry/
│   │   │   │       ├── MaskBuilder.kt
│   │   │   │       ├── OrientationEstimator.kt
│   │   │   │       ├── PixelToMetric.kt
│   │   │   │       └── Types.kt
│   │   │   ├── res/        # layouts, drawables, animations
│   │   │   ├── assets/     # ML model + Lottie animations
│   │   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── build.gradle.kts
├── gradle.properties
└── settings.gradle.kts
```

## Getting Started

1️⃣ Requirements
- Android Studio (Arctic Fox or newer)
- Gradle + Kotlin configured (uses build.gradle.kts)
- Physical Android device with Bluetooth & camera

2️⃣ Installation
Clone the repo:

git clone https://github.com/your-username/Android-app-main.git

Open in Android Studio, let Gradle sync, and hit Run ▶️ on your device.

3️⃣ Usage
1. Launch the app → welcome screen will appear
2. Scan for devices → connect via Bluetooth
3. Start scanning a package → results will overlay in real-time
4. Save or share results via built-in tools

## 🛠 Dependencies

TensorFlow Lite for ML inference

Android BLE API for device connection

Lottie for animations (RotatingGear.json)

## 📸 Screenshots
<img width="1045" height="704" alt="Screenshot 2025-09-22 at 13 15 51" src="https://github.com/user-attachments/assets/51b0436b-7fa8-46c4-a1cb-0a9a3d54f963" />

<p float="left">
  <img src="https://github.com/user-attachments/assets/d93245de-8c71-4b6e-9aaa-be161a24a69b" width="200" />
  <img src="https://github.com/user-attachments/assets/6d424dc2-0472-4ba8-93e7-0317bb21f5fc" width="200" />
</p>

🤝 Contributors
1. Vo Hoang Khanh s3926310	 
2. Nguyen Hong Anh s3924711 
3. Vo Phuc Duy Nhat s3868763 
