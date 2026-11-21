# Open Voice

![icon](docs/icon.png)

An open-source, hands-free voice control app for Android, in the spirit of Google's Voice Access.
Say what you want to do; Open Voice taps, scrolls, types and navigates for you using Android's
Accessibility Service API.

## Commands

| Say | Does |
|---|---|
| `show numbers` / `hide numbers` | Overlay a number on every tappable element |
| `tap 5` / `long press 5` | Tap or long-press item 5 |
| `click <text>` | Tap the element whose text or description matches |
| `scroll down` / `scroll up` | Scroll the main list or page |
| `swipe left` / `swipe right` | Swipe gesture |
| `go back`, `go home`, `recents` | Global navigation |
| `notifications`, `quick settings` | Open the shade / quick settings |
| `open <app name>` | Launch an app |
| `type <text>` | Type into the focused field |
| `start dictation` / `stop dictation` | Everything you say is typed until you stop |
| `clear text`, `enter` | Edit the focused field |
| `stop listening` | Stop the microphone service |

## Build

GitHub Actions builds the APK on every push (`.github/workflows/android.yml`):
open the **Actions** tab, pick the latest run, and download the **open-voice-debug-apk** artifact.
Push a tag like `v0.1.0` to also attach the APK to a GitHub Release.

Locally (JDK 17 + Android SDK, Gradle 8.7+):

```
gradle assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Install & set up

1. Install the APK.
2. On Android 13+, sideloaded apps can't enable accessibility services until you allow it:
   **Settings → Apps → Open Voice → ⋮ → Allow restricted settings**.
3. Open Open Voice → **Enable accessibility service** → turn on Open Voice.
4. **Grant microphone**, then **Start listening**.

## How it works

- `OpenVoiceService`: the AccessibilityService. Reads the UI tree, performs clicks, scrolls, text
  input, gestures and global actions.
- `NumberOverlay`: draws numbered badges in an accessibility overlay window.
- `ListenService`: foreground service running Android's `SpeechRecognizer` in a loop
  (offline preferred when a language pack is installed).
- `CommandParser`: turns recognised phrases into `Command`s.

## Roadmap

- Offline recognition with Vosk and a restricted command grammar
- Coordinate grid for apps with poor accessibility trees
- Fuzzy matching for `click <text>`
- Settings screen, custom commands, language selection
- Wake word / pause-resume

## License

MIT
