# Changelog

All notable changes to this project are documented in this file. The format
is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The
version lives in `csb.cli/version` and is kept in sync with this file
manually ([ADR 011](docs/decisions/011-versioning-changelog.md)). While the
project is 0.x, minor bumps carry features and the MVP stays free to evolve.

## [Unreleased]

### Added

- Top-level `(comment …)` forms appear under a counted **(comment)** category
  in Desktop and the TUI. The category lists the blocks by file and line and
  the code panel shows the whole block read-only, with or without a REPL
  connected. Evaluating a block (Desktop **Evaluate block** or TUI F6 in the
  nREPL panel) sends its body forms one by one in the source namespace,
  stopping at the first error; Desktop's **Eval in REPL** menu actions
  evaluate a captured Definitions row or the exact editor selection
  ([ADR 022](docs/decisions/022-comment-blocks-evaluation.md)).
- The example projects ship `(comment …)` blocks with expressions for their
  APIs, and all three can start a live nREPL (`:repl` alias, new in the
  Lazytest example) to test the browser's evaluation flow against a real
  runtime.
- Modal project search for namespaces and definitions in the TUI. Press `f`
  to open it.
- The **Tree** checkbox next to **Namespaces** in Desktop switches between
  the default flat list and expandable branches grouped by dots. Switching
  preserves the filter, selection and draft.
- Automatic **Uses** panel in Desktop: beside the source of the selected
  function, var, macro or multimethod it lists the definitions that reference
  it, with the relative file, the line of the first use and a count when there
  are several. Contextual lookup results take the pane back while open,
  ambiguous names show a note, and a save whose reindex fails marks uses stale
  until Reload (ADR 015).
- Desktop and TUI support Lazytest suites, with source navigation, scoped runs
  and results for each `defdescribe`. The
  [Lazytest sample project](examples/lazytest-sample-project/README.md) shows
  passing, failing and skipped suites.
- Projects that split their Clojure CLI test configuration across `:test`
  (extra paths, dependencies and JVM options) and `:runner` (the runner
  `:main-opts`) are now detected and run: the Test Runner recognizes a
  `:runner` alias when it launches Kaocha, Cognitect or Lazytest, composes
  every test alias in the child command so the classpath keeps the `:test`
  configuration, and **Run namespace** and **Run test** use the runner's
  native selectors instead of reporting these commands unavailable. A `test`
  task in `bb.edn` remains a suite-only command
  ([ADR 023](docs/decisions/023-runner-alias-convention.md)).
- Application settings as EDN with a global layer and a per-project
  override: global at
  `$XDG_CONFIG_HOME/clojure-system-browser/settings.edn`, project at
  `<project-root>/.csb/settings.edn`, merged over built-in defaults in that
  order. Desktop opens the settings overlay with a new gear button in the
  toolbar; the TUI panel opens with `g`. Each row shows the effective value
  and its origin (`default`/`global`/`project`) beside a `Global` /
  `This project` save scope. The first keys are `:theme` (TUI),
  `:namespace-tree?` and `:source-order?` (initial view on project open),
  `:repl` (`{:host, :port}` panel defaults; a detected `.nrepl-port` still
  wins) and `:editor-command` (an argv vector that now takes precedence over
  `CSB_EDITOR_ARGS`, `VISUAL` and `EDITOR`). Reading is tolerant (a broken
  layer warns and is skipped), saving is strict and atomic, and the project
  file is written only by an explicit save
  ([ADR 025](docs/decisions/025-settings-files.md)).

### Changed

- The Tests log has more space and highlights failures in red. Opening a
  failed test shows its first failure above the source and marks the reported
  line when it matches the indexed definition.

## [0.3.1] - 2026-11-22

### Added

- Filter box for the Desktop namespaces panel: case-insensitive substring,
  reset when another project is opened and preserved while navigating
  (ADR 019).
- Project search in Desktop with Ctrl+F / Cmd+F: a centered overlay with a
  dimmed backdrop lists matches grouped as namespaces first and then
  definition categories, arrows move the selection from the query field,
  Enter opens results through the usual draft guards, and Esc or a click on
  the backdrop closes the panel. The matching is the shared service use case
  `csb.service/search` — names only, never definition bodies — so the TUI
  can adopt it without core changes (ADR 019).
- README screenshots: real captures of the Desktop main view, the Ctrl+F
  search overlay and the TUI browsing an external 176-namespace project,
  stored under `docs/img` and shown at the top of the README.

### Changed

- The definition filter moved from the toolbar to the head of the
  Definitions column, aligned with the namespaces filter.
- README: new Installation section ahead of Current status, moved out of
  Releases, which now only describes the tag-triggered workflow.

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
