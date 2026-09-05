# Voice V2 existing-call sideband contract

MobileClaw's Frameless Bidi adapter creates media through the ChatGPT realtime call
endpoint and attaches client delegation control directly to
`https://api.openai.com/v1/live/<call_id>`.

The existing-call attachment is one logical continuation of call creation, not a new
session. It therefore carries the exact `call_id` returned in `Location` and the same
ephemeral `session-id`, `thread-id`, and `x-session-id` values used for call creation.
The sideband resolves fresh authentication at connect/reconnect time and includes the
same bearer, ChatGPT account, originator, residency, and `openai-alpha:
quicksilver=v2` contract headers. Bearer credentials are never retained in the typed
attachment.

The concrete pre-fix difference was that call creation sent all three session identity
headers while the sideband sent only the common ChatGPT authentication headers. That
made media success independent of—and unable to prove—the direct sideband attachment.

Client delegation events are `delegation.created` objects whose item has type
`delegation`, target `client`, an item id, and concatenated `input_text` content.
Results are returned for that same id with `delegation.context.append`; UTF-8 content
is divided into chunks of at most 500 bytes and preserves the requested commentary or
speakable channel.

## Delegation content semantics

Current Codex Frameless Bidi protocol and integration fixtures use natural handoff text
such as `check the weather` and `hello world`. The `input_text` is the normalized client
handoff transcript; it is not required to be a MobileClaw JSON control envelope. Codex
passes that handoff input to downstream client-managed work, and the client returns
progress/result input text using `delegation.context.append` for the same delegation
item id.

MobileClaw therefore treats a validated `delegation.created` event targeting `client`
as the provider assertion that its natural text is client work. When no Voice-owned
phone task exists, natural text becomes a `Start` goal. While one exists, natural work
becomes `Replace`, preventing overlapping `PHONE_CONTROL` execution. Narrow local
phrases for phone-task cancellation and status become `Cancel` and `Status` instead.
The strict JSON envelope remains supported as an optional, unambiguous internal control
form, especially for status, cancellation, and replacement; it is not a Frameless wire
requirement. Malformed JSON-like content is rejected instead of being executed as a
natural Android goal.
