# Clojure System Browser

A Smalltalk-inspired browser for Clojure definitions. Regular project source files
remain the persistent source of truth. This implementation works exclusively with
**Source World**, without starting or connecting to a REPL.

## ⚠️ Disclaimer

This project is a **rapid prototype** built exclusively through **vibe coding** (100% AI-assisted development).

The main goal was to validate an idea and get a working MVP as fast as possible. As a result, the codebase may contain unoptimized logic, edge-case bugs, or lack strict architectural patterns and test coverage. **Use at your own risk**—it is not intended for production environments.

## Current status

- Source World: discovery of `.clj`, `.cljc` and `.cljd` files, indexing,
  counted category facets with a namespace summary, a shared service, editing
  sessions, a CLI and a minimal extension
  registry. Saving is limited to unambiguous `defn`/`defn-` forms in `.clj` files,
  preserving surrounding bytes, detecting conflicts and retaining drafts on error.
- Desktop (`cljfx`/JavaFX): project opening, navigation, filtering, a code editor
  with syntax highlighting, selection, undo/redo and Save/Discard/Reload. Tested
  with real controls using the JavaFX 27 headless backend.
- TUI (Lanterna): navigation, resizing, dark/light themes, syntax highlighting
  shared with Desktop, updates only when something changes, external editing,
  Save/Ctrl+S and draft recovery. Tested with a virtual terminal and real Vim in
  a PTY, including quoted editor commands and visible failure details. The user
  confirms that the theme is readable in Emacs/EXWM, and on 2026-11-21 confirmed
  the emacsclient editing round-trip there (edit in an existing frame, return
  with `C-x #`, save from the TUI); this does not yet cover syntax
  highlighting or the complete manual workflow in that session.
- Source order view: a read-only panel in both clients (the **Source order**
  button in Desktop, `o` in the TUI) shows the real file order of top-level
  forms of the current namespace, marking the selected definition. Shared
  through the same service; see [ADR 008](docs/decisions/008-source-order-view.md).
- Latest recorded results (2026-11-21): **65 tests, 648 assertions and 40 process
  checks**, with no failures; lint reports no warnings. Core tests use JDK 27 and
  21; client tests use JDK 27. See [verification](docs/verification.md).
- The MVP **is not considered complete** until the full interaction workflow has
  been demonstrated in both clients on supported systems.
  Next design decision: [forward-reference diagnostics](docs/definition-order.md)
  (option 2; the read-only source order view is already implemented).
  See the [roadmap](docs/roadmap.md).

## Requirements and versions

Recommended: JDK 27 and Clojure CLI 1.12.6.1673. Desktop requires JDK 25 or later.
The core uses Clojure 1.12.6. Dependency versions are pinned in `deps.edn`:

**What matters is the Java executable used by Clojure CLI.** `-Srepro` does not
select a JDK, and setting `JAVA_HOME` alone may not change it if the launcher
prioritizes `java` on PATH. If Java 21 is the system default, select JDK 27 for
one invocation without changing global configuration:

```sh
JAVA_CMD=/usr/lib/jvm/java-27-openjdk/bin/java clojure -Srepro -M:desktop --project examples/sample-project
```

That path matches this Linux installation; use your own JDK path on another
machine. Alternatively, prepend its `bin` directory to PATH and set `JAVA_HOME`
to the same JDK. Check the Java version actually used by Clojure with:

```sh
clojure -Srepro -M -e '(println (System/getProperty "java.version"))'
```

Desktop checks the version before loading JavaFX and displays a clear message
if the JDK is too old. An `UnsupportedClassVersionError` mentioning class version
69 and a maximum of 65 means Java 25 bytecode is being loaded by Java 21.

| Dependency | Version | Scope |
|---|---|---|
| Clojure | 1.12.6 | Core and clients |
| rewrite-clj | 1.3.59 | Source adapter |
| cljfx | 1.10.363 | Desktop only |
| JavaFX | 27.0.1 | Desktop only |
| RichTextFX | 0.11.7 | Code editor, Desktop only |
| Lanterna | 3.1.5 | TUI only |

Initial dependency resolution requires Maven Central and Clojars access.
`-Srepro` avoids inheriting dependencies from personal Clojure CLI configuration.

```sh
java -version
clojure -Sdescribe
clojure -Srepro -M:test
clojure -Srepro -M:cli --project examples/sample-project
clojure -Srepro -M:cli --project examples/poly-sample-project
clojure -Srepro -M:desktop --project examples/sample-project --example-extension
clojure -Srepro -M:tui --project examples/sample-project --example-extension
```

Run these commands **from the Clojure System Browser directory**, which contains
its `deps.edn`. `:tui` and `:desktop` are aliases defined by this repository.
`--project` selects the source project to browse; it does not select the
`deps.edn` used by Clojure CLI to launch the application. For a sibling project:

```sh
cd /path/to/clojure-system-browser
JAVA_CMD=/path/to/jdk-27/bin/java clojure -Srepro -M:tui --project ../another-project
```

If Clojure tries to open `--project` as a file and reports
`FileNotFoundException`, execution has not reached the browser's `-main`.
Check the working directory and that its `deps.edn` defines `:tui`. To check the
alias and regenerate its launch cache once, without opening the terminal UI:

```sh
clojure -Srepro -Sforce -M:tui --help
```

This should print the application version and the CSB help starting with
`clojure -M:tui --project PATH`. The version is the one declared in
`csb.cli/version` and recorded in [CHANGELOG.md](CHANGELOG.md).
If the launch cache also needs rebuilding when opening a project, place
`-Sforce` before `-M:tui`. Do not change the `deps.edn` of the project you want
to browse.

Source roots come from `:paths` in the project's `deps.edn`, without resolving
its dependencies or executing aliases. If no paths are provided, `src` and
`test` are used. To include paths declared only in an alias, repeat `--source-root`:

```sh
clojure -Srepro -M:tui --project /path/to/project --source-root src --source-root test
```

In a Polylith workspace there is no top-level `src`. When the project contains
a `workspace.edn` (or the older `.polylith` configuration), or both `components`
and `bases` folders, and `deps.edn` declares no top-level `:paths`, the browser
derives the conventional brick layout from the filesystem alone: every brick
under `components` and `bases` contributes its `src` and `test`, `development`
contributes `src` and `test`, and each project under `projects` contributes its
rare `src`. `workspace.edn` and aliases are never executed; explicit
`--source-root` options and `deps.edn` `:paths` still take precedence.
`examples/poly-sample-project` is a minimal browsable workspace with that layout.

Only roots within the project are accepted. Symbolic links, `target`, `.git`,
caches and `node_modules` are ignored. `project.clj` is not evaluated.

Discovery includes `.clj`, `.cljc` and ClojureDart `.cljd` files. `.cljd` files
can be browsed **read-only**, without resolving their dependencies or starting
Dart or Flutter. **Open project…** detects the selected project's source roots
and clears the previous filter; startup `--source-root` options apply only to
the initial project. If no namespaces are found, the status bar shows the roots
searched and the supported file formats.

## Source World walkthrough

1. Open `examples/sample-project` in Desktop. All definitions of `shop.orders`
   are listed by default; the Categories panel shows counted facets
   (`functions · 2`) and `(all)`. Select the `functions` facet and then the
   `total` definition. The editor shows only that form.
2. Change the body and press **Save** or Ctrl+S / Cmd+S. Bytes before and after
   the definition are preserved, including comments and CRLF line endings.
3. Leave a parenthesis unclosed: Save must report the error and retain the draft.
   Fix the text or press **Discard**.
4. With a pending draft, modify the file from another application. Save must show
   a conflict. To accept the version on disk: **Discard**, then **Reload**.
5. Open the TUI on the same project: it displays the same service data. Tab
   changes lists and the arrow keys select items. `?` shows help and `!` shows
   project source errors.
6. With `--example-extension`, Desktop offers a greeting command and a summary
   view. In the TUI, `x` invokes the first command and `v` the first view.
7. Press **Source order** in Desktop or `o` in the TUI: both show the real file
   order of top-level forms — `(ns shop.orders)` first, then `total`,
   `discount`, `receipt` — with the selected definition marked. `Esc` or the
   button returns to the editor; selecting an entry in Desktop navigates to it.

Navigation, reload, opening another project and normal exit are blocked while
there is a draft to save or discard. Desktop disables the lists while a draft
is pending to keep the visible selection aligned with the edited definition.
The full manual walkthrough and platform records are in
[acceptance](docs/acceptance.md).

The Desktop code panel highlights special forms, keywords, strings, regular
expressions, comments, numbers, literals, characters and delimiters. Highlighting
updates while editing, including incomplete forms and read-only definitions.
It is lexical: it does not resolve symbols or evaluate code, and it does not
certify that a draft can be saved. The editor preserves selection, copy/paste,
undo/redo and scrolling in both directions. The TUI uses the same lexical
classifier for its code panel, including incomplete drafts and read-only
sources. Colors adapt to both themes, and multiline strings keep their style
when scrolling. External editing uses the highlighting provided by `$EDITOR`.

### Terminal editing

The TUI has its own dark theme with explicit backgrounds, separate panels,
a highlighted selection and colors for errors and pending changes. Press `t`
to switch between dark and light; the choice lasts for the session, including
reloads and editor handoffs. A terminal with 256-color support is required.
The application uses the extended palette instead of inheriting the terminal's
background or basic 16 ANSI theme colors. It does not change the Emacs/EXWM or
external editor theme. `?` lists the available keys.

The screen updates when state or size changes, sending only differences to the
terminal. It does not redraw or emit output while idle. Startup, resizing and
returning from the external editor rebuild the complete screen.

`e` opens an **external editor on a temporary file containing one definition**.
It never opens the original project file. The code panel displays source and
drafts; text editing happens in the external editor. On return, CSB imports a
draft. `s` / Ctrl+S saves through the same service used by Desktop, and `d`
discards it.

Editor selection follows this order: `CSB_EDITOR_ARGS`, `$VISUAL`, `$EDITOR`,
then `vi`. Empty `VISUAL`/`EDITOR` values are ignored. These two variables accept
commands with quoted arguments, such as `EDITOR='vim -f'` or
`VISUAL='emacsclient'`. No shell is invoked, and variables, globs and command
substitutions are not expanded. If a value is the literal path of an existing
file, it remains a single executable path, including spaces. For unambiguous
arguments, use an EDN vector:

```sh
CSB_EDITOR_ARGS='["vim" "-f"]' clojure -Srepro -M:tui --project examples/sample-project
```

Graphical editors must wait until editing is finished. During the handoff, CSB
prints the selected command and return instructions. On failure, a panel shows
the error, exit code when available, command and retained temporary file.
`Esc` returns to source without discarding the draft. PgUp/PgDn scroll the
details, including in short panels.

To edit in a buffer in your existing Emacs frame (including an EXWM session),
run `M-x server-start` in that Emacs if its server is not already running, then
launch CSB from this repository:

```sh
JAVA_CMD=/usr/lib/jvm/java-27-openjdk/bin/java CSB_EDITOR_ARGS='["emacsclient" "--alternate-editor=false"]' clojure -Srepro -M:tui --project examples/sample-project
```

The Java path above is the Linux installation described in Requirements; use
your JDK path on another machine. `CSB_EDITOR_ARGS` takes precedence over both
`VISUAL` and `EDITOR`. Omitting `-c` / `--create-frame` lets `emacsclient` use an
existing frame; `-c` explicitly requests a new frame, which is not necessarily
a separate Emacs process. On POSIX, `--alternate-editor=false` prevents fallback
to another editor or a new daemon if the server cannot be contacted. If you use
a named server, add `"--socket-name=YOUR_SERVER"` to the argument vector.

Press `e` in CSB, edit the temporary buffer in Emacs, save it with `C-x C-s`,
and finish with `C-x #` (`server-edit`). Return to the terminal buffer running
CSB and press `s` to save the project source. Saving the temporary buffer alone
does not update the project file. The `-n` and `--no-wait` options of
`emacsclient` are rejected because they return before editing finishes.
CSB does not change global Emacs configuration or start its server automatically.
See the [emacsclient manual](https://www.gnu.org/software/emacs/manual/html_node/emacs/Invoking-emacsclient.html).

On POSIX, an interactive terminal and `stty` are required. The TUI disables
XON/XOFF flow control during its session so Ctrl+S can reach it, then restores
the previous settings on exit, including exceptions. Shell configuration is
unchanged. If Emacs, EXWM or the terminal intercepts the shortcut before it
reaches the application, use `s` to save. Running inside Emacs requires a terminal
that supports full-screen applications; a process output buffer does not
demonstrate that compatibility.

## Headless API

```clojure
(require '[csb.service :as browser])

(def opened (browser/open-project "examples/sample-project"
                                  {:example-extension? true}))
(def system (:value opened))
(browser/list-namespaces system)
(browser/list-definitions system 'shop.orders)
(browser/list-categories system 'shop.orders)

(def definition (some #(when (= 'discount (:name %)) %)
                      (:value (browser/list-definitions system 'shop.orders))))
(browser/get-definition-source system (:id definition))
(browser/save-definition! system (:id definition) "(defn- discount [amount] amount)"
                          {:expected-revision (:revision definition)})
```

The save example targets `discount`. Operations return
`{:status :ok :value ...}` or
`{:status :error :error {:type ... :message ... :details ...}}`.
A confirmed save returns `:written? true`, the revision and change data.
A subsequent reindexing failure is reported separately as `:reindex-error`.

## Releases

Every `v*` tag (for example `v0.2.0`) runs a build-only GitHub Actions
workflow ([ADR 012](docs/decisions/012-release-packaging.md)) that publishes
two assets to [GitHub Releases](https://github.com/DevGiuDev/clojure-system-browser/releases):
the TUI/CLI uberjar and the `csb` launcher script. The workflow runs no
tests and makes no verification claims; checks stay local as recorded in
`docs/verification.md`. To cut a release, verify locally and push the tag:

```sh
clojure -T:build uber
git tag v0.2.0 && git push origin v0.2.0
```

The jar runs the TUI (`csb.tui` is the main class) on Java 21 or later and
contains no JavaFX: the Desktop still needs a JDK 25+ with this
repository's JavaFX 27 dependencies and is not packaged. To install from a
release, put both assets in a directory on `PATH`:

```sh
mkdir -p ~/.local/opt/csb && cd ~/.local/opt/csb
curl -sLO https://github.com/DevGiuDev/clojure-system-browser/releases/download/v0.2.0/csb
curl -sLO https://github.com/DevGiuDev/clojure-system-browser/releases/download/v0.2.0/clojure-system-browser-0.2.0.jar
chmod +x csb
export PATH="$HOME/.local/opt/csb:$PATH"
csb --project /path/to/project
```

`csb` accepts the same options as `clojure -M:tui` (`--project`,
`--source-root`, `--example-extension`) and honors `JAVA_CMD` to select the
Java binary.

## Initial release limitations

- Writing supports only a single unambiguous top-level `defn` or `defn-` in a
  UTF-8 `.clj` file. Renaming or changing the definition kind during Save is
  not supported.
- Other known forms, `.cljc` and `.cljd` files are indexed when the parser can
  read them, but remain read-only. ClojureDart browsing recognizes known top-level
  forms; it does not implement the compiler or guarantee support for every Dart
  construct. Macros are not expanded.
- The reader and `defn` argument structure are checked using Clojure specs.
  Compilation, var resolution, the validity of all macros and program behavior
  are not checked.
- Custom tags, `#=`, reader conditionals in `.clj` files and unrecognized aliases
  prevent saving. `:as`/`:as-alias` are recognized in simple vector `:require`
  libspecs without loading their namespaces.
- Current IDs contain a file and an ordinal among top-level forms. They locate
  a definition within a revision; they are not persistent IDs for history,
  renaming or future integrations.
- Save uses hashes, serialization within each open project, a temporary in the
  same directory and atomic replacement. **It does not provide compare-and-swap
  against arbitrary external editors or durability across power loss.**
  If atomic replacement is unavailable, saving fails without destructive writing.
- POSIX permissions are preserved where available. Preserving owners, ACLs,
  extended attributes or hard-link relationships is not guaranteed.
- There is no automatic file watching; use **Reload**. Indexes stay in memory
  and are rebuilt after saving or reloading.
- Definition order matters in Clojure. A name/category list does not represent
  file loading order. Save preserves the definition's position, but does not
  check dependencies or forward references. Displaying and handling that order
  remain [pending design work](docs/definition-order.md).
- The TUI provides basic text clipping. Emoji and double-width character display
  still need visual validation. Both interfaces still require the manual
  demonstration described in `docs/verification.md`.
- Extensions are registered from trusted Clojure code. There is no dynamic
  installation, catalog or sandbox. The example loads only through an explicit
  option.
- The source order view (`o` in the TUI, the **Source order** button in Desktop)
  shows the real file order of top-level forms per namespace, including `ns`,
  `declare`, `comment`, discarded and unknown forms, marking the current
  definition. It counts only significant forms (comments and blank lines are not
  counted) and describes the parse structurally: it does not resolve references,
  expand macros or prove that the file loads. Files with parse errors appear
  only in diagnostics.
- nREPL, evaluation, Live/Combined World and a Test Runner are not implemented.

## Verification

```sh
clojure -Srepro -M:test
clj-kondo --lint src apps test test-desktop test-tui test-support deps.edn build.clj
clojure -Srepro -M:tui:tui-test
JAVA_CMD=/usr/lib/jvm/java-27-openjdk/bin/java clojure -Srepro -M:desktop:desktop-test
```

The last command exercises lists, the editor, filter, Save/Discard/Reload buttons,
extensions and the save shortcut in a JavaFX window using its built-in headless
backend. The `:desktop-test` alias enables this mode only for tests. Neither Xvfb
nor display access is needed. Adjust the JDK path for your machine.

The TUI suite feeds Lanterna's virtual terminal input queue and starts a test
editor process; it does not require an interactive terminal. Both suites edit
temporary copies of the example with BOM, Unicode and CRLF, and compare the
complete file bytes. UI I/O errors are injected at the write point; Save and the
service remain real.

There is no remote CI: the prototype relies on the local checks above. A GitHub
Actions workflow existed briefly on 2026-11-21 and was removed by owner
decision; its single run surfaced two macOS-only test assertions, fixed in the
same commit (see [verification.md](docs/verification.md)). For an explicit
local-cache check without changing production dependencies:

```sh
python3 scripts/check-cached.py --java /path/to/jdk-27/bin/java --cached-rewrite 1.2.50
```

This checks compatibility with **rewrite-clj 1.2.50**; it does not certify the
pinned 1.3.59 version. Observed results are documented in
[verification.md](docs/verification.md).

The additional POSIX check uses real Vim and a pseudoterminal on a disposable
copy of the example. It requires Python with `pexpect` and `pyte`, Vim and `stty`:

```sh
python3 scripts/check-terminal.py --java /path/to/jdk-27/bin/java
```

Use `--maven-repo` for an alternative cache and `--evidence-dir` for transcripts,
frames and results (default: `.cache/terminal-check/run`). The check covers saving,
conflicts, permissions and terminal recovery. It is process automation and does
not replace manual acceptance or validation inside Emacs. Use
`--editor-setting editor` or `--editor-setting visual` to exercise quoted commands
and arguments in those variables; the default `args` uses `CSB_EDITOR_ARGS`.
