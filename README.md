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
| `click mode on` / `stop clicking` | While on, just say the text of a button or link (or its number) and it is clicked. No “click” or “tap” needed. Other commands still work when nothing on screen matches |
| `scroll down/up/left/right`, `scroll to top/bottom` | Scroll |
| `swipe left/right/up/down`, `refresh` | Swipe gestures |
| `go back`, `go home`, `recents`, `notifications`, `quick settings` | Navigation |
| `screenshot`, `lock screen`, `power menu`, `split screen` | System actions |
| `open <app name>` | Launch an app |
| `type <text>`, `start dictation` / `stop dictation` | Typing |
| `select all`, `copy`, `paste`, `cut`, `delete`, `delete word`, `clear text`, `new line`, `enter` | Editing |
| `volume up/down`, `volume full/low`, `volume 40 percent`, `ring volume down`, `mute`, `unmute` | Volume (media, ring, alarm, notification, call) |
| `brightness up/down`, `brightness full/low`, `brightness 60 percent`, `brightness auto` | Screen brightness (needs “Modify system settings”) |
| `turn on/off Wi‑Fi`, `Bluetooth`, `hotspot`, `airplane mode`, `location`, `mobile data`, `NFC`, `do not disturb`, `flashlight`, `auto-rotate`, `battery saver`, `dark mode`, `night light`… | System switches. Any Quick Settings tile name works too, and `toggle <name>` flips it |
| `silent mode`, `vibrate mode`, `ring mode` | Sound mode |
| `pause listening` / `resume listening` | Ignore everything except the wake phrase |
| `show commands`, `stop listening` | Help / stop |

## App layout

A bottom bar with three icons:

- **Home**: the orb. Tap to start, turn listening off and on. It breathes and reacts to your voice.
- **Commands**: your custom commands and every built-in command, each with an on/off switch,
  plus search, New / Examples / Export / Import and Gestures.
- **Settings**: permissions, top bar, grid size, animations, Root / Shizuku, how system switches are
  changed, and external triggers.

The listening notification has a **Turn off / Turn on** button (and **Stop**), so you can pause the
microphone without leaving the app.

## System switches

Flashlight and auto-rotate use direct Android APIs. Everything else (Wi‑Fi, Bluetooth, hotspot,
airplane mode, any other tile) works by opening Quick Settings and tapping the tile, because Android
no longer lets apps flip those directly. In *Settings → System controls* you can switch to Root or
Shizuku to use direct commands instead (`svc wifi enable`, `cmd connectivity airplane-mode enable`…)
where they exist. Tile names depend on your phone and language; use the name you see in Quick Settings.

## Overlays

- **Top bar**: two styles. *Only while speaking* (default) shows your live words and the result
  (for example `“scroll down” → done`), then hides. *Always visible* keeps a `● Listening` pill on
  screen and shows the current mode (dictating, grid step, paused).
- **Tap & swipe indicators**: ripples where it taps, a ring for long-press, arrows for swipes.

Both can be switched off in the app.

## Custom commands

Open the app → **Custom commands**.

- **Trigger phrases**: as many as you like. `{text}` can sit anywhere (`search for {text}`,
  `open {text} app`) and captures what you said there.
- **`{text}` and other placeholders work in every field of every action**: app names, URLs,
  tap positions, wait times, intent extras, shell commands… Also available: `{clipboard}`,
  `{time}`, `{hour}`, `{minute}`, `{date}`, `{weekday}`, `{battery}`, `{app}`, `{app_name}`,
  `{random}`, `{error}`, and your own variables. Modifiers: `{text:url}`, `{text:sh}`,
  `{text:upper}`, `{text:lower}`, `{text:trim}`.
- **Flow control**: *If / Else if / Else / End if*, *Repeat N*, *While*, *End loop*, *Break*,
  *Continue*, *Wait until*, *Stop*, *Try / Catch / End try*, *Run another command*.
  Conditions can be combined (AND / OR) and inverted (NOT). Checks: value comparison
  (equals, contains, starts/ends with, regex, empty, numeric <, >, ≤, ≥), foreground app,
  text on screen, app installed, time window, day of week, battery level, charging, internet,
  Wi-Fi, device locked, orientation, random chance.
- **Variables**: *Set variable* (set, add, subtract, multiply, append, clear; this run or saved
  across runs) and *Read into variable* (clipboard, focused field, screen text, foreground app…).
- **Touch & gestures**: tap / double tap / long press / swipe at a position (in %), recorded
  gestures, scroll, click text.
- **Apps, links & shortcuts**: open app, open link, **trigger another app's shortcut** (the ones
  shown when you long-press an icon; pick with *Browse*), **custom intents** (activity,
  broadcast or service with extras), shell command.
- **Keys & text**: system keys, type text, run a built-in command, copy to clipboard.
- **Feedback**: message in the top bar, speak aloud, vibrate.
- **Repeat** the whole sequence up to 99 times; optionally keep going when an action fails.
- **Run with**: *Normal* (accessibility), *Root* (`su`) or *Shizuku*. Root and Shizuku send taps,
  swipes and key presses as shell `input` commands, can start app shortcuts that aren't
  exported, and unlock the **Shell command** action.
- **Shortcut triggering**: pin a home-screen shortcut for any command, or fire one from other apps
  (turn on *Allow other apps to trigger commands*):
  `adb shell am start -a app.openvoice.action.RUN_COMMAND --es name "My command" --es text "hello"`
- `run <name>` runs a command by name; `stop macro` cancels the running one.
- **Export / Import** via the clipboard (commands and the gestures they use).

Notes: reading `{clipboard}` can return nothing on newer Android when Open Voice isn't the focused
app. App shortcuts only cover the *static* shortcuts an app declares.

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
