# Dynamic Island (Island) — Android project

An iPhone-style Dynamic Island for Android phones with a punch-hole camera. A black pill sits over the camera hole and grows out of it to show what is happening on the phone.

- **Package:** `com.blurify.island` · **App name:** Island · **Version:** 1.0
- **Stack:** Kotlin 2.1.0, Jetpack Compose (BOM 2024.12.01), Material 3, AGP 8.9.1, Java 17
- **SDK:** minSdk 30 (Android 11), targetSdk/compileSdk 36

## Features
- **Music:** compact pill with album art and a live indicator. Tap to expand into a player with title, artist, progress bar and controls. Auto-collapses after 7 s.
- **Mic / camera indicator:** shows when any app records audio or opens the camera.
- **Flashlight:** the card pops open when the torch turns on, then settles into a torch icon in the pill.
- **Headphones:** detects connected headphones.
- **Unlock animation:** short animation after unlock (`ACTION_USER_PRESENT`); can be switched off in the app.
- Handles rotation (`onConfigurationChanged`) and screen-off (collapses).
- A demo mode and a "Try the flashlight island" button in the main screen.

## How it works
Android only allows an overlay above the status bar through an **Accessibility Service**, so the island is hosted that way (it does not read the screen; its event filter is only `typeWindowStateChanged`). A **NotificationListenerService** exists only so Android grants access to the active media sessions.

| File | Role |
|---|---|
| `IslandAccessibilityService.kt` | Hosts the island window, creates/starts/stops the controller |
| `IslandController.kt` | Overlay window, media/mic/camera/torch/headphone listeners, decides which `Mode` is showing |
| `IslandModel.kt` | Observable state (`IslandModel`), modes `Hidden/Compact/Expanded/Unlock`, camera-hole geometry (`Geo`) |
| `IslandUI.kt` | Compose drawing and animations of the pill |
| `IslandService.kt` | Notification listener, tells the controller to attach media when access is granted |
| `OverlayLifecycle.kt` | Lifecycle/SavedState owner so Compose can run inside a service window |
| `MainActivity.kt` | Setup screen: enable accessibility + notification access, toggle unlock animation, demos |

The camera-hole size and position come from the display cutout (`computeGeo()`), with a fallback of 90×26 dp.

## Build & install
Requires Android Studio (or JDK 17 + Android SDK). `local.properties` points to the original author's SDK path — change `sdk.dir` to yours.

```bash
./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk` (a prebuilt copy is included in the zip as `Island-debug.apk`).

## First-run setup on the phone
1. Install the APK and open **Island**.
2. Enable the **Island accessibility service** (needed to draw above the status bar).
3. Enable **Notification access** for Island (needed to see the playing song).
4. Play music, turn on the flashlight, or use the in-app demo buttons.

## Permissions / privacy
No internet permission and no data leaves the device. The accessibility service does not read screen content.

## Notes
- Best on punch-hole devices; on notched/no-cutout phones it uses the fallback geometry.
- Some OEMs kill accessibility services in the background — exclude Island from battery optimization.
