# Build prompt implementation status

The supplied prompt describes a full commercial-scale media player. The current app is still a small offline prototype. This status separates implemented code from future goals; the prompt's feature list is not evidence that a feature exists. Recent source updates are awaiting a consolidated build and device test, which will be done after the planned development phases.

## Implemented in source; pending consolidated build and device test

- Local video open through Android's document picker; recursive SAF folder listing; MediaStore library query across visible external volumes; Android 14 selected-video permission and reselection path. Scans run off the UI thread and cap listings at 10,000 items.
- Local search by filename or relative folder; sort by name, modified date, or file size; favorite videos, filter to favorites, and view a continue-watching strip with progress bars.
- Media3 playback owned by a MediaSessionService, with hardware decoder preference, Media3 decoder fallback, and one software-decoder retry.
- Brightness, volume, horizontal seek, pinch zoom, fit/stretch/crop, and 10-second double-tap seek/play-pause controls.
- Playback-speed selection from 0.5× to 4×.
- Supported embedded audio-track selection, embedded subtitle selection with Off, and external SRT/VTT/ASS/SSA/TTML subtitle loading per video. Folder playback queues videos from the same folder with automatic next/previous controls.
- Local resume position and queue saved every five seconds, on pause, and when the service stops; progress is cleared when watched to 95%. Media-button playback resumption restores the saved item and queue after service recreation.
- Picture-in-picture and background playback through the media session service. No network playback or broad-storage permission.

## Partial or missing areas from the prompt

- **Architecture and background playback:** playback now lives in a MediaSessionService and the activity binds through a MediaController. There is no MediaLibraryService for Android System UI library resumption, MVI, ViewModel, Compose, or modular core/feature layout.
- **Player controls:** no pan while zoomed, configurable seek gestures, double-tap accumulation feedback, long-press speed, kids lock, frame stepping, queue, A-B repeat, sleep timer, or settings screen. Basic screen lock, orientation selection, and PiP are present.
- **Tracks and subtitles:** embedded audio and subtitle selection and external SRT/VTT loading exist. There is no automatic sibling matching, subtitle styling/delay, charset selection, or multiple subtitle display.
- **Audio processing:** no equalizer, audio delay, loudness boost, night mode, channel mix, or background-audio UI.
- **Library:** folder listing is recursive with sorting and favorites; there is no audio library, thumbnails, playlists, watched badges, or list/grid toggle. MediaStore access still needs permission-flow and permission-revocation edge-case validation.
- **Formats and effects:** playback depends on platform decoders and Media3 extractors; there is no libmpv/libVLC fallback, FFmpeg decoder pack, video effects, converter, or format-specific test matrix.
- **Privacy and platform readiness:** no encrypted vault, biometric lock, Cast, LAN/network sources, AI features, accessibility audit, release signing, or Play submission preparation.
- **Quality setup:** no app test suite, lint baseline, benchmark, or CI workflow is present yet.

## Next stages

1. Finish the foundation: Android System UI playback resumption through MediaLibraryService and robust lifecycle handling.
2. Finish tracks and subtitles: add automatic sibling matching, subtitle styling and delay, then test the supported subtitle formats and language labels.
3. Improve the local library: recursive folders, sort/filter options, favorites, playlists, thumbnails, and recently played items.
4. Add audio controls and optional playback features: equalizer, sleep timer, background audio-only mode, and A-B repeat.
5. Evaluate decoder coverage, performance, accessibility, and release requirements before selecting any native decoder or converter dependencies.

Network streaming, Cast, and network file sources are excluded from this roadmap under the user's earlier direction to keep the app local.
