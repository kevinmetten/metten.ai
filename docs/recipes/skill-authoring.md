# Recipe: Small Skill Contribution


MobileClaw skills should be narrow, inspectable, and easy to disable. A good skill does one thing well and makes its data flow obvious.

## A Good Skill

- Has one clear purpose.
- Accepts explicit inputs.
- Returns structured, understandable output.
- Avoids hidden network requests.
- Avoids broad file, shell, or device privileges unless the user explicitly asks.
- Fails with a useful error message.
- Can remain on-demand instead of always injected.

## Contribution Checklist

- Skill name:
- Problem solved:
- Inputs:
- Output:
- External network requests:
- File access:
- Device permissions:
- Model calls:
- Failure behavior:
- Example prompt:
- Example result:
- Why this should be built in, listed in a market, or kept as a local custom skill:

## Review Questions

- What data can the skill read?
- What data can it send out?
- Can the user understand what it will do before running it?
- What happens on timeout, malformed input, or missing permissions?
- Does it need to be promoted, or can it stay on-demand?

## Example Prompt

```text
Create a skill that fetches a JSON endpoint and extracts the top-level keys.
```

Expected review notes:

- The skill should only access the URL provided by the user.
- It should use a timeout.
- It should not log the full response if it may contain secrets.
- It should return a concise list of keys and a clear error on invalid JSON.
