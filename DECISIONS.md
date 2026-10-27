# Decisions

- The player shows an Audio button only when the current video exposes multiple supported audio tracks. This avoids a nonfunctional control on single-track files.
- Track choices come from Media3's currently available tracks; unsupported audio formats are omitted. Each listed option shows the available language or label, codec, and channel count.
- The playback engine lives in MediaSessionService while the existing Activity remains the player screen. The larger modular architecture in the supplied prompt remains a later project step.

- Playback resume is stored locally by selected content URI, saved at five-second intervals and on stop, ignored before five seconds, and cleared after 95% watched.
- Double-tap uses the left and right thirds for 10-second seeks and the center third for play or pause; playback speed is selected from an accessible list.
- Network streaming remains excluded because the user's product direction is a local player.
- Embedded subtitle tracks use the same Media3 track inventory. Users can choose a supported track or turn subtitles off; external SRT/VTT files can be attached per video; richer sidecar formats remain future work.

- External subtitle loading uses the system document picker, remembers the selected URI per video, and currently accepts SRT, VTT, ASS, SSA, and TTML. It preserves the current playback position when reloading the media item.

- Playback now belongs to MediaSessionService; the Activity connects with MediaController and releases only the controller on stop so background playback can continue. Session connect uses Media3's command defaults for trusted versus untrusted controllers.
- Picture-in-picture is available from a player button and enters automatically when leaving the app during active playback. The service saves progress while playback continues in the background.

- The library scans a selected SAF tree asynchronously with a 10,000-video cap. Selecting a video creates an auto-advance queue limited to its containing folder.
- The service persists queue URIs and current index; MediaButtonReceiver plus onPlaybackResumption restores local playback after service recreation. Android System UI library resumption still needs MediaLibraryService.

- The library exposes name/date/size sorting and URI-keyed favorites using local preferences. Recursive SAF scans are asynchronous and limited to 10,000 listed video files.

- Continue Watching shows up to ten local videos with saved positions and progress bars. It appears above the full library list and opens at the saved position.

- MediaStore browsing is opt-in through an explicit button. The app requests video-only media access and handles Android 14 selected-video access with an Add more action; the SAF folder picker remains available.
