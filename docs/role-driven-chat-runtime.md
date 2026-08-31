# Role-driven Chat Runtime

## Purpose

Roles influence chat through explicit runtime policy rather than language-specific trigger words. The implementation separates role resolution, context construction, execution-mode selection, tool policy, response policy, persistence, and trace reporting.

## Current flow

1. Normalize the user request without changing user-authored Unicode.
2. Resolve the active role and compile its `RoleChatControlPlan`.
3. Build bounded conversation, memory, role-workspace, and attachment context.
4. Use the intent router and execution policy to choose direct chat, agent execution, informational handling, or the Codex Desktop route.
5. Select only skills allowed by the role and current task.
6. Persist the visible response and any approved role-memory or artifact updates.

`RoleChatRuntimeBridge`, the runtime ports under `ui/chat/runtime`, and `MainViewModel` implement this boundary. Source code remains authoritative when details change.

## Policy boundaries

- Local safety rules and capability checks override role instructions.
- A role can shape context, tone, skill preference, and persistence, but cannot grant itself permissions.
- `AiIntentRouter` and `AiTaskRouteDecision` remain the canonical routing structures.
- Execution is based on task semantics, including `ContextualTaskIntent.aiRequiresExecution`, rather than Chinese-only keywords.
- Image decisions use `ImageRoutingSemantics`; artifact updates use `ArtifactChangeClassifier`.
- Arbitrary Unicode in messages, names, metadata, and stored content is preserved.

## Agent Town and workspace

Agent Town is current. Role outputs can become role-scoped `RoomArtifact` records, and relevant skills can be represented as `RoomTool` records. This does not create group chat or a shared group workspace.

## Testing

Focused tests should cover routing semantics, context limits, role policy compilation, skill restrictions, persistence decisions, image routing, artifact classification, and Unicode preservation. Compatibility tests may load old fields, but new serialization must use the current single-path schemas.
