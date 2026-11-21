# Open Voice

![icon](docs/icon.png)

An open-source, hands-free voice control app for Android, in the spirit of Google's Voice Access.
Say what you want to do; Open Voice taps, scrolls, types and navigates for you using Android's
Accessibility Service API.

## Commands

| Say | Does |
|---|---|
| `show numbers` / `hide numbers` | Number badges on every tappable element |
| `tap 5`, `double tap 5`, `long press 5` | Act on item 5 |
| `click <text>` | Tap the element whose text or description matches |
| `show grid`, then `1`–`9` (repeat to zoom), then `tap` / `double tap` / `hold` | Tap anywhere, even in apps with no accessibility info |
| `cancel` | Hide numbers and grid |
| `scroll down/up/left/right`, `scroll to top/bottom` | Scroll |
| `swipe left/right/up/down`, `refresh` | Swipe gestures |
| `go back`, `go home`, `recents`, `notifications`, `quick settings` | Navigation |
| `screenshot`, `lock screen`, `power menu`, `split screen` | System actions |
| `open <app name>` | Launch an app |
| `type <text>`, `start dictation` / `stop dictation` | Typing |
| `select all`, `copy`, `paste`, `cut`, `delete`, `delete word`, `clear text`, `new line`, `enter` | Editing |
| `volume up/down`, `mute`, `unmute` | Media volume |
| `pause listening` / `resume listening` | Ignore everything except the wake phrase |
| `show commands`, `stop listening` | Help / stop |

## Overlays

- **Top bar**: shows live speech, the result of each command, and the current mode
  (listening, dictating, grid, paused).
- **Tap & swipe indicators**: ripples where it taps, a ring for long-press, arrows for swipes.

Both can be switched off in the app.

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
