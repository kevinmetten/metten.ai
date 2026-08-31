# Recipe: Phone Control Smoke Test


Use this recipe to verify basic screen reading, app launch, tapping, and result verification on a device.

## Device Notes

Record these before testing:

- Device:
- Android version:
- ROM:
- MobileClaw version or commit:
- Root, Shizuku, or ADB helper:
- Permissions granted:
- Screen resolution:

## Task 1: Read Current Screen

Prompt:

```text
Look at the current screen and summarize what page I am on. Do not tap anything.
```

Expected result:

- The agent describes the visible app or launcher.
- No tap, scroll, or navigation happens.

Failure signals:

- The agent reports an unrelated screen.
- The agent taps or navigates despite the instruction.
- Accessibility XML is empty and screenshot fallback also fails.

## Task 2: Open An App

Prompt:

```text
Open Calculator, wait until it is visible, and tell me when it is ready.
```

Expected result:

- Calculator opens.
- The agent verifies the screen before responding.

Failure signals:

- App launch fails.
- The wrong app opens.
- The agent responds before observing the launched app.

## Task 3: Perform A Simple Action

Prompt:

```text
In Calculator, calculate 23 + 19 and tell me the verified result.
```

Expected result:

- The app shows `42`.
- The agent reports the result after observing the screen.

Failure signals:

- Taps miss keys.
- Text input or gestures do not work.
- The agent reports a result without verifying the screen.

## Failure Report

If the test fails, include:

- Which task failed.
- Whether XML reading, screenshot reading, app launch, tap, scroll, or input failed.
- The visible screen before and after the failure.
- Relevant logs with secrets removed.
- Device model, Android version, ROM, and permission state.
