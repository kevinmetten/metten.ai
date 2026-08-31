# Contributing To MobileClaw


MobileClaw is an experimental Android AI agent runtime. The most valuable contributions are small, reproducible, and easy to review. A report that explains one ROM problem clearly is often more useful than a large unbounded patch.

## What We Need Most

- ROM compatibility reports for AccessibilityService, screenshots, virtual display, foreground services, and VPN.
- Reproducible phone-control recipes with the target app, Android version, task prompt, expected result, and failure mode.
- Small skills or role presets that solve one clear task.
- Focused fixes for one Android permission, model, VPN, WebView, or automation edge case.
- Documentation, screenshots, short recordings, and setup notes.

## Before You Start

1. Read [docs/quickstart.md](docs/quickstart.md).
2. Search open issues for similar reports.
3. Keep changes scoped. Avoid mixing runtime behavior, UI polish, and documentation in one PR.
4. Read [SECURITY.md](SECURITY.md) before touching screen content, dynamic skills, shell/Python execution, VPN, local APIs, or WebView bridges.

## Development Setup

Requirements:

- Android Studio Ladybug or newer
- JDK 21
- Android 11+ device or emulator
- Python 3.11 available to Chaquopy
- An OpenAI-compatible chat endpoint and API key for cloud-model testing

Build:

```bash
./gradlew :app:assembleDebug
```

Run available checks:

```bash
./gradlew test
```

Use a real device for phone-control behavior. Emulators are useful for basic UI checks, but OEM ROM behavior is often different.

## Pull Request Expectations

Every PR should include:

- What changed and why.
- How you tested it.
- Device model, Android version, ROM, and whether root or an ADB helper was used for phone-control changes.
- Screenshots or a short recording for UI, automation, and permission-flow changes.
- Known limitations, especially around AccessibilityService, screenshots, virtual display, VPN, WebView, dynamic skills, and local/LAN APIs.

## Code And Design Guidelines

- Follow the existing Kotlin and Jetpack Compose style.
- Prefer explicit task boundaries and inspectable behavior over hidden automation.
- Keep new skills narrow. A small tool with clear inputs and outputs is easier to review and safer to promote.
- Keep dangerous tools disabled or on-demand unless there is a strong reason.
- Do not add telemetry or upload screen contents without explicit user action and documentation.
- Redact API keys, tokens, proxy credentials, phone numbers, private URLs, and personal screenshots from logs.

## Good Issue Reports

A useful issue usually includes:

- MobileClaw release or commit.
- Device model, Android version, and ROM.
- Permissions granted.
- Whether root, Shizuku, or ADB helper was used.
- The exact task prompt.
- Expected behavior and actual behavior.
- Logs, screenshots, or recordings with private data removed.

Use the ROM compatibility template for device-specific behavior.
