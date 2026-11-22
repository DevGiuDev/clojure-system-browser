# Changelog

All notable changes to this project are documented in this file. The format
is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The
version lives in `csb.cli/version` and is kept in sync with this file
manually ([ADR 011](docs/decisions/011-versioning-changelog.md)). While the
project is 0.x, minor bumps carry features and the MVP stays free to evolve.

## [0.3.0] - 2026-11-22

### Added

- Local nREPL panel in Desktop and TUI with a multiline console, explicit
  definition evaluation, streamed output and interruption (ADR 013).
- Prefill the nREPL port from `.nrepl-port` in the open project's root.
- Configurable nREPL host and a shared inspector for loaded namespaces, vars,
  metadata and source/runtime presence in Desktop and TUI (ADR 014).
- The `(requires)` selector in Desktop and TUI shows the selected namespace's
  `:require` and `:require-macros` declarations in a read-only view (ADR 009).
- Java VMs in Desktop and TUI selects a local JDK per project for test
  processes, with a list of detected installations and manual path entry
  (ADR 017).
- Desktop visual theme (ADR 018): a light palette with green-tinted surfaces
  and a teal accent centralized in `theme.css`, one 16 px vector icon family
  for actions, views, list headers, definition kinds and operation states,
  a filter icon and primary emphasis for `Open project…` and `Save`.

### Changed

- Operation severity travels as data: `csb.session` attaches a
  `:status-tone` to status messages, so conflicts render amber, other
  failures red and confirmed saves green, with draft and error shown
  simultaneously; the editor stylesheet shares the theme palette variables.

## [0.2.0] - 2026-11-21

### Added

- Category facets with counts and a namespace summary: `list-categories`
  returns `{:total :files :facets}`; Desktop and the TUI show facets with an
  "(all)" sentinel, unfiltered by default (ADR 009).
- Source order view: `list-source-forms` exposes all significant top-level
  forms per file in real file order, including forms that are not indexed
  definitions; Desktop has a **Source order** button and the TUI an `o`
  panel (ADR 008).
- Polylith workspace discovery (ADR 010): when `deps.edn` declares no
  top-level `:paths` and the project carries a `workspace.edn` (or the older
  `.polylith` configuration) or both `components` and `bases` folders, the
  conventional brick roots are derived from the filesystem alone —
  `components/*/src|test`, `bases/*/src|test`, `development/src|test` and the
  rare `projects/*/src`. Explicit `--source-root` options and `deps.edn`
  `:paths` keep precedence. Closes issue #1.
- `examples/poly-sample-project`, a minimal Polylith-shaped workspace.
- Version string: `csb.cli/version` shown by the CLI, Desktop and TUI
  `--help` output and in the Desktop window title (ADR 011).
- Release packaging (ADR 012): `build.clj` builds the TUI/CLI uberjar
  (`clojure -T:build uber`) with no JavaFX inside, and a build-only,
  tag-triggered workflow publishes the jar and the `csb` launcher script to
  GitHub Releases for installation on `PATH`.

## [0.1.0] - 2026-11-21

### Added

- Source World MVP (milestones 0–4): definition index across the project's
  source roots, namespace navigation, category filtering, drafts, external
  `$EDITOR` editing over temporaries, and Save through the service with
  atomic replacement, byte preservation outside the edited range
  (BOM, LF/CRLF/CR, Unicode), revision and external-change conflict
  detection, and explicit error types.
- `.cljc` browsing and read-only ClojureDart `.cljd` support.
- Desktop client (JavaFX 27 through cljfx, headless-tested) and TUI client
  (Lanterna, virtual-terminal-tested) consuming the same service; lexical
  syntax highlighting in both code panels; TUI dark/light themes,
  change-driven refresh and external editing with a real editor in a PTY.
- Extension registry v1 with commands and views; example extension.
- Headless service facade (`csb.service`) with a CLI printing the index as
  EDN.
- Documentation set: architecture, decisions (ADR 001–007), roadmap,
  acceptance and verification; `examples/sample-project`.
