# macOS host test (2026-10-22, MacBook M3, macOS 26.6, plugin 0.4.0 notarized)

- Install path as the app runs it: zip from GitHub → SHA-256 → ditto → link → LaunchAgent; Gatekeeper: Notarized Developer ID (8LN388NLF4).
- Before the grants: hello `needs: [screen-recording, accessibility]`; after Screen Recording only: `[accessibility]`; after both: `[]`.
  Grants made for an earlier ad-hoc test build do not carry over to the notarized app (different signature): `tccutil reset ScreenCapture|Accessibility com.roamtty.desktop`, then grant again.
- Picture: 41 and 146 frames, VideoToolbox H.264 Main, 1920×1246 (3024×1964 Retina scaled to the client's long edge), decoded with ffmpeg.
- Pointer: absolute move to (1512, 982) native px, cursor reported at (1509–1512, 982).
- Keyboard: a tap focuses the TextEdit window; `INPUT text` "ROAMTTY_MAC_你好 ok" and ⌘S (HID keys) saved the file with exactly that text (macos-typed-textedit.png).
- Not covered here: the phone app itself against the Mac (needs the phone), UDP to the Mac.
