# Recipe: Control Desktop Codex From MobileClaw

MobileClaw can send coding tasks from your phone to a Codex CLI process running on your computer through a small LAN bridge.

## 1. Start The Bridge On The Computer

From the `mobileClaw` repo root on the computer:

```bash
CODEX_BRIDGE_TOKEN=change-me python3 scripts/codex_desktop_bridge.py
```

Optional:

```bash
CODEX_BRIDGE_PORT=52734
CODEX_BRIDGE_COMMAND='codex exec --ask-for-approval never'
```

Keep the terminal open while using the bridge. Make sure the computer firewall allows the selected port.

## 2. Configure The Phone

In MobileClaw:

1. Open Settings.
2. Open Codex Bridge.
3. Set `Bridge URL` to `http://<computer-lan-ip>:52734`.
4. Set `Token` to the same `CODEX_BRIDGE_TOKEN`.
5. Optionally set the default desktop working directory.
6. Tap Test Connection.

## 3. Use It In Chat

Examples:

```text
Use desktop Codex in /Users/me/project to investigate the test failure.
```

```text
Show the desktop Codex bridge status.
```

```text
Stop the desktop Codex task.
```

The built-in `codex_desktop` skill supports `status`, `run`, and `stop`.
