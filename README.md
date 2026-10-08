# JARVIS Assistant - complete Android project

Gemini Live voice + device control (calls, WhatsApp, accessibility, background wake word,
overlay + edge lighting, camera, screen share).

## Build the APK (Android Studio - easiest)
1. Install Android Studio (Koala or newer). Open this folder as a project.
2. Let Gradle sync finish (needs internet once; it downloads Gradle 8.9 + libraries).
3. Menu: Build > Build Bundle(s) / APK(s) > Build APK(s).
4. Debug APK: app/build/outputs/apk/debug/app-debug.apk

## Build from terminal
    gradle wrapper --gradle-version 8.9      # once, if ./gradlew is missing
    ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk

## Release (signed) APK
    keytool -genkey -v -keystore jarvis.jks -keyalg RSA -keysize 2048 -validity 10000 -alias jarvis
    ./gradlew assembleRelease
    zipalign -v -p 4 app/build/outputs/apk/release/app-release-unsigned.apk jarvis-aligned.apk
    apksigner sign --ks jarvis.jks --out jarvis-release.apk jarvis-aligned.apk

## First run on the phone
1. Settings (gear) > paste Gemini API key > Save.
2. Tap the power button, allow microphone, talk.
3. Settings > Device Control & Permissions:
   - Permission center: Microphone, Camera, Contacts, Phone, Notifications.
   - Display over other apps: allow.
   - Accessibility: enable "JARVIS". On Android 13+ for a sideloaded APK, first open
     Settings > Apps > JARVIS > three dots > "Allow restricted settings".
   - Background assistant switch: starts the foreground service + wake word.
   - Battery optimization: exempt JARVIS (Xiaomi/Oppo/Vivo/Samsung also need autostart / "no restrictions").

## Voice commands to try
open YouTube | call Mom | WhatsApp Mom: I am coming home | go back | scroll down |
tap search | type hello | front camera | take a photo | start screen share | stop screen share |
run in background | stop background mode

## Known limits (Android, not bugs)
- Accessibility, overlay and screen-share consent are always user-granted by Android.
- Camera cannot be opened by a background app on Android 9+; open JARVIS first.
- Wake word uses Android SpeechRecognizer (fallback), not an offline always-on engine.
- Edge lighting is an on-screen animation, not the phone's physical LEDs.
- WhatsApp automation depends on WhatsApp's current view ids and can break after updates.
