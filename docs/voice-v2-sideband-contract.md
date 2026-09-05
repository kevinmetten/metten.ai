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
