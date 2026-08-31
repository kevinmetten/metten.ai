# Security Policy


MobileClaw can read screen state, control Android apps through AccessibilityService, run dynamic skills, execute Python, and manage VPN/proxy state. Treat every change in these areas as security-sensitive.

## Supported Versions

Security fixes are handled on the default branch until the project starts publishing stable release lines.

## Reporting A Vulnerability

Please do not open a public issue for a vulnerability that exposes private screen content, files, credentials, network traffic, model prompts, memory, or device-control capability.

Report privately through the repository owner's preferred GitHub contact path. Include:

- A clear description of the issue.
- Steps to reproduce.
- Device model, Android version, ROM, and app version or commit.
- Whether root, Shizuku, ADB helper, VPN, dynamic skills, shell/Python execution, WebView, or local/LAN APIs were involved.
- Logs or screenshots with secrets and personal data removed.

## Security-Sensitive Areas

- AccessibilityService screen reading, screenshots, gestures, and text input.
- Dynamic Python and HTTP skills.
- Shell execution and privileged helper flows.
- Local and LAN API servers.
- File access and attachment handling.
- VPN, proxy, and subscription parsing.
- WebView JavaScript bridges for mini apps.
- Model prompt construction, memory injection, and tool observations.

## Project Rules

- Do not add hidden telemetry.
- Do not upload screen contents, files, app lists, memory, or VPN configs without explicit user action.
- Do not make generated skills privileged by default.
- Keep dangerous tools inspectable and scoped to the active task.
- Prefer deny-by-default behavior when a tool is uncertain.
- Redact API keys, tokens, proxy credentials, phone numbers, private URLs, and personal screenshots from logs.
