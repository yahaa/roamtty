# Roamtty demo video

Remotion project for the Google Play foreground-service review video and the website demo.

| Composition | Use | Output |
| --- | --- | --- |
| `PlayReview` | Play Console foreground service (`specialUse`) declaration video, ~63 s | `roamtty-play-review.mp4` |
| `SiteEn`, `SiteZh` | roamtty.com home page, ~105 s | `site/assets/video/site-demo-{en,zh}.mp4` in the private `yahaa/roamtty-website` repository |

All are 1920×1080, 30 fps, no audio.

## Website demo

`SiteDemo.tsx` plays real recordings of the app driving Claude Code and remote desktop on two virtual machines. How to record them is in `scripts/site-demo/README.md` of the website repository. The clips are not committed: put them in `public/site/<lang>/` as 30 fps H.264, and list the cuts in `src/siteTimeline.ts`. Captions are in `src/siteCopy.ts`.

```sh
npx remotion render SiteZh out/site-zh.mp4 --crf=18
npx remotion render SiteEn out/site-en.mp4 --crf=18
```

Check the frames before publishing: the virtual machines' gateway only offers another vendor's model, so no frame may show the Claude Code startup banner.

## Play review footage

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

The site copies are re-encoded with `-crf 27 -movflags +faststart`. Their posters are the frame at 72 s, the desktop in landscape.
