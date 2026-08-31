# Privacy Notes


MobileClaw is designed around user-authorized Android capabilities. Some features can access sensitive local information, so data flow must stay explicit, reviewable, and easy to explain.

## Data MobileClaw May Process

Depending on enabled permissions and the active task, MobileClaw may process:

- Screen text, UI hierarchy, screenshots, and tap coordinates.
- User messages, attachments, generated files, and local memory.
- Installed app metadata when app-control tools are used.
- VPN/proxy subscription data if the VPN feature is configured.
- Web pages fetched by web tools or hidden WebView flows.
- Model prompts and selected task context sent to an OpenAI-compatible endpoint.

## Local First, But Not Always Local

Many features store data on the device. However, cloud model usage can send prompts, selected observations, and relevant task context to the configured model endpoint. Users should configure only endpoints they trust.

Local model mode is intended for text-only requests when available. Tool calls, image input, web access, or unavailable local models may fall back to the configured cloud endpoint.

## Contributor Requirements

- Do not introduce telemetry unless it is documented, optional, and disabled by default.
- Do not upload screenshots, files, app lists, memory contents, VPN configs, or generated artifacts without explicit user action.
- Clearly document any new external network request.
- Keep generated tools and skills scoped to user intent.
- Redact API keys, tokens, proxy credentials, phone numbers, private URLs, and personal screenshots from logs.

## User Safety Notes

MobileClaw is not a polished commercial assistant. Review permissions carefully, especially AccessibilityService, overlay, file access, VPN, local/LAN APIs, WebView mini apps, Python/shell execution, and dynamic skills.

Use a test API key while experimenting. Avoid giving the agent tasks that involve payments, account changes, private messages, or destructive actions until you understand the permission and model flow.
