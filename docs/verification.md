# Implementation verification

Date: 2026-11-15. Updated: 2026-11-21. This record distinguishes checks that
were executed from prepared verifications. No REPL has been started or
connected.

## Summary of the latest recorded results

| Check | Tests | Assertions | Result |
|---|---:|---:|---|
| Core, external editor, lexical classifier, source order and facets | 44 | 357 | No failures or errors; JDK 27 and 21 |
| Desktop with headless JavaFX controls | 8 | 90 | No failures or errors; JDK 27 |
| TUI with virtual terminal | 13 | 201 | No failures or errors; JDK 27 |
| Suite total | **65** | **648** | No failures or errors |
| Production TUI with PTY/Vim | — | — | **40 checks**, no failures |
| clj-kondo | — | — | 0 errors, 0 warnings |

The TUI theme appearance is confirmed by the user in Emacs/EXWM. Full manual
acceptance, review of the highlighting in that session and remote CI remain
pending. The 2026-11-21 totals include the tests of the source order view
([ADR 008](decisions/008-source-order-view.md)) and of the category facets
([ADR 009](decisions/009-category-facets.md)); their manual on-screen
interaction remains undemonstrated. All four checks were re-run on 2026-11-21
after renaming the facets sentinel label to "(all)" (previously "(todas)"):
same totals — core 44/357, Desktop 8/90, TUI 13/201, lint clean.

[Definition order](definition-order.md) has its option 1 implemented (source
order view, read-only) with automated verification in all three suites.
Forward-reference diagnostics, load validation and write operations with
placement remain unimplemented.

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

Result: **44 tests, 357 assertions, 0 failures and 0 errors** (2026-11-21; the
previous run of 2026-11-15 recorded 40/322). The earlier suite also passes
with Java 21, keeping the core independent of JavaFX.

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
clj-kondo --lint src apps test test-desktop test-tui test-support deps.edn
```

Result: **0 errors, 0 warnings**. It includes both clients and their
interaction tests; a static result is not equivalent to executing those
interfaces.

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
3. Run remote CI. All four checks have run locally on Linux with JDK 27. The
   workflow now supports `workflow_dispatch` and its lint includes
   `test-support` and `deps.edn`. At the time these lines were recorded,
   this copy contained no useful Git metadata nor an identified remote, so a
   remote workflow could not be dispatched. A Git repository and the `origin`
   remote were initialized on 2026-11-21, but no commit or push has happened
   and no remote run has occurred; its existence is not presented as a
   passing execution.

The tests do not demonstrate a compare-and-swap operation against external
editors, crash durability, or complete compatibility with every reader form.
