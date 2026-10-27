# Pro Player

A modern Android local video player built by **Abhishek Kumar** for smooth, private, on-device playback.

> Independent implementation informed by media-player and reverse-engineering research. This project does not include proprietary source code or branding from other players.

## Features

- Hardware, software, and automatic decoder modes with fallback handling
- Same-video decoder restart with timestamp and playback-state restoration
- Multi-touch pinch zoom, pan, fit, stretch, crop, and zoom controls
- Brightness, volume, and timeline gestures
- Audio track/language selection
- Embedded and external subtitle support
- Playback speed control
- Picture-in-picture mode
- Background playback
- A–B repeat, sleep timer, orientation control, and kids lock
- Resume playback from the last saved position
- Material-inspired dark player UI

## Build

Open the project in Android Studio, select a connected Android device, and run the `app` configuration.

```powershell
.\gradlew.bat assembleDebug
```

The generated APK is placed under `app/build/outputs/apk/debug/` after a successful build.

## License

The original source code in this repository is licensed under Apache-2.0. Third-party libraries, codecs, fonts, and media assets remain under their respective licenses; see their notices for details.

## Attribution

Built by Abhishek Kumar. Developed through independent Android engineering, testing, and reverse-engineering research of common media-player workflows.

## APK download

Download the debug APK from [releases/ProPlayer-debug.apk](releases/ProPlayer-debug.apk). This build is for testing and sideloading, not a signed Google Play release.
