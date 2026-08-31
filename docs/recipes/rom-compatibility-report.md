# Recipe: ROM Compatibility Report


Android automation behavior differs across OEM ROMs. This checklist helps contributors report useful compatibility data.

## Device

- Device model:
- Android version:
- ROM name and version:
- Security patch level:
- MobileClaw release or commit:

## Permissions And Helpers

- AccessibilityService enabled:
- Screenshot permission granted:
- Notification permission granted:
- Overlay permission granted:
- Battery optimization disabled:
- VPN permission granted:
- Root available:
- Shizuku available:
- ADB helper used:

## Feature Results

| Feature | Works | Notes |
| --- | --- | --- |
| Screen XML reading |  |  |
| Screenshot capture |  |  |
| Tap gesture |  |  |
| Scroll gesture |  |  |
| Text input |  |  |
| App launch |  |  |
| Virtual display launch |  |  |
| Background screenshot |  |  |
| VPN start/stop |  |  |
| WebView mini app |  |  |
| AI Page rendering |  |  |

## Reproduction

```text
Task prompt:

Expected:

Actual:
```

Attach screenshots or short recordings when possible. Remove private data first.

## Useful Notes

- Mention any OEM permission page that had to be changed.
- Mention whether battery optimization, autostart, floating window, or background activity settings affected behavior.
- If virtual display fails, include the exact error message if available.
- If gestures miss, include screen resolution and display scaling settings.
