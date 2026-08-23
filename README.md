<div align="center">

<img src="docs/logo.png" alt="MobileClaw" width="148" />

# MobileClaw

### An autonomous AI agent and phone operator for Android.

MobileClaw connects language models to Android perception, accessibility, and tool APIs. It can inspect a screen, reason about a task, take a bounded action, and verify the result. The project is designed for practical phone automation rather than a simulated multi-agent environment.

[![Android](https://img.shields.io/badge/Android-11%2B-111111?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2-111111?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-111111?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-MIT-111111)](LICENSE)

</div>

## What MobileClaw Does

MobileClaw provides an observe → reason → act → verify loop for Android tasks:

- Reads the current screen through AccessibilityService, accessibility-tree data, screenshots, and Set-of-Mark annotations.
- Interacts with apps using taps, swipes, scrolling, long presses, text input, Back, Home, and app launching.
- Operates foreground apps and supports background/virtual-display flows where the device and ROM allow them.
- Routes supported requests across configured cloud gateways and installed local text models.
- Uses specialized roles without changing the underlying single-agent task runtime.
- Maintains conversation context, durable memory, task workspaces, and role workspaces.
- Exposes skills for web research, files, shell/Python tasks, MCP connections, and other installed tools.
- Creates and opens MiniAPPs and native AI Pages for interactive or reusable results.
- Includes image/video generation integrations, VPN helpers, and a desktop Codex bridge.

Actual behavior depends on the selected model, granted Android permissions, installed apps, device ROM, network access, and configured providers. Some tasks require cloud credentials, and not every Android surface exposes enough accessibility information for reliable automation.

## Screenshots

<p align="center">
  <img src="docs/media/mobileclaw_real_chat_start.png" alt="MobileClaw chat task" width="300" />
  <img src="docs/media/mobileclaw_real_miniapp_pocket_synth_clean.jpg" alt="MobileClaw MiniAPP" width="300" />
  <img src="docs/media/mobileclaw_real_ai_page_result_clean.jpg" alt="MobileClaw native AI Page" width="300" />
</p>

## Requirements

- Android 11 or newer
- Android Studio with JDK 21
- Python 3.11 for Chaquopy builds
- An OpenAI-compatible endpoint for cloud model use, or a supported installed local text model

Phone control requires enabling MobileClaw's AccessibilityService. Background operation may require additional OEM-specific permissions or setup.

## Build

```bash
git clone https://github.com/kevinmetten/metten.ai.git
cd metten.ai
./scripts/assemble_debug.sh
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

For configuration, permissions, a first safe task, and troubleshooting, see the [Quickstart](docs/quickstart.md).

## Useful Documentation

- [Quickstart](docs/quickstart.md)
- [Phone-control recipe](docs/recipes/phone-control.md)
- [Codex desktop bridge](docs/recipes/codex-desktop-bridge.md)
- [Skill authoring](docs/recipes/skill-authoring.md)
- [Role-driven chat runtime](docs/role-driven-chat-runtime.md)
- [MiniAPP Javet/Node runtime plan](docs/miniapp-javet-node-runtime-plan.md)

## Pgyer Release Helper

```bash
python3 scripts/pgyer_release.py build-upload \
  --gradle-task assembleDebug \
  --notes "MobileClaw Android agent update"
```

Keep Pgyer secrets in `local.properties`, `.pgyer.env`, or environment variables. Do not commit them.

## Safety

Start with observation-only or reversible tasks. Review permissions carefully, use test accounts and API keys when developing, and avoid payments, destructive changes, or sensitive messages until a workflow has been validated on your device.

## License

MIT. See [LICENSE](LICENSE).
