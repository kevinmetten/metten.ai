# MobileClaw Quickstart


This guide helps you build the app, install a debug APK, grant the minimum required permissions, and run a first safe Android agent task.

## 1. Requirements

- Android Studio Ladybug or newer
- JDK 21
- Android 11+ phone or emulator
- Python 3.11 available for Chaquopy builds
- An OpenAI-compatible chat endpoint and API key

Use a real device when testing phone control. Emulators are useful for basic UI checks, but OEM ROM restrictions often differ.

## 2. Build

```bash
git clone https://github.com/kevinmetten/metten.ai.git
cd metten.ai
./scripts/assemble_debug.sh
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The build helper selects Android Studio's bundled JBR 21 when it exists and sets
`NO_PROXY=*` for Chaquopy/pip. If Android Studio still fails while installing
Python requirements, set the Gradle JDK to JBR 21 and add `NO_PROXY=*` to the
Gradle environment.

## 3. Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If the install fails, check that USB debugging is enabled and that your device allows installs from ADB.

## 4. Configure A Model

Open MobileClaw settings and configure an OpenAI-compatible endpoint and API key.

Use a test key while experimenting. Avoid using a production key while testing dynamic skills, WebView tools, phone automation, or VPN flows.

## 5. Grant Only The Permissions You Need

Common permissions:

- AccessibilityService: screen reading, gestures, text input, and app automation.
- Notification: foreground status and AI Page notifications.
- Overlay/background permissions: long-running visual assistant behavior.
- VPN: only needed for VPN/proxy features.
- File/media access: only needed for attachments and user storage tools.

Root is not required for basic use. Some background virtual-display flows may need ROM-specific settings, root, or an ADB-activated helper.

## 6. First Safe Tasks

Start with an observation-only task:

```text
Look at the current screen and summarize what page I am on. Do not tap anything.
```

Then try a bounded action:

```text
Open Calculator and calculate 23 + 19. Tell me the result after you verify the screen.
```

Good first tasks have visible results and low risk. Avoid payments, account changes, private messages, or destructive actions while testing.

## 7. Troubleshooting

- If screen reading fails, confirm AccessibilityService is enabled and try a raw screenshot task.
- If taps miss targets, include the screen screenshot and device resolution in your report.
- If virtual display launch fails, report the ROM and whether root or ADB helper was used.
- If VPN fails, include subscription type, Android version, and whether the VPN permission dialog appeared.
- If a model call fails, confirm endpoint URL, API key, model name, and network proxy settings.

## 8. Report Device Results

Open a ROM compatibility issue and include:

- Device model
- Android version
- ROM name/version
- MobileClaw commit or release
- Permissions granted
- Whether root or ADB helper was used
- Task prompt
- Expected behavior
- Actual behavior

Use [docs/recipes/rom-compatibility-report.md](recipes/rom-compatibility-report.md) as a checklist.
