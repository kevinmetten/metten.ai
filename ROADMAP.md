# MobileClaw Roadmap


This roadmap focuses on making MobileClaw easier to try, safer to extend, and more useful as an open Android agent ecosystem. Dates are intentionally not promised; the project should move by stability and reproducibility rather than marketing deadlines.

## 0.1.x: Make The Project Easy To Try

- Publish installable APKs from tagged GitHub Releases.
- Keep [docs/quickstart.md](docs/quickstart.md) current with build, install, permission, and model setup steps.
- Add short demo recordings for phone control, skill execution, AI Page generation, and VPN control.
- Collect ROM compatibility reports through GitHub issue templates.
- Document known limitations for AccessibilityService, screenshots, virtual display, foreground services, VPN, and WebView mini apps.
- Add at least three reproducible recipes under `docs/recipes`.

## 0.2.x: Make Contributions Repeatable

- Add focused tests around task classification, tool policy, skill loading, dynamic skill parsing, and VPN config parsing.
- Define a stable skill package format and review checklist.
- Add sample dynamic Python and HTTP skills.
- Track known device and ROM behavior in public documentation.
- Add a contributor-facing debugging guide for screen reading, action execution, and task logs.

## 0.3.x: Make Skills An Ecosystem

- Improve skill search, installation, review, and promotion flows.
- Show provenance and permission summaries for installed skills.
- Add a documented submission path for ClawHub-compatible skills.
- Provide example roles and task recipes for phone operators, coders, researchers, creators, and VPN operators.
- Make skill failures easier to inspect and reproduce.

## Later

- Better VLM grounding and action verification.
- Better long-running task recovery and interruption.
- More reliable background virtual-display support across ROMs.
- Stronger local-model routing for text-only tasks.
- More complete privacy and security controls for screen content, files, generated tools, and local/LAN APIs.
