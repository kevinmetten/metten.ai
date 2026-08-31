# MiniAPP Runtime: Current State and Future Node Builder

## Current implementation

MiniAPP is active functionality. `MiniAppStore` owns metadata and HTML content, `AppManagerSkill` exposes creation and management actions, and `MiniAppActivity` renders content in a controlled WebView. Preflight validation checks content before it is opened. Import, export, package sharing, the JavaScript bridge, and optional Python-backed behavior belong to this subsystem.

The checked-in runtime is HTML-oriented. It does **not** currently embed Javet or provide a general Node or npm environment.

## Future direction

A project-oriented MiniAPP format could support multiple source files and a controlled build step for frameworks such as Vue and TypeScript. Javet is one possible implementation, but it remains a design option rather than a shipped capability.

Any future builder must:

- run only allowlisted build operations inside a MiniAPP project directory;
- avoid arbitrary shell or npm execution;
- keep source, generated output, manifest, permissions, backend, and user data in explicit locations;
- produce structured build diagnostics;
- validate paths, package contents, bridge capabilities, and WebView navigation;
- retain compatibility with existing HTML MiniAPP imports.

## Package boundary

A future project package should define a versioned manifest, source tree, built `dist/` tree, optional backend, and declared capabilities. Import must reject traversal, oversized entries, unsupported schema versions, and undeclared native access. Export must state whether user data is included.

## Delivery rule

Implement this direction incrementally behind the existing MiniAPP boundary. Do not claim Node/Javet availability until the dependency, ABI support, builder isolation, package migration, and runtime validation are implemented and tested on Android.
