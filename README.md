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
| `show grid`, then a cell number | Numbered grid: saying a number zooms in, and clicks on the last step. Works in any app |
| `set grid big` / `medium` / `small` | Big 3×5 → 3×3 → 3×3 → click · Medium 9×15 → 3×3 → click · Small 27×45 → click |
| `undo` (while the grid shows) | Go back one grid step |
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

- **Top bar**: two styles. *Only while speaking* (default) shows your live words and the result
  (for example `“scroll down” → done`), then hides. *Always visible* keeps a `● Listening` pill on
  screen and shows the current mode (dictating, grid step, paused).
- **Tap & swipe indicators**: ripples where it taps, a ring for long-press, arrows for swipes.

Both can be switched off in the app.

## Custom commands

Open the app → **Custom commands**.

- **Trigger phrases**: as many as you like. End one with `{text}` to capture words, e.g.
  `search for {text}` → open URL `https://www.google.com/search?q={text}`.
- **Actions** (any number, reorderable): tap / double tap / long press / swipe at a screen
  position (in %), play a recorded gesture, click text, wait, open app, open link, press a key or
  system action, type text, scroll, run a built-in command, shell command, run another custom
  command, show a message.
- **Repeat** the whole sequence up to 99 times; optionally keep going when an action fails.
- **Run with**: *Normal* (accessibility), *Root* (`su`) or *Shizuku*. Root and Shizuku send taps,
  swipes and key presses as shell `input` commands and unlock the **Shell command** action.
  Multi-finger gestures only replay faithfully in Normal mode.
- **Shortcut triggering**: add a pinned home-screen shortcut for any command, or fire one from
  other apps (turn on *Allow other apps to trigger commands*):
  `adb shell am start -a app.openvoice.action.RUN_COMMAND --es name "My command" --es text "hello"`
- `run <name>` runs a command by name; `stop macro` cancels the running one.
- **Export / Import** via the clipboard (commands and the gestures they use).

## Gestures

Open the app → **Gestures** → **Record new gesture**. After a 5 second countdown you draw over
whatever app is on screen (several strokes, several fingers). Recorded gestures are stored as
screen fractions, so they work on any resolution, and can be used as an action.

## Root and Shizuku

- **Root**: tap *Test root access* once and approve the prompt from your root manager.
- **Shizuku**: install and start Shizuku, then tap *Grant Shizuku permission*.

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
- `MacroEngine`: runs custom-command actions in Normal / Root / Shizuku mode.
- `GestureRecorder`: overlay that records multi-touch gestures.
- `ListenService`: foreground service running Android's `SpeechRecognizer` in a loop
  (offline preferred when a language pack is installed).
- `CommandParser`: turns recognised phrases into `Command`s.

## Roadmap

- Offline recognition with Vosk and a restricted command grammar
- Fuzzy matching for `click <text>`
- Settings screen, custom commands, language selection
- Wake word / pause-resume

## License

MIT
