# AodBrightnessControl

An Xposed / LSPosed module that gives you full control over **Always-On Display (AOD)** brightness on Android 16 GSIs, Sony Xperia (`pdx213`), and other AOSP-based custom ROMs.

Built with modern **[LibXposed API 102](https://github.com/libxposed/api)**. Compatible with **LSPosed (Vector)** and **KernelSU / Magisk**.

---

## ✨ Features

- 🌙 **Night / Minimum Brightness Slider**: Set the exact lower bound (1–255) for dark rooms or night time.
- ☀️ **Daylight / Maximum Brightness Slider**: Set the upper brightness ceiling (1–255) for outdoor conditions.
- 💡 **Adaptive AOD Brightness**: Automatically and smoothly scales AOD brightness using the hardware ambient light sensor (ALS).
- 🎛️ **Light Sensitivity (Curve) Slider**: Fine-tune the response curve ($\gamma = 0.5\text{x} - 2.5\text{x}$) to keep brightness gentle under indoor lighting (< 2,000 lux) while still reaching full visibility in bright sunlight (up to 10,000 lux).
- ⚡ **Zero Wake Battery Drain**: Sensor listener is only active during AOD (`DOZE_AOD`) and automatically unregistered as soon as the screen wakes up (`FINISH`).
- ⚡ **Real-Time Updates**: Changes take effect instantly over broadcast without needing to reboot or restart SystemUI.

---

## 🛠️ How It Works

Many AOSP GSIs lack vendor-specific overlays (`doze_brightness_sensor_type`), causing SystemUI to hardcode AOD brightness to a fixed, non-dimming value and leave the ambient light sensor inactive during doze.

This module hooks into `com.android.systemui.doze.DozeScreenBrightness`:
1. Directly interfaces with the hardware `Sensor.TYPE_LIGHT` via `SensorManager`.
2. Computes the target brightness using an adjustable power curve:
   $$\text{Target} = \text{Min} + (\text{Max} - \text{Min}) \times \left(\frac{\text{lux}}{10\,000}\right)^{\text{Curve}}$$
3. Directly pushes target values into SystemUI's `mDozeService.setDozeScreenBrightness()` and patches internal brightness tables and scrims.

---

## 📱 Compatibility

- **Android Version**: Android 14, 15, and 16 (tested on Android 16 / crDroid 16.0 GSI)
- **Devices**: Sony Xperia 10 III (`pdx213`), and other Qualcomm / MediaTek devices running AOSP GSIs
- **Framework**: LSPosed / Vector (v2.2+ with LibXposed API 102)

---

## 🚀 Building from Source

```bash
git clone https://github.com/icadkren22/AodBrightnessControl.git
cd AodBrightnessControl
./gradlew assembleRelease
```
The compiled APK will be located in `app/build/outputs/apk/release/`.

---

## 📄 License

GPL-3.0 License
