# MobileClaw Workspace Architecture

## Status

This document describes the current workspace boundary at a high level. The implementation is authoritative; this is not a proposal for a universal filesystem or a group workspace.

## User-facing areas

The Workspace page brings together local artifacts that users can inspect and manage. Role workspace data remains scoped to an individual role. Agent Town rooms are also role-scoped and are managed through `AgentTownStore`; they are not a shared group workspace.

Current data areas include:

- conversation and task artifacts;
- role profiles, role memory, and role workspace records;
- installed skills and Skill Market metadata;
- MiniAPP projects and their runtime data;
- Agent Town rooms, including pinned `RoomArtifact` and `RoomTool` records;
- model, gateway, and device configuration.

Each subsystem owns its storage and validation rules. The Workspace UI is an index over those stores rather than a second source of truth.

## Boundaries

- User content is Unicode and must be preserved without language-specific rewriting.
- Secrets, credentials, and private endpoint configuration are not portable workspace content by default.
- Imports must validate their own schema and paths before writing data.
- Exports should be explicit about included user data and should omit transient caches.
- MiniAPP import and export uses the MiniAPP package boundary, not a generic workspace archive.
- Role Market packages and generated catalog checksums are maintained as a separate generated unit.

## Navigation and presentation

Workspace lists follow the shared conversation-list visual language documented in [mobile-claw-ui-style.md](mobile-claw-ui-style.md). Entries use a leading tile, title, muted metadata, an optional action, and inset dividers. Feature-specific detail screens remain responsible for editing and destructive actions.

## Evolution

Add workspace integrations only when a subsystem has a stable ownership boundary, a safe list/read operation, and clear import/export behavior. Do not revive the removed Groups, Arena, old Game, group-chat, or group-workspace architectures under the Workspace name.
