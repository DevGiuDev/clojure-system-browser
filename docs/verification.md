# Implementation verification

Date: 2026-11-15. Updated: 2026-11-22. This record distinguishes checks that
were executed from prepared verifications.

## Summary of the latest recorded results

| Check | Tests | Assertions | Result |
|---|---:|---:|---|
| Core, including settings files and the session round-trip | 68 | 538 | No failures or errors; JDK 27 |
| Desktop with headless JavaFX controls | 22 | 235 | No failures or errors; JDK 27 |
| TUI with virtual terminal | 24 | 297 | No failures or errors; JDK 27 |
| nREPL and test process integration | 14 | 130 | No failures or errors; JDK 27 |
| Suite total | **128** | **1200** | No failures or errors |
| Production TUI with PTY/Vim | — | — | Not re-run here (pexpect/pyte unavailable); last recorded 40-check run predates this change |
| clj-kondo | — | — | 0 errors, 0 warnings |

## Settings files (ADR 025) — 2026-11-22

Executed on Linux with JDK 27 via `JAVA_CMD`, the full tree including the
concurrent `:runner` aliases change (ADR 024): `clojure -Srepro -M:test`
(68 tests / 538 assertions), `clojure -Srepro -M:live-test` (14 / 130),
`clojure -Srepro -M:desktop:desktop-test` (22 / 235) and
`clojure -Srepro -M:tui:tui-test` (24 / 297), all with zero failures and
errors; `clj-kondo --lint src apps test test-desktop test-tui test-support
deps.edn build.clj` with 0 errors and 0 warnings. Before the headless
suites, the new `csb.settings` namespace was exercised in a scratch script
against JDK 27 with 29 behavioral checks: layering and origins, deep
`:repl` merge, invalid EDN / unknown keys / tagged literals / unsupported
values as per-layer warnings, strict save rejections, merge-on-save, atomic
replacement without temporaries, and scope validation.

The headless suites drive the real UIs: the Desktop test opens the gear
button, edits theme and tree default through the overlay controls, saves
with the project and global scopes asserting both files, and checks that a
rejected `:editor-command` keeps the overlay open with the reason; the TUI
test presses `g`, cycles the theme with arrows, toggles scope with Tab,
toggles a boolean with Space, saves with Enter, reopens with the persisted
origins, and confirms `q` edits the focused field instead of quitting.
Session-level behavior (view defaults applied on open, warning surfaced in
the status, round-trip through `csb.session`) is covered in the core suite.
Not re-run here: real-window Desktop interaction and a PTY TUI session with
a real external editor, and the `:editor-command` precedence end-to-end in a
real `$EDITOR` handoff (covered by unit tests of `csb.editor/command`).

## Development aliases in `:runner` runs (ADR 024) — 2026-11-22

Executed on Linux with JDK 27 on the PATH (the Clojure CLI ignores
`JAVA_HOME` on this machine), on the full tree that also carries the
settings work (2f43ff4): `clojure -Srepro -M:test` (68 tests / 538
assertions), `clojure -Srepro -M:live-test` (14 / 130),
`clojure -Srepro -M:desktop:desktop-test` (22 / 235) and
`clojure -Srepro -M:tui:tui-test` (24 / 297), all with zero failures and
errors; `clj-kondo` with 0 errors and 0 warnings. During development the
settings work was still uncommitted and its `editor-argv` arity change broke
one TUI fixture; a clean worktree at HEAD with only the test-command change
already ran the TUI suite 23 / 281 with zero failures, and the committed
settings work then fixed its fixture, so this change was never involved.

Before editing, the reported failure was reproduced from the project nREPL
against the real Rephrase checkout: `detect` returned
`{:driver :main, :aliases [:runner :test], :runner :lazytest}`, and the
composed child command exited 1 with
`FileNotFoundException … nrepl/middleware/caught … on classpath`. After the
change, the same real checkout passed through the full `csb.tests/run!`
flow: the suite returned `:passed` (`clojure -M:dev:optional:runner:test`,
exit 0, 6 test cases) and the `org.corfield.rephrase.nrepl-test` namespace
run returned `:passed` (3 test cases).

Covered by automation: the composed descriptors and command labels for
`:main` and `:exec-fn` `:runner` layouts, and a real run whose test
classpath needs both a `:dev` source path and an `:optional` dependency
(nREPL 1.7.0, already in the local cache from this project's own aliases),
alongside the pre-existing cases without those aliases.

## Test Runner alias convention (ADR 023) — 2026-11-22

Executed on Linux with JDK 27 via `JAVA_CMD`, all four suites with zero
failures and errors: core `clojure -Srepro -M:test` (60 tests / 477
assertions), `clojure -Srepro -M:live-test` (14 / 125),
`clojure -Srepro -M:desktop:desktop-test` (21 / 221) and
`clojure -Srepro -M:tui:tui-test` (23 / 281); `clj-kondo` with 0 errors and
0 warnings.

The change was driven from a report against real projects with the
`:test` + `:runner` convention (Rephrase with Lazytest, logging4j2 with
Cognitect). Before editing, the bug was reproduced from a project nREPL:
`csb.test-command/detect` returned `{:driver :bb}` for that layout, which is
suite-only and produces the reported "Unavailable" message. After the
change, `detect` was checked against copies of the two real configurations
(Lazytest and Cognitect runners, both with a `bb.edn` test task) and against
an unrecognized `:runner` that keeps the `bb` fallback. The full
`csb.tests/run!` flow was executed against a Lazytest layout project before
the suite runs: suite, namespace and single test each returned `:passed`
with the composed command `clojure -M:runner:test`. The default directory of
`lazytest.main` without paths (`test`) was confirmed from the published
2.1.0 artifact in the local cache.

The new live tests cover the convention end to end with real processes
(suite, namespace and test runs with their command labels) and, without
processes, the detection descriptors for Lazytest and Cognitect, the
composed alias arguments, the native `-n`/`-v` selectors and the negative
cases. No production code outside the test-runner extension changed; no
visual review was performed because the Tests view markup is untouched.

## Comment blocks and contextual evaluation (ADR 022) — 2026-11-22

Executed on Linux with JDK 27 via `JAVA_CMD`, all four suites with zero
failures and errors: core `clojure -Srepro -M:test` (60 tests / 477
assertions), `clojure -Srepro -M:live-test` (12 / 108),
`clojure -Srepro -M:desktop:desktop-test` (21 / 220) and
`clojure -Srepro -M:tui:tui-test` (23 / 281); `clj-kondo` with 0 errors and
0 warnings. The behaviour tests were written first and confirmed failing
before the implementation.

Covered by automation: block discovery with verbatim sources, discarded
forms, `clojure.core/comment`, Unicode, CRLF and the `(comment)` facet; the
facet listing blocks read-only through the shared navigation guards; block
evaluation against a real nREPL with sequential forms, stop on the first
error, retained effects, alias use across forms, history order, untouched
file and draft; interruption and disconnection mid-block; stale rows,
`.cljd` blocks, blank selections, selections evaluated literally including a
whole `(comment …)` wrapper, unloaded namespaces and busy sessions; the
Desktop menus (keyboard trigger, drafted row, captured selection, disabled
states, opening the console and the initial view without a project); the TUI
navigation to `(comment)`, F6 dispatch for definitions and blocks, and the
transcript visibility after the run.

The changed namespaces were additionally verified interactively in a
project nREPL (`-M:desktop:nrepl`) before the final suite runs: compiling
each edited namespace with `:reload`, listing blocks from a temporary
project, and running a block end to end against an in-process nREPL server.

The example projects were verified the same way after adding their
`(comment …)` blocks: the service discovers them (one block in
`sample-project`, three namespaces in `poly-sample-project`, one in the
Lazytest example), and each example's `:repl` alias started a live nREPL
whose namespaces are loaded — the browser evaluated the whole block of every
example against that runtime, including the Poly development scratch block
whose second form uses the alias introduced by its first. The
`desktop_smoke` and `tui_smoke` suites that append their own block to a
disposable copy of `sample-project` select it by body content because the
example now ships its own blocks. With the example blocks in place, the
`uses-follow-the-visible-definition` Desktop test exposed a pre-existing
harness race: a diagnostic run showed the session state and the uses service
correct (`:ready` with both matches) while the rendered panel had not drawn
the list yet, and a read at that moment reported empty results. The test now
retries content-expected reads for up to two seconds — the same pattern the
list-selection helper already uses — and the full Desktop suite ran green
six consecutive times afterwards, including concurrent runs. No product
change was involved: the panel derives from render-time state and the
service always returned the correct matches.

Not executed: manual interaction with a real Desktop window and a real
terminal (menus with the mouse, the production window flow); and the
production TUI PTY check (`scripts/check-terminal.py`), which needs
pexpect and pyte and was not re-run in this environment — its last recorded
run predates this change. The automated Desktop suite runs headless JavaFX
controls and the TUI suite a virtual terminal; per
[acceptance](acceptance.md), that manual pass remains pending.

The [Desktop namespace tree](decisions/020-desktop-namespace-tree.md) was
verified on Linux with JDK 27. The headless test covers the flat default,
the empty project state, nested namespaces, filtering, selection and draft
retention while switching views. Automated clicks on the production window
under Xvfb verified the checkbox placement, branch collapse and expansion,
opening a namespace and returning to the flat list with that namespace selected.

Java VM discovery found seven installations on the Linux test machine,
including Java 21 and 27. A filesystem fixture verifies discovery from the
environment and installation folders, alias deduplication, version metadata,
and the exclusion of wrapper scripts and incomplete directories. The Desktop
dropdown and TUI selector fill an editable path in their automated UI suites.

On 2026-11-22 the TUI project search was verified on Linux with JDK 27. The
virtual terminal tests cover the empty project state, case-insensitive grouped
results, relative source paths, query editing, opening namespaces and
definitions, Esc, and the draft guard. An automated run of the production TUI
in a POSIX pseudoterminal started at 60 × 16. It verified that `f` opens search
and can be typed in the query, both kinds of navigation, relative paths,
scrolling to an off-screen result, selection after a resize and clean exit.

On 2026-11-22 the [namespace filter and project search](decisions/019-desktop-search.md)
were verified: the core suite covers `csb.service/search` (grouped contract,
case-insensitivity, qualified-name matching, blank queries, the Source World
guard) and the session filter reset; the headless Desktop suite types in the
`#namespace-filter` box, navigates from the narrowed list, opens the search
overlay with a synthesized Ctrl+F KeyEvent, asserts the focused query field,
the Namespaces-first grouping, Enter opening a namespace row and a definition,
Esc closing, and a pending draft blocking Enter while keeping the panel open.
All suites and clj-kondo were re-run on Linux with JDK 27 via `JAVA_CMD`
(core 54/404, Desktop 17/160, TUI 20/245 unchanged, nREPL 9/51 unchanged).
Real on-screen interaction with the overlay was not exercised in that pass;
the production launcher was additionally started under Xvfb on Linux (normal
JavaFX toolkit, JDK 27 via `JAVA_CMD`) and stayed up with a clean log,
confirming startup and rendering outside the headless backend.

On 2026-11-22 the README screenshots were captured against a real external
project (`cljgame`, 176 namespaces, one `data_readers.clj` diagnostic) on
Linux with JDK 27 via `JAVA_CMD`. The Desktop window was created through the
production `csb.desktop` view and controller with the normal JavaFX toolkit
on a real display (Wayland session through XWayland, Prism software
rendering); namespaces, category and definition were selected through the
real ListViews and the Ctrl+F overlay was opened with query results and a
selection, producing `docs/img/desktop.png` and `docs/img/desktop-search.png`
from scene snapshots. The production TUI (`clojure -Srepro -M:tui`) ran in a
real tmux terminal (256 colors, 128×36) on the same project: startup,
arrow/Tab navigation to a specific definition and the syntax-colored code
panel were exercised, and the pane capture became `docs/img/tui.png`. These
are real-UI interactions outside the headless and virtual test backends;
they demonstrate navigation and rendering, not the full manual walkthrough.
After the 0.3.1 version bump and the README changes, all suites and
clj-kondo were re-run on Linux with JDK 27 (core 54/404, Desktop 17/162,
TUI 20/245, nREPL 9/51, clj-kondo with 0 errors and 0 warnings).

On 2026-11-22, Java VMs was exercised in the headless Desktop and virtual TUI
with temporary user preferences. The checks cover invalid paths, saving and
clearing the setting, project isolation, and draft retention. With CSB running
on Java 27, the Desktop test verified Java 21 from inside the Clojure CLI child
process. A separate CSB invocation reused the saved setting to run `lein test`
on a disposable copy of `cljgame`. Leiningen reported **642 tests / 2231
assertions**, including one added test that asserted the child ran on Java 21,
with no failures or errors. This verification was automated on Linux.

The `(requires)` selector was tested with headless JavaFX and a virtual
terminal. Both clients display the namespace's declarations and return to
definitions through `(all)`. Core checks cover reader conditionals, multiple
source files, namespaces without requires and draft retention. Desktop used
`.cache/m2` with the pinned versions in `deps.edn`, including Clojure 1.12.6,
rewrite-clj 1.3.59 and JavaFX 27.0.1.

For [source symbol lookup](decisions/015-source-symbol-lookup.md), the Desktop
suite opens both context menus, navigates across namespaces and checks that
opening a result cannot discard a draft. An automated click-through with the
normal JavaFX window on Xvfb opened `total-test` from the `total` row and went
back to `total` from `orders/total` in the editor. The window, menus and results
were inspected in screenshots from that run.

On 2026-11-22, core tests for the automatic **Uses** panel covered qualified
names, aliases, explicit refers, occurrence locations and counts, ambiguous
targets, and index refresh after saving and reloading. Desktop Headless
covered initial selection, cross-namespace navigation, the draft guard,
Escape from contextual results, and project switches. Automated interaction
with the regular JavaFX backend on Xvfb checked the layout at 760 and 1100 px,
opened `shop.orders-test/total-test` with Enter, and checked the results for
eight definitions in the 176-namespace `cljgame` project. The core, Desktop
Headless, virtual TUI and Live suites ran on JDK 27 with the pinned
dependencies from the local Maven cache. clj-kondo reported zero errors
and warnings.

The TUI theme appearance is confirmed by the user in Emacs/EXWM. Full manual
acceptance and review of the highlighting in that session remain pending. The
2026-11-21 totals include the tests of the source order view
([ADR 008](decisions/008-source-order-view.md)) and of the category facets
([ADR 009](decisions/009-category-facets.md)); their manual on-screen
interaction remains undemonstrated. All four checks were re-run on 2026-11-21
after renaming the facets sentinel label to "(all)" (previously "(todas)"):
same totals — core 44/357, Desktop 8/90, TUI 13/201, lint clean.

The core suite grew on 2026-11-21 with the Polylith workspace discovery tests
([ADR 010](decisions/010-polylith-discovery.md)): core 48/371 (run with the
machine's Java 21; the core stays JavaFX-independent), Desktop 8/90 and TUI
13/201 re-run with JDK 27 via `JAVA_CMD` — the PATH Java is 21 and JavaFX 27
refuses it — for a suite total of **69 tests / 662 assertions** and lint
clean.

[Definition order](definition-order.md) has its option 1 implemented (source
order view, read-only) with automated verification in all three suites.
Forward-reference diagnostics, load validation and write operations with
placement remain unimplemented.

## Desktop visual theme and icons (2026-11-22)

The Desktop visual plan (`prompts/002-estilo-desktop.md`) is implemented
([ADR 018](decisions/018-desktop-visual-theme.md)). `theme.css` centralizes
the light green-tinted palette as named `-csb-*` variables loaded from the
scene, `code-editor.css` reuses them keeping the lexical classes, one 16 px
SVGPath icon family covers actions, views, list headers, definition kinds,
read-only locks and operation states, and the filter field carries a search
icon. Severity travels as data: `csb.session` attaches `:status-tone` when
it builds a status message from the result's error type, so conflicts render
amber, other failures red, confirmed saves green, and draft and error
markers appear simultaneously; a save whose reindexing failed stays amber,
distinct from a write failure.

All four suites were re-run on the final code with JDK 27 (`JAVA_CMD`):
core 53/391, Desktop 15/142, TUI 20/245, live-test 9/51, and clj-kondo
reported 0 errors, 0 warnings. The Desktop suite asserts the primary
emphasis of `Open project…` and `Save`, the success tone after a confirmed
save, and the simultaneous draft and error or conflict tones. The WCAG
contrast ratios of the fifteen foreground/background pairs of the palette
were computed from the CSS values: normal text reaches at least 5.77:1
(secondary text on the window surface) and essential indicators at least
3.82:1 (control edges), so the 4.5:1 and 3:1 thresholds pass; this is a
calculation from the palette values, not a rendered measurement. The owner
visually confirmed the themed window on Linux. The manual acceptance matrix
(760 px and scaled sessions, macOS, Windows) remains pending as recorded in
[acceptance](acceptance.md).

## Project Test Runner (2026-11-22)

- Desktop and TUI ran a configured suite in a child process. Both showed a
  passing test in green and a failing test in red after the orange running
  phase. The checks covered stdout, stderr, opening test source and a
  scoped rerun. Desktop also passed on Xvfb (13 tests, 124 assertions).
- The integration suite ran all four Polylith tests with the workspace's
  development and test classpaths. The example `:test` alias also passed
  through the shared client.
- Disposable projects verified execution and filtering with Kaocha
  1.91.1392 and Cognitect CLI and exec aliases, a custom exec function,
  and a Babashka task. They also checked live output before cancellation
  and tested that Kaocha's capture plugin preserves the event stream.
- Leiningen 2.13.1 integration covered test-profile injections, a scoped
  run, and namespace failure when a fixture throws after its tests pass.
  On a disposable copy of `cljgame` with JDK 21, all 641 indexed tests and
  42 namespaces turned green. Adding a failing test to the copy turned
  that test and its namespace red while the other 641 stayed green.
- JDK 27 built the TUI uberjar with the child observer bundled. Manual
  client interaction remains pending.

### Lazytest support (2026-11-22)

A disposable copy of the [Lazytest sample project](../examples/lazytest-sample-project/README.md)
used Lazytest 2.1.0 through the shared runner. The integration test
exercised whole-suite, namespace and single-var runs, nested assertions,
skipped suites, setup errors, source navigation, and the project's reporter
and directory options. Core tests verified static discovery, navigation and
read-only definitions.

Running the example directly reported five cases and one intentional
failure, with exit status 1. Excluding `demo-failure` reported four
passing cases and exit status 0.

On JDK 27, core passed 55 tests and 409 assertions, Live integration passed
10 tests and 68 assertions, Desktop Headless passed 18 tests and 179
assertions, and the virtual-terminal TUI passed 22 tests and 267 assertions.
clj-kondo reported zero errors and warnings. Manual Lazytest interaction
in the clients remains pending.

### Test log and failure diagnostics (2026-11-22)

Desktop Headless verified that the log receives more vertical space than
the test lists and renders failing test names in red. The virtual-terminal
TUI verified red failure rows in the log. Both clients opened a failing
definition and displayed the assertion message, expected value and line
marker. The integration suite exercised Lazytest failures, setup errors,
Clojure test exceptions and an external edit that invalidates the line
marker while keeping the failure details.

In the isolated commit snapshot on JDK 27, core passed 55 tests and 409
assertions, Live integration passed 10 tests and 76 assertions, Desktop
Headless passed 17 tests and 170 assertions, and the virtual-terminal TUI
passed 20 tests and 249 assertions.
clj-kondo reported zero errors and warnings.

## Example nREPL launchers (2026-11-22)

Started both example launchers concurrently from disposable project copies
with JDK 27. The shared CSB client detected each port from `.nrepl-port`,
evaluated an expression on both servers, and ran all five preloaded tests
across four namespaces with zero failures or errors. The core suite passed
again (49 tests, 376 assertions), and both example configurations passed lint.

## Initial nREPL Test Runner (2026-11-21)

- Desktop and TUI opened the Tests view offline, exercised gray, orange, green
  and red indicators with a running nREPL server, and opened the selected
  test's source. A failed individual run turned a previously green
  namespace red in both views.
- The nREPL suite verified fixture handling, missing namespaces and tests,
  separate assertion failures and exceptions, cancellation, and preservation
  of the console session and transcript.
- The Desktop suite also passed with the graphical JavaFX backend on Xvfb
  (13 tests, 122 assertions). The TUI uberjar built with the extension
  included. Manual acceptance is pending.

## Live and Combined inspection (2026-11-21)

- The integration suite started a separate nREPL JVM and connected using the
  configured hostname. It checked namespace and private var discovery, metadata,
  missing vars, all four Combined presence states across the suite, and
  preservation of the console namespace and transcript. A server that rejects
  inspection reports the denial and continues to accept console evaluation.
- Desktop exercised inspector controls with the headless backend and repeated
  the suite against JavaFX GTK under Xvfb. Both runs checked navigation to var
  metadata and preservation of an unsaved Source World draft.
- The virtual TUI used the production key handling to open Live, switch to
  Combined, navigate and return to the source draft. The Source World PTY/Vim
  check also passed all 40 checks again.
- The TUI uberjar rebuilt successfully with JDK 21. In a POSIX pseudoterminal
  it connected to a separate JVM, paged through namespaces, opened private
  var metadata, showed Combined presence and reported a dropped connection.
  It exited normally and the project file stayed byte-for-byte intact.
- All four suites ran on JDK 27, and clj-kondo reported zero warnings and errors.
- Manual verification in Emacs/EXWM and the cross-platform acceptance matrix
  remain pending.

## Local nREPL panel (2026-11-21)

- Desktop and virtual TUI tests checked prefill from the open project's
  `.nrepl-port`, invalid content, manual overrides and a project switch.
  Both clients also connected to a separate nREPL using the prefilled port.
- Ran `clojure -Srepro -M:live-test` with JDK 27 and nREPL 1.7.0 over
  loopback. Checked persistent namespace, streamed output, stderr, values,
  evaluation errors, print quota, definition evaluation, timeouts,
  interruption before and during execution, and reconnection after EOF.
- Exercised the Desktop controls with the graphical JavaFX GTK backend
  under Xvfb against a separate nREPL JVM. The evaluated draft stayed on
  screen and left the file byte-for-byte intact. Saving a different definition
  changed the file while the runtime retained the evaluated definition.
  Also checked multiline input, output, interruption and disconnect.
- Drove the packaged TUI in a POSIX pseudoterminal against the same external
  server. Checked entry with a real newline, F5 evaluation, F6 evaluation of
  the selected definition, F7 interruption, visible connection loss and
  clean exit. The source file was byte-for-byte intact.
- These were automated interactions on Linux. Manual acceptance in the
  user's Emacs/EXWM session and the platform matrix remain pending.

## Observed environment

| Component | Version used/observed |
|---|---|
| JDK used for the tests | OpenJDK 27.0.1.0.3 |
| Machine default Java | OpenJDK 21.0.13.0.3 |
| Clojure executed | 1.12.6 |
| Installed Clojure CLI | 1.12.2.1565 |
| rewrite-clj used in tests | 1.3.59 |
| tools.reader | 1.6.0 |
| clj-kondo | 2026.10.26 |

`deps.edn` pins rewrite-clj **1.3.59**, cljfx **1.10.363**, JavaFX **27.0.1**,
RichTextFX **0.11.7** and Lanterna **3.1.5**. Current tests use those versions,
with no overrides to older ones. The recommended Clojure CLI version is
**1.12.6.1673**; the machine's global installation was not modified.

## Executed checks

```sh
JAVA_CMD=/usr/lib/jvm/java-27-openjdk/bin/java clojure -Srepro -M:test
```

Result: **48 tests, 371 assertions, 0 failures and 0 errors** (2026-11-21, run
with the machine's Java 21; earlier runs of the same suite with JDK 27 keep
the core independent of JavaFX; the 2026-11-15 run recorded 40/322).

Main coverage:

- Navigation across namespaces, definitions and privacy; empty namespaces.
- Exclusion of nested and discarded forms.
- Navigation without executing project forms or reader eval.
- Saving with exact prefix/suffix preservation: LF, CRLF, CR, BOM and Unicode,
  including supplementary characters before the definition.
- Rejection of multiple forms, invalid reader syntax, invalid `defn`
  arguments, name/kind changes, reader conditionals and unsupported tags.
- Revision conflicts and external changes, preserving both texts.
- Duplicates within/across files, `.cljc` and ambiguous namespaces in
  read-only mode.
- Configured paths without executing aliases; rejection of roots outside the
  project.
- Polylith discovery (2026-11-21): a workspace with `components`, `bases`,
  `development`, `projects` and no top-level `:paths` resolves its brick
  `src`/`test`, `development` and rare project `src` roots from the
  filesystem alone, reports them in `project-info`, and Save inside a brick
  keeps its guarantees; `workspace.edn` alone also triggers the layout;
  `deps.edn` `:paths` and explicit `:roots` keep precedence; a lone
  `components` folder does not switch the layout
  ([ADR 010](decisions/010-polylith-discovery.md)).
- Parsing errors, invalid UTF-8, deleted file and write failures.
- Symbolic link exclusion and permission errors on this Linux.
- Partial result when saving is confirmed but reindexing fails.
- Drafts retained when Save fails and navigation protected.
- Editor over a temporary and saving through the same service.
- Category facets (2026-11-21): `list-categories` returns `{:total :files
  :facets}` with correct counts per type over a namespace split across two
  files (`defn`, `defn-`, `defmacro`, `def`, `deftest`), order by name,
  exclusion of types without presence and rejection of `{:world :live}`; the
  session opens without a preselected category, exposes the "(all)"
  entry and filters only when a facet is chosen
  ([ADR 009](decisions/009-category-facets.md)).
- Source order view (2026-11-21): `document`/`list-source-forms` return all
  significant top-level forms in real order (`ns`, `declare`, `(comment ...)`,
  `#_`, `#=`, unknown heads), with ordinals that match definition IDs,
  `:definition-id` only on indexed ones, preview without BOM and correct rows
  with CRLF; multi-file per namespace, exclusion of files with parse errors
  and rejection of `{:world :live}`.
- Extension registration/deregistration, version, collisions, commands, views
  and missing capabilities.
- Absence of JavaFX/Lanterna classes and nREPL resources from the test
  classpath.
- Desktop JDK check before resolving JavaFX classes.
- Lexical classification shared by Desktop/TUI, without UI: strings, comments,
  characters, keywords, numbers and readers, with incomplete text and Unicode
  offsets. Its three tests moved from the Desktop suite to the core.
- Editor selection, variable priority, empty values, arguments with
  quotes/escapes, literal paths with spaces and rejection of invalid
  configuration, extra EDN input and `emacsclient -n`/`--no-wait`, without
  shell expansion.

```sh
clj-kondo --lint src apps test test-desktop test-tui test-support deps.edn build.clj
```

Result: **0 errors, 0 warnings**. It includes both clients and their
interaction tests; a static result is not equivalent to executing those
interfaces.

## Polylith example through the CLI (2026-11-21)

```sh
clojure -Srepro -M:cli --project examples/poly-sample-project
clojure -Srepro -M:cli --project examples/sample-project
```

With no `--source-root` options, the Polylith example opens `:status :ok`
with its 10 namespaces — component, base and `development` sources and tests
plus the rare `projects/app/src` — indexed definitions, tests and values
correctly read-only, and empty diagnostics. The classic example keeps its
previous result (2 namespaces), showing the detection does not alter projects
with `:paths`. On-screen Desktop/TUI interaction with the Polylith example
was not demonstrated; headless runs and the CLI are the executed evidence.

## Version display (2026-11-21)

`csb.cli/version` ("0.2.0", [ADR 011](decisions/011-versioning-changelog.md))
was verified through `clojure -Srepro -M:cli --help`, which prints
`clojure-system-browser 0.2.0` before the usage lines. After the help and
title changes, the three suites were re-run with the same totals recorded in
the summary table (core 48/371 with Java 21; Desktop 8/90 and TUI 13/201 with
JDK 27 via `JAVA_CMD`) and lint stayed clean. The TUI `--help` string and the
Desktop window title are not asserted by their suites; neither help path was
exercised beyond the CLI.

## Release packaging (2026-11-21)

Locally executed ([ADR 012](decisions/012-release-packaging.md)):
`clojure -T:build version` prints exactly `0.2.0`, read from `src/csb/cli.clj`
without evaluating it, and `clojure -T:build uber` (tools.build **0.10.14**
from Maven Central) produced `target/clojure-system-browser-0.2.0.jar`. The
first build shipped a jar that failed with `ClassNotFoundException: csb.tui`
— the AOT of a namespace without `:gen-class` does not emit the main class;
fixed by adding `(:gen-class)` to `csb.tui` (the `clojure -M:tui` launch is
unchanged and the TUI suite still passes). The rebuilt jar printed the
version and TUI help through `java -jar` on Java 21, and its entries contain
lanterna classes with zero JavaFX/cljfx/richtext classes. The `csb` launcher
was exercised next to the jar (help path) and without it (expected error,
exit 1). Before the remote run, the workflow YAML was validated syntactically
and its published references verified (checkout v7, setup-java v6,
setup-clojure 13.7.0, JDK 21, Clojure CLI 1.12.2.1565).

The workflow was then executed remotely by pushing the tag `v0.2.0`
(run 44950510002, 30 seconds): checkout, JDK and Clojure CLI setup, the
tag/version check, the uber build and the release publication all passed on
the first run. The published assets were downloaded and exercised locally:
`csb --help` printed `clojure-system-browser 0.2.0`, and a pty driver that
answers Lanterna's cursor-position report ran the published jar's TUI
against `examples/poly-sample-project`, which rendered its 10 namespaces
with 0 source errors — including the brick, `development` and `projects`
roots. This is a build-artifact check, not a resumption of test CI.

## Automated UI interaction

```sh
JAVA_CMD=/usr/lib/jvm/java-27-openjdk/bin/java clojure -Srepro -Sdeps '{:mvn/local-repo ".cache/m2"}' -M:desktop:desktop-test
JAVA_CMD=/usr/lib/jvm/java-27-openjdk/bin/java clojure -Srepro -M:tui:tui-test
```

- Desktop: **8 tests, 90 assertions, 0 failures and 0 errors**. Initial window,
  example loading, selection through the three list models, case-insensitive
  filter, CodeArea editing and real Save/Discard/Reload buttons. The save
  shortcut is tested through a KeyEvent, in addition to the close event with a
  pending draft and the extension contributions. The actions are those of the
  production controller, with its executor, session, service and renderer
  mounted. The lists are disabled with a pending draft to keep the visible
  selection aligned with the edited definition. Highlighting classes and the
  CSS color actually applied to the text are checked, plus incomplete forms,
  comments, escapes, regexes, characters, numbers, Unicode and long strings.
  Selection over a CRLF source survives a render without text changes;
  undo/redo preserves content and undoing back to the original restores its
  exact text without leaving a dirty draft. Changing definitions clears the
  history and read-only definitions are colored. The **Source order** button
  (2026-11-21) replaces the editor with the real form order (the `ns` form
  included; `total` before `discount`, unlike alphabetical order), marks the
  current definition with the marker updated when navigating by selection,
  returns with `Esc` and is disabled with a pending draft. Category facets
  (2026-11-21) show counts (`functions · 2`), the panel title carries the
  summary (`3 defs · 1 file`), the "(all)" row restores the full list
  and, with no facet chosen, the whole namespace is listed
  ([ADR 009](decisions/009-category-facets.md)).
- TUI: **13 tests, 201 assertions, 0 failures and 0 errors**. Lanterna's input
  queue is fed and the same iteration as the production loop is executed.
  Navigation, help, diagnostics without errors, scroll, extensions and resize
  from 120 × 35 to 40 × 10 and back. A Java/Clojure process acts as a test
  editor, receives only a temporary file and returns a draft without writing
  the project. Save/Ctrl+S, blocking of navigation/reload/exit with changes,
  failed editor return, recoverable temporary and recovery on EOF are
  checked, including leaving the terminal's private mode when the loop
  closes. Dark/light themes are also checked with explicit colors in every
  cell and minimum text contrast of 4.5:1 for the built-in palettes, including
  selection, pending draft and syntax error. Theme switching preserves the
  definition and the draft. Idle polling and unassigned keys produce no flush
  or terminal clearing; a focus change produces differences without erase.
  Resize is detected without requiring a key press. Physical terminal
  restoration after the editor is verified, even when returning an identical
  draft and state. The code panel now uses the shared `csb.syntax`
  classifier. Effective colors of forms, keywords, strings/regexes, comments,
  numbers, literals, characters, readers and delimiters are verified in both
  themes. An incomplete multiline string keeps its style with its opening off
  screen; the draft preserves exact Unicode and CRLF. Read-only, help and
  diagnostics as plain text and absence of idle output after coloring are
  also checked. Nonexistent executable and unclosed quotes are tested, with
  the previous draft intact. The panel shows the reason, command and exit
  code when available, keeps the temporary and supports returning with `Esc`
  and retrying. A configuration failure is reported before creating
  temporaries. Pagination in an 80 × 20 terminal walks the details without
  skipping lines, with explicit colors and checked contrast. The `o` key
  (2026-11-21) opens the source order panel in plain text — with the `ns`
  form and the real file order, unlike alphabetical —, marks the current
  definition, moves the marker when navigating without closing, returns with
  `Esc` without losing the draft and is mutually exclusive with help and
  diagnostics; help and the shortcut bar include the new key and the panel
  meets the explicit-color contrast. Category facets (2026-11-21) are
  navigated by keyboard from "(all)" — the default selection — to
  `functions · 2` and `private-functions · 1`, with the namespace summary in
  the column header and real filtering of the definition list.
- Both suites use temporary copies of the example with BOM, Unicode and CRLF
  and compare the whole file bytes after saving. Invalid syntax and external
  changes are rejected, preserving the draft and the disk. To check UI I/O
  errors, an IOException is injected at the atomic replacement; Save and the
  service remain real. The core additionally covers a real permission failure
  on this Linux.
- Opening regression: `.cljd` discovery with namespace metadata and `#/`
  syntax, without loading dependencies or executing forms. A file's parse
  errors do not hide the rest. Desktop shows the definitions as read-only and
  the TUI refuses to edit or save them. The **Open project…** button is
  exercised by replacing only the native chooser with a test path: it clears
  the filter and uses the chosen `deps.edn`, without inheriting startup
  `--source-root` options. Canceling preserves the project and a draft blocks
  the chooser. The session reports the examined roots when opening or
  reloading yields no namespaces.
- Additional check on a real local ClojureDart project: **49 namespaces, 939
  definitions, 0 diagnostics**, all definitions read-only. Both the service
  and the Open button with headless JavaFX controls and a replaced chooser
  were verified. No sources of that project were modified and its runtime was
  not started. The native dialog and visual review remain pending.
- With Java 21, `clojure -Srepro -M:desktop ...` exits with code 1 and a
  message indicating the minimum JDK, the one used and how to select it,
  before loading JavaFX. No `UnsupportedClassVersionError` appears.

This machine's default Java is 21. In its Clojure launcher, `JAVA_HOME` alone
does not change the selected Java when `java` is already on the PATH;
`JAVA_CMD` allows explicitly selecting the installed JDK 27.

The new Desktop dependency used a local `.cache/m2` cache, without changing
versions or global configuration. The usual cache is not writable from this
environment and the shell cannot resolve Maven Central. The RichTextFX 0.11.7
POM/JARs and their dependencies were fetched from `repo.maven.apache.org`
through the available Node runtime and their published SHA-1 sums were
checked. ReactFX 2.0-M5, UndoFX 2.1.1, Flowless 0.7.4 and WellBehavedFX 0.3.3
were used, per the official POMs; the remaining artifacts correspond to the
existing cache. The core and TUI suites ran with the normal commands listed
above.

The environment cannot open a DISPLAY. The Headless backend bundled with
JavaFX 27 is used, enabled only in the test alias. A capture of the scene
with a colored definition was also generated and inspected: layout,
legibility, strings, comments, keywords and literals. This checks classes,
controls, rendering and life cycle, but does not replace manual on-screen
interaction.

Normal graphical startup was attempted with JDK 27. The default native cache
is not writable from this environment; selecting
`-J-Djavafx.cachedir=.cache/javafx` for that command only, startup reaches
GTK and fails with `Unable to open DISPLAY`. Global configuration was not
changed and Headless was not enabled for normal startup. Direct TUI launch
with a pseudoterminal could not open `/dev/tty` on the first attempt.
Afterwards the automated workflow with a controlling pseudoterminal described
below was completed; none of those runs constitutes a manual demonstration.

## Production TUI with pseudoterminal and real Vim

```sh
python3 scripts/check-terminal.py --java /usr/lib/jvm/java-27-openjdk/bin/java --maven-repo .cache/m2 --editor-setting editor --evidence-dir .cache/terminal-check/editor-env
```

Re-run on 2026-11-21 after the source order view (with the `o` key and its
shortcut bar already present): **40 checks, no failures**, with the same Vim
version and the default configuration path (`CSB_EDITOR_ARGS`, without
`--editor-setting`); the evidence from that pass is in
`.cache/terminal-check/order-run` (the script does not exercise the `o`
panel; that coverage belongs to the virtual terminal suite). After the
category facets the script was adapted to the unfiltered default view: the
first alphabetical definition is `discount` and `total` sits two rows down,
so its fixed navigation uses two arrows instead of one. That day's first run
failed on byte preservation due to a fragile assumption of the script itself:
it delimited the end of the form with a separator of exactly two CRLFs, and
the repository example had three after the owner's manual save in Desktop
(draft with an extra trailing line; the save contract is correct: what
replaces the draft is the bytes of the form's range). The script now trims
trailing newlines from the boundary and does not depend on the blank-line
count. The originally recorded run (2026-11-15, `--editor-setting editor`)
was also:

Result: **40 checks, no failures**, on Linux with JDK 27, Lanterna 3.1.5,
Vim 9.2 (patches 1–1178), pexpect 4.9.0, pyte 0.8.2 and wcwidth 0.9.4. In
that run pyte and wcwidth were loaded via `PYTHONPATH` from their wheels in
`.cache/terminal-check`, without modifying global Python. The indicated local
Maven cache was used, with the versions pinned in `deps.edn`.

The script opens the production TUI in a POSIX PTY with `TERM=xterm-256color`
and renders its output with pyte. It opens real Vim with user configuration
disabled and with an executable whose path contains spaces. It only modifies
a temporary copy of the example; it does not substitute Lanterna, the editor
or the service. This run uses `EDITOR` with arguments and a quoted path, with
no `VISUAL` and no `CSB_EDITOR_ARGS` in the test process. It demonstrates the
new environment-command handling; it does not represent the user shell's
effective configuration.

Navigation, help, diagnostics, extensions, scroll and resize from 120 × 35 to
40 × 10 and back were verified. Vim receives a temporary with a single
definition and the project disk does not change until Save. All bytes are
compared after `s` and Ctrl+S with BOM, CRLF and Unicode outside the form.
Invalid syntax, external changes and a real directory-permission failure are
rejected, preserving disk and draft. Blocking of navigation/reload/exit, a
recoverable temporary after a failed Vim and return to the TUI loop are also
checked. Reason and exit code visibility, and returning from the error panel
with `Esc` without deleting the recoverable temporary, are also verified.

Explicit dark/light background is verified in the interpreted ANSI output,
plus theme switching without losing navigation. The output of both themes
shows the expected colors for `defn`, a string and a normal symbol of the
displayed code. After the initial frame completes, a 0.5 s idle interval with
no process output is observed. The TUI previously cleared/refreshed the whole
screen every 30 ms; now it paints only when state or size changes and sends
differences, with full repaints on startup, resize and editor return. Images
were generated and inspected from the real PTY cells to review both themes;
they are not Emacs/EXWM captures.

The test found that XON/XOFF intercepted Ctrl+S before it reached Lanterna.
The TUI now captures POSIX settings before building the terminal, disables
`ixon` and restores the exact state after closing it. The script starts with
`ixon` enabled, checks it remains disabled after Vim, and compares `stty -g`
before/after normal exit and a startup exception. It does not modify global
terminal or Emacs configuration. Windows skips the POSIX adjustment and still
requires its own validation.

The ANSI traces, save/error screens and `results.json` are in
`.cache/terminal-check/editor-env` for the earlier run (reproducible with the
script). The default directory remains `.cache/terminal-check/run`. The
suites were also repeated: core **40/322**, Desktop **6/66**, TUI **11/168**,
with no failures or errors, and clj-kondo without warnings. This evidence
does not verify human interaction, visual scale or key delivery inside an
Emacs/EXWM session.

The user has confirmed the theme appearance looks right in their TUI. That
confirmation precedes this highlighting; it is not extrapolated to its
validation or to the full manual acceptance workflow.

## TUI startup with `--project` and a sibling project

Facing a `FileNotFoundException: --project` report, the trace was reviewed:
Clojure tried to load that argument as a script, before entering
`csb.tui/-main`. In this copy the `:tui` alias and its `-m csb.tui` options
are correct. Help starts from the repository root with
`-Srepro -M:tui --project ... --help`. The failure was not reproduced from
that directory; no concrete cause in the user's shell (execution directory or
launch cache) is considered demonstrated.

The following was executed, from the CSB root and inside a POSIX
pseudoterminal:

```sh
JAVA_CMD=/usr/lib/jvm/java-27-openjdk/bin/java clojure -Srepro -Sforce -M:tui --project ../buyappclj
```

Result: production TUI opened on the indicated project, **11 namespaces and 0
source errors**. Exited with `q`, code 0 and terminal settings restored. No
editors were launched and no project sources were modified. The README was
updated to distinguish the directory from which Clojure CLI resolves the
aliases from the project CSB opens via `--project`. This check does not
replace manual acceptance in Emacs/EXWM nor add tests to the totals of the
previous suites.

## Manual confirmation of the external editor with emacsclient

Date: 2026-11-21. The user validated the TUI workflow in their Emacs/EXWM
session following the README example (`emacsclient` without `-c`, with
`--alternate-editor=false`): editing in a buffer of the existing frame,
returning with `C-x #`, and saving from the TUI updated the project file.
This closes the blocker recorded on 2026-11-15. This confirmation covers
external editing and saving; the rest of the manual workflow (bytes, invalid
draft, conflict, write failure, terminal restoration and highlighting in that
session) remains pending per [acceptance](acceptance.md).

## Pending before closing the MVP

1. Manually demonstrate the [acceptance workflow](acceptance.md) in Desktop
   and TUI, including draft retention, editor return, help, diagnostics and
   exit.
2. Check keyboard, scaling and resize on Linux, macOS and Windows; also
   review JavaFX classifiers/natives on the architectures that get
   distributed.

## Remote CI (removed)

A GitHub Actions workflow existed briefly. Its single run (initial push,
2026-11-21): the headless Desktop job passed on ubuntu; the macOS core job
failed with 2 failures in `csb.source-test` — assertions compared
`(.resolve root ...)` against `:file` values the adapter real-resolves
(`/var` vs `/private/var` behind the runner's temp-dir symlink) — and the
ubuntu/Windows matrix jobs were cancelled by fail-fast. Those two assertions
were fixed to compare real paths in the same commit that removed the
workflow. Owner decision (2026-11-21): the project is a prototype and
verification relies on the local checks above; no remote CI execution is
claimed.

The tests do not demonstrate a compare-and-swap operation against external
editors, crash durability, or complete compatibility with every reader form.
