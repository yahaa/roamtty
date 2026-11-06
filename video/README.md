# Roamtty demo video

Remotion project for the Google Play foreground-service review video and the website demo. It has three compositions (1920×1080, 30 fps, ~63 s, no audio):

| Composition | Use | Output |
| --- | --- | --- |
| `PlayReview` | Play Console foreground service (`specialUse`) declaration video | `roamtty-play-review.mp4` |
| `SiteEn`, `SiteZh` | roamtty.com home page | `site/assets/video/site-demo-{en,zh}.mp4` in the private `yahaa/roamtty-website` repository |

All three use the same real screen recording. Only the captions and outro change.

## Footage

`scripts/record.sh` drives `emulator-5554` in portrait with adb. It runs: new Claude session, then pick the host and `~/review-project`, ask a question, go Home and open Settings, open the notification shade, tap the notification to return, ask a follow-up, then Disconnect all. It writes `raw/rt.mp4`, the tap timestamps (`raw/marks.txt`) and the foreground-service state before, during and after the session (`raw/fgs.log`).

```sh
scripts/record.sh
ffmpeg -i raw/rt.mp4 -vf fps=30,format=yuv420p -c:v libx264 -crf 14 -an public/demo.mp4
ffmpeg -sseof -0.1 -i public/demo.mp4 -frames:v 1 -update 1 public/last.png
```

The footage is not committed. `src/timeline.ts` holds the edit list, taps, zooms and the blur over the server address, all keyed to the 2026-10-17 take. After re-recording, re-time it: video time ≈ `marks.txt` time − first mark + 2.55 s.

## Render

```sh
npm i
npx remotion studio                      # preview
npx remotion render PlayReview out/roamtty-play-review.mp4 --crf=16
npx remotion render SiteEn out/roamtty-demo-en.mp4 --crf=16
```

The site copies are re-encoded with `-crf 25 -movflags +faststart`. Their posters are the frame at 23 s.
