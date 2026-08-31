# mobileClaw Role Market

This directory is a checked-in static Role Market snapshot. The Android app does
not currently fetch or display this catalog; roles are installed through the
app's generic `.mobileclaw-role` package importer.

- `templates.json`: the maintainable source definitions, search keywords, and
  historical avatar prompts.
- `avatars/`: checked-in generated role avatars.
- `packages/`: checked-in installable `.mobileclaw-role` ZIP archives. Each
  archive contains `manifest.json`, `role.json`, workspace Markdown, and a copy
  of its avatar.
- `index.json`: a generated catalog snapshot containing selected template
  metadata, download URLs, Skill dependencies, and a SHA-256 checksum of each
  package archive.

The historical avatar and package/catalog generator scripts are not present in
this fork, and there is no maintained replacement workflow to invoke. The
checked-in assets must therefore not be presented as reproducible from this
checkout. In particular, do not edit canonical role metadata in
`templates.json` or `index.json` alone: canonical names and descriptions are
also embedded in each package's `role.json`, and package metadata is repeated in
`manifest.json` and workspace Markdown. Rebuilding a package changes the bytes
covered by its `index.json` SHA-256 value.

Avatar generation and package generation are separate concerns. A future
maintained package builder can reuse the checked-in avatar files and embed them
without regenerating images. Binary package/catalog regeneration is deferred
until that maintained workflow exists.

## Current migration boundary

The snapshot still has legacy bilingual catalog fields: `name` and
`description` are Chinese canonical metadata, while `nameEn` and
`descriptionEn` are catalog-only historical localization fields. The Android
`Role` runtime schema has only `name` and `description`; it does not consume the
`*En` fields. Multilingual `keywords` are searchable role data rather than an
app-language switch and should remain capable of storing arbitrary Unicode.

All current packages embed their Chinese canonical name and description, so an
import installs those values regardless of the corresponding `index.json`
entry. The catalog's checked-in `eggbrid2/mobileClaw` raw-content URLs are stale
inherited publication metadata, not the catalog used by this app. They should
move to this repository's publication location only as part of a consistent
rebuild; changing the catalog alone would leave package-internal metadata and
checksums out of sync.

The next binary-capable migration must restore or introduce a reviewed,
maintained builder, make English the canonical authored name and description in
`templates.json`, remove localization-only `nameEn` and `descriptionEn`, retain
intentional Unicode proper names and search aliases, rebuild every affected
package using the existing avatars, verify package-internal manifests, role
definitions, and workspace files, recompute every SHA-256 value, update all
publication URLs to the `kevinmetten/metten.ai` catalog location, regenerate
`index.json`, and validate installation end to end. Until then,
`templates.json`, `index.json`, avatars, and packages remain an internally
consistent historical snapshot and are intentionally unchanged.

Roleplay templates are unofficial community templates. Character names and source works belong to their respective rights holders. The prompts in this repository are original and do not copy third-party character-card text.
