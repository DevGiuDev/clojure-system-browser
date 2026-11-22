# Roadmap

## Settings — 2026-11-22

CSB persists its own settings as EDN in two layers merged over built-in
defaults: a global file at
`$XDG_CONFIG_HOME/clojure-system-browser/settings.edn` and a per-project
override at `<project-root>/.csb/settings.edn`
([ADR 025](decisions/025-settings-files.md)). Both clients share one session
round-trip: the Desktop gear button opens a settings overlay and the TUI
panel opens with `g`; each row shows the effective value, its origin
(`default`/`global`/`project`) and a `Global` / `This project` save scope.
The first keys are `:theme` (TUI), `:namespace-tree?` and `:source-order?`
(initial view on project open), `:repl` (panel defaults, with a detected
`.nrepl-port` still winning for the port) and `:editor-command` (an argv
vector taking precedence over `CSB_EDITOR_ARGS`, `VISUAL` and `EDITOR`).
Reading is tolerant — a broken layer warns and is skipped — while saving is
strict and atomic; the project file is created only by an explicit save and
`deps.edn` is never touched. The per-project JDK preference (ADR 017)
remains in Java Preferences; migrating it into EDN is left for later.

## Test Runner alias convention — 2026-11-22

Projects that split their Clojure CLI test configuration across `:test`
(extra paths, dependencies and JVM options) and `:runner` (the runner
main-opts) are detected when `:runner` launches Kaocha, Cognitect or
Lazytest, and the child process composes every test alias so the classpath
keeps the `:test` configuration. When the command comes from `:runner`, the
development aliases `:dev` and `:optional` are composed as well: that layout
keeps development paths and optional runtime dependencies exercised by the
tests outside the library and test aliases (for example nREPL in Rephrase).
**Run namespace** and **Run test** use the runner's native selectors; a
`test` task in `bb.edn` stays suite-only. See
[ADR 023](decisions/023-runner-alias-convention.md) and
[ADR 024](decisions/024-runner-environment-aliases.md).

## Comment blocks and contextual evaluation — 2026-11-22

Top-level `(comment …)` forms of a namespace are discovered as read-only
locator entries ([ADR 022](decisions/022-comment-blocks-evaluation.md)): a
counted `(comment)` category lists them by file and line, the code panel
shows the whole block verbatim without a REPL connected, and editing and
saving are refused. The nREPL panel evaluates a whole block by sending its
body forms sequentially in the source namespace, stopping at the first
error, and Desktop evaluates captured rows and editor selections
(literally, even when they include the `(comment …)` wrapper). Evaluation
never writes sources or drafts. Blocks stay out of definitions, search, the
uses index and symbol lookup. The example projects ship `(comment …)` blocks
and can all start a live nREPL to test the flow; see
[ADR 022](decisions/022-comment-blocks-evaluation.md) and the README REPL
walkthrough.

## Navigation and search — 2026-11-22

Desktop has substring filters in the Namespaces and Definitions columns.
The Namespaces heading has a **Tree** checkbox for browsing dot-separated
namespace names in expandable branches. The flat list opens by default.
See [ADR 020](decisions/020-desktop-namespace-tree.md).
Project-wide search opens with `f` in the TUI and Ctrl+F / Cmd+F in Desktop.
Both use `csb.service/search` to match names, group
namespaces before definition categories, and open results through the usual
draft guards. The TUI uses a dedicated search view with keyboard navigation.
See [ADR 019](decisions/019-desktop-search.md) and [verification](verification.md).

## Live World — 2026-11-21

The first slice of milestone 5 adds the local nREPL panel to Desktop and TUI.
Both offer explicit connection to a running JVM, a multiline console, evaluation
of the selected definition, a transcript, interruption and disconnection. The
clients share the runtime adapter. See [ADR 013](decisions/013-local-repl-panel.md).

Milestone 5 now also covers configurable hosts, runtime namespace and var
exploration, basic metadata, and conservative Live and Combined views in both
clients. See [ADR 014](decisions/014-runtime-inspection.md) and
[verification](verification.md). Milestone 6 adds the **Tests** view,
background suite and scoped execution, streamed output and indicators
per namespace and test in both clients. See [ADR 016](decisions/016-test-runner.md).
Both clients can select an installed JDK per project for the Test Runner.
See [ADR 017](decisions/017-project-java-vms.md).
The Source World manual acceptance matrix remains tracked separately.

## Recorded progress — 2026-11-21

Implemented the source order view (option 1 of
[definition-order](definition-order.md), [ADR 008](decisions/008-source-order-view.md)):
the service publishes `list-source-forms` with all significant top-level forms
per file, and Desktop (the **Source order** button) and the TUI (the `o` key)
present them with the current definition marked. The owner's decision to
consider the smoke test validated was recorded in
[acceptance](acceptance.md); the manual matrix remains undemonstrated.

Also implemented are category facets with counts and the namespace summary
([ADR 009](decisions/009-category-facets.md)): `list-categories` returns
`{:total :files :facets}`, the session adds an "(all)" sentinel and by default
shows all definitions of the namespace; Desktop and the TUI only format. The
central panel is no longer limited to two or three uninformative types.

Implemented Polylith workspace discovery
([ADR 010](decisions/010-polylith-discovery.md)), closing
[issue #1](https://github.com/DevGiuDev/clojure-system-browser/issues/1):
when `deps.edn` declares no top-level `:paths` and the project carries a
`workspace.edn` (or both `components` and `bases` folders), the adapter
derives the conventional brick roots from the filesystem alone, without
executing anything. `examples/poly-sample-project` is a minimal browsable
workspace with that layout, and save keeps its guarantees.

Versioning was added ([ADR 011](decisions/011-versioning-changelog.md)): the
version (0.3.1) lives in `csb.cli/version`, is shown by the CLI, Desktop and
TUI `--help` output and the Desktop title, and is kept in sync manually with
the `CHANGELOG.md`, which records 0.1.0 through 0.3.1.

Release packaging was added
([ADR 012](decisions/012-release-packaging.md)): `build.clj` produces the
TUI/CLI uberjar (`clojure -T:build uber`, no JavaFX inside, main
`csb.tui` with `:gen-class`), and a build-only workflow triggered by `v*`
tags publishes the jar and the `csb` launcher script to GitHub Releases.
The Desktop is not packaged; jpackage and native binaries stay out of
scope. The workflow ran successfully on the `v0.2.0` tag (its first remote
execution, run 44950510002) and the published assets were exercised
locally; test CI remains removed.

Verification before the REPL panel: **69 tests / 662 assertions**, **40 process
checks** and clean lint.

## Recorded progress — 2026-11-15

The available code covers Source World of milestones 0–4, with validation
still pending to close the MVP. Desktop and TUI share navigation, drafts and
saving; both code panels have lexical highlighting. The TUI additionally has
dark/light themes and change-driven refresh, external editing with real Vim
in a PTY and terminal restoration. The external editor accepts arguments in
`VISUAL`/`EDITOR` without a shell and shows failures with reason, exit code
and a recoverable temporary; `Esc` returns to the code. The TUI theme
appearance was confirmed by the user in Emacs/EXWM; the complete workflow and
highlighting in that session are still not validated.

Verification valid at that time: **57 tests / 556 assertions**, **40 process
checks** and clean lint. See [verification](verification.md) and
[acceptance](acceptance.md) to distinguish automation from manual interaction.

| Milestone | Available code | Pending validation |
|---|---|---|
| 0: preparation | deps, example, tests and decisions; dependencies available; automated test in pseudoterminal with real Vim | Manual TUI demonstration per platform |
| 1: core and source | Index, categories, extraction and Save; `.cljd` reading; Polylith workspace discovery ([ADR 010](decisions/010-polylith-discovery.md)); lexical classifier without UI; 40 tests pass with rewrite-clj 1.3.59 | Manual platform matrix |
| 2: Desktop | Four panels, syntax highlighting, filter, draft, Save/Discard/Reload; controls, colors, selection, undo, shortcut, conflicts and errors tested headless with JDK 27 | Full visual demonstration and manual interaction |
| 3: TUI | Dark/light themes with `t`, lexical highlighting shared with Desktop, contrast and change-driven refresh; virtual terminal and 40 checks with pseudoterminal/real Vim; Save/Ctrl+S, conflicts, permissions and terminal restoration | Full manual interaction, highlighting inside Emacs/EXWM and per-platform behavior |
| 4: polish/extension | Registry v1, command, view and documentation | Validation of both clients and system matrix |
| 5: Live World | nREPL connection, evaluation, runtime inspection and Live/Combined views | Manual verification on supported platforms |
| 6: Test Runner | Project command discovery, background suite and scoped runs, streamed results and per-test indicators | Rerunning a set of failures and manual UI validation |

## Source World acceptance record

User confirmation (2026-11-21): Desktop saving works and the TUI workflow with
`emacsclient` in their Emacs/EXWM session works too — editing in a buffer of
the existing frame, returning with `C-x #` and saving from the TUI. This
closes the blocker recorded on 2026-11-15 (the editor opened another
Emacs/frame).

The core and automated interaction suites of Desktop/TUI pass with the pinned
versions. Owner decision (2026-11-21): the smoke test is considered validated
and work continues with the next feature; the full acceptance matrix remains
recorded as not manually demonstrated. The feature chosen at that point was
[definition order](definition-order.md). A Git remote (`origin`) exists since
2026-11-21. The GitHub Actions workflow ran once on the initial push:
headless Desktop passed; the macOS core job exposed two macOS-only
path-resolution assertions (fixed in the same commit), and ubuntu/Windows
were cancelled by fail-fast. Owner decision: the workflow was removed — the
prototype relies on local checks, and no remote CI execution is claimed.

## Pending decision: save-time validation

The owner raises that the browser should not compile or accept/reject code
syntax, at least in this phase. Save currently neither compiles nor evaluates,
but it does require a readable form and validates `defn` arguments. Allowing
syntactically incomplete drafts to be saved requires revisiting that contract
and agreeing how to preserve selection and the index after writing a form that
can no longer be parsed. Deciding that policy remains pending; this update
does not change the implementation nor its acceptance criteria. The revision,
conflict and outer-byte preservation guarantees must be kept under any of the
policies.

## Pending improvement: integrated editing in the TUI

**Noted, not implemented.** Allow editing the definition directly in the code
panel, without requiring an external editor. This is an extension of Source
World, independent of nREPL; the current MVP still supports editing through
`$EDITOR` and this improvement does not change its closing criteria.

Proposed scope:

- Multi-line editor with cursor, selection, paste, undo/redo, highlighting
  and scroll, respecting the themes and terminal resize.
- Distinguish navigation and editing, with clear keys to save, discard and
  leave edit mode without discarding changes silently.
- Use `csb.session` for the draft and the same Save service as Desktop; keep
  validation, conflicts, draft retention on errors and the current writing
  limits, including read-only definitions.
- Keep the external editor as a configurable alternative.
- Verify keyboard, Unicode, paste, resize and error recovery both in
  automated tests and real interaction, including Emacs/EXWM.

First, a reliable Lanterna editing control must be chosen and validated, and
its modes and shortcuts agreed. It introduces no interface-owned parser,
index or writing, and adds no Live World features.

## Design work: definition order

**First step implemented (2026-11-21)**: the source order view shows the real
per-file order and the context of definitions, without modifying sources
([ADR 008](decisions/008-source-order-view.md)). Conservative forward-reference
diagnostics and explicit placement or `declare` options remain open for future
write operations. The initial REPL panel evaluates only the text submitted
explicitly by the user.
