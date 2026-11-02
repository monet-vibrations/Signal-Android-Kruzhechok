# Testing round video messages on a real device

This fork adds Telegram-style round video messages ("video notes") to Signal Android.
Reference spec: `../ROUND_VIDEO_SPEC.md`.

## 1. Build

Use the **staging perf** build. It is minified like a release build, is signed with the debug key, and talks to
Signal's **staging** servers. It installs as a separate app, **Signal (Staging)**
(`org.thoughtcrime.securesms.staging`), next to your real Signal, so your real account is never touched.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :Signal-Android:assemblePlayStagingPerf
```

The APKs are written to `app/build/outputs/apk/playStaging/perf/`:

| File | Use for |
|---|---|
| `Signal-Android-play-staging-arm64-v8a-perf-8.32.1.apk` (~140 MB) | almost every phone from the last 8 years |
| `Signal-Android-play-staging-universal-perf-8.32.1.apk` (~375 MB) | if you're unsure of the CPU type |

Don't use the `playProd*` variants on a phone that has the real Signal installed. They use Signal's real app ID, so
they can't be installed alongside it. Registering a real number on them would also move your real Signal account
off your phone.

## 2. Install

There are two ways:

- **USB:** enable *Developer options → USB debugging*, connect the phone and run:
  ```bash
  ~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/playStaging/perf/Signal-Android-play-staging-arm64-v8a-perf-8.32.1.apk
  ```
- **File:** copy the APK to the phone and open it. Allow "Install unknown apps" for the app you opened it from.

## 3. Register (staging)

1. Open **Signal (Staging)**, then *Continue* and enter a phone number that can receive SMS. It doesn't have to be a
   spare: staging accounts are separate from real Signal accounts.
2. Solve the hCaptcha, then enter the SMS code **once**. Entering it twice can leave the install deregistered
   ("network error" at the PIN step). If that happens, clear the app's data and register again.
3. Set a PIN and a profile name.

Staging rate-limits SMS. If "Resend code" shows a countdown, wait for it.

To test sending and receiving, you need **two staging accounts with different numbers**, for example a phone and the
emulator, or two phones. Registering the same number on a second device deregisters the first one.

## 4. What to test

Do these in a chat, or in *Note to Self* for a quick single-device check.

**Input bar**
- [ ] A quick tap on the mic toggles mic ↔ video camera icon, with a light haptic. The choice survives an app restart.
- [ ] The first hold in video mode shows *"Allow access to your camera and microphone"*, then the system camera and
      mic prompts. Recording doesn't start on that press; hold again.
- [ ] Hold in video mode: a round front-camera preview with a white progress ring, a flip button, the lock, and
      "Slide to cancel" with a red video icon.
- [ ] Release: the note is sent. A hold shorter than about 0.8 s is discarded with a hint.
- [ ] Slide left: cancelled, nothing is sent.
- [ ] Slide up: locked. Release the finger; recording continues. *Send* sends, *Cancel* discards.
- [ ] Flip the camera while recording: the clip continues with the other camera.
- [ ] At 60 s the recording stops and sends automatically.
- [ ] Voice notes still work in mic mode, including while a round video is playing with sound.

**In the chat (this fork)**
- [ ] A sent or received video note is a circle with no bubble, and the time and ticks sit below it.
- [ ] It autoplays muted and loops.
- [ ] A tap plays it once with sound, with a ring showing progress, then it goes back to muted looping.
- [ ] Received over mobile data, the video may need a tap to download first; the download button sits inside the circle.

**What unmodified Signal clients see**
- [ ] Open a note full screen (*chat settings → Shared media → tap it*). That full square is what stock
      Android, iOS and Desktop show: the circle, with blurred and dimmed corners. Stock clients show it as a normal
      video, and tapping plays it with sound.

## 5. Logs

```bash
adb logcat -s VideoNoteRecorder VideoNoteCornerBaker AttachmentCompressionJo
```

A healthy recording looks like this:

```
VideoNoteRecorder: Recording started.
VideoNoteRecorder: Recording finalized. error: 0, duration: 5344ms, ...
VideoNoteCornerBaker: Baked video note corners in 2538 ms: 768x768, video <encoder> @ ~800000 bps, ...
VideoNoteRecorder: Video note ready: 768x768, ... bytes
AttachmentCompressionJo: Video note, already encoded. Not compressing.
```

Without a computer, use *Settings → Help → Debug log → Submit* and share the link.

If the bake fails (a warning from `VideoNoteCornerBaker`), the raw camera clip is sent instead. It gets compressed
normally and still shows as a circle in this fork, but stock clients see it without the baked corners. Please report
the device model and the log line.

## 6. Known limitations

- The camera can take a moment to start. The timer starts at the press, so the clip is slightly shorter than the
  timer shows. Releasing before the camera is ready sends nothing.
- Sending waits for the bake, which re-encodes the clip, and there's no progress indicator. The note appears in the
  chat once the bake is done. With hardware encoders that should be a few seconds even for 60 s. On the emulator
  (software encoder) a 48 s note took over 90 s. The bake gives up after 30 s plus 3× the clip length and sends the
  original instead. Please note how long long notes take on your phone.
- Saved notes aren't mirrored. The front-camera preview is mirrored like a selfie view, but the video shows you as
  others see you.
- Not implemented from Telegram: pause and resume, trim preview, flash, zoom, growing the circle on tap, a floating
  player when you scroll away, seeking on the ring, and earpiece playback when the phone is raised.
- Notes with a quote or caption show as a normal video.
