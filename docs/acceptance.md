# Manual acceptance of the Source World MVP

Status: **pending manual demonstration**. The JavaFX controls and virtual
terminal suites described in [verification](verification.md) provide
automated regressions, but do not close this acceptance. Do not start or
connect a REPL.

## Preparation

Work on a disposable copy of `examples/sample-project`, not on the repository
example nor on working sources. Keep an initial copy of `src/shop/orders.clj`
outside the source roots to compare bytes. Select a compatible JDK per
command as described in the README.

```sh
JAVA_CMD=/path/to/jdk-27/bin/java clojure -Srepro -M:desktop --project /path/to/the/copy --example-extension
JAVA_CMD=/path/to/jdk-27/bin/java EDITOR=vi clojure -Srepro -M:tui --project /path/to/the/copy --example-extension
```

On Windows, set the variables with the syntax of the shell in use. For an
editor with arguments use `CSB_EDITOR_ARGS` as an EDN vector. Record system,
architecture, JDK, terminal, editor and screen scale of each run.

## Desktop

1. Start with `--project` pointing to a nonexistent path and check the visible
   error, the window without a project and Save disabled; open the copy with
   **Open project…**. Repeat startup with `--project` and confirm no REPL
   console appears.
2. Navigate to `shop.orders` → `functions` → `total`; check only that form is
   shown. Filter by `ToTaL`, clear the filter and navigate with the keyboard.
   In Categories, check the facets with counts (`functions · 2`) and the
   namespace summary in the title; with no facet chosen the whole namespace
   is listed and "(all)" restores the complete list.
3. Change the body, save with **Save** and then with Ctrl+S / Cmd+S. Compare
   the file bytes with the initial copy: only the selected form should change.
   Repeat with BOM, CRLF and a Unicode comment before the form.
4. Leave `(defn total [` as a draft and save: visible error, disk intact and
   draft retained. Check the locked lists, **Reload** and window close.
   Discard must restore the saved text and allow navigation.
5. Create another valid draft and add a comment to the file from another
   editor. Save must show a conflict, preserving both the external comment
   and the local draft. Use **Discard**, **Reload** to accept the disk.
6. Test a write failure on the disposable copy (for example, temporarily
   remove write permission from the directory where the system allows it,
   restoring it afterwards). File and draft must be preserved; the error must
   be distinguishable from a confirmed save.
7. Test a read-only definition, a file with a parse error, the
   **Example: hello** and **Project summary** buttons, resize and scaling.
   Exit with a clean draft and confirm the process closes.
8. Open **Source order**: check the real per-file form order (the `ns` form
   included), the current-definition marker, navigation by selection with the
   panel open and closing with `Esc` or the button itself.

The CodeArea control may normalize line endings inside the edited form; the
exact-preservation guarantee applies to the bytes outside that form.

## TUI

1. Open the same copy and compare namespaces and definitions with Desktop.
   Navigate with Tab, arrows and PgUp/PgDn; check help `?` and diagnostics
   `!`. Review panel, code, selection, error and help contrast in both themes
   (`t`). Leave the application idle: there must be no continuous flicker.
   Check colors of forms, keywords, strings and comments; scroll a multiline
   string and also review a read-only definition.
   In the Categories column, check the facets with counts, the summary in the
   header and "(all)": with no facet chosen all definitions are
   listed.
2. Shrink the terminal below 60 × 16 and enlarge it again: it must show the
   warning and recover the view and selection without failing.
3. Select `total`, press `e`, edit in the real editor and return. A temporary
   containing only the definition must open; the project file stays intact
   until `s` or Ctrl+S. Compare the bytes after saving.
4. Repeat invalid syntax, external conflict and write failure. Check visible
   error, disk intact and draft retained. `d`, followed by `r`, accepts the
   disk version. Navigation, `r` and `q` must not discard the draft.
5. Test an editor that exits with a nonzero code: the indicated temporary
   must be preserved and the browser regained. Also test an editor with
   arguments and one whose executable or path contains spaces.
   Test `VISUAL`/`EDITOR` with quoted arguments, invalid configuration and a
   nonexistent executable: the panel must show the reason and allow returning
   with `Esc`, keeping the draft. With `emacsclient`, check an active server,
   editing in a buffer of the existing frame (no `-c`) and returning via
   `C-x #`, without `-n`/`--no-wait`. The README POSIX example uses
   `--alternate-editor=false` to avoid a fallback editor when the server is
   missing.
6. Press `x` and `v` to observe the command and the view. Exit with `q` after
   saving or discarding: the shell must regain the terminal and its echo. On
   EOF with a draft, check the emitted recovery path and the temporary's
   content.
7. Press `o`: the source order panel shows the real form order with the
   current definition marked; navigating moves the marker without closing the
   panel, `Esc` returns to the code without losing the draft, and
   help/diagnostics are mutually exclusive with it.

In an Emacs/EXWM session, also record the terminal or buffer type used and
check that keys reach the application. Test `s` and Ctrl+S separately, also
after returning from the editor. On POSIX, compare `stty -g` before and after
the process: it must restore the previous settings. The automated
pseudoterminal test does not verify keys intercepted by Emacs or EXWM.

## Additional automated evidence (2026-11-15)

`scripts/check-terminal.py` completes **40 checks** with the production TUI
and real Vim, on a temporary copy of the example. It includes navigation,
resize, editing, `s` and Ctrl+S, byte preservation, invalid syntax, conflict,
a real permission failure, failed editor, themes with explicit background,
syntax colors in both themes, absence of
idle output and restoration of terminal settings
on exit and on a startup exception. See commands and environment in
[verification](verification.md). It is not manual acceptance nor a run inside
Emacs/EXWM; the following checkboxes remain pending.

The user confirms the theme appearance looks right in their TUI inside
Emacs/EXWM. This confirmation covers the theme appearance; the highlighting
added afterwards and the full editing/error workflow still require their
checking in that session.

## Acceptance log

Partial user feedback — 2026-11-15: confirms Desktop saves. In the TUI they
could not yet complete saving: the editor seems to open another Emacs or
frame instead of a buffer in the existing one. The README example is fixed to
use `emacsclient` without `-c` and to explain returning with `C-x #`;
trying it in their Emacs/EXWM session remains pending. This confirmation does
not demonstrate byte comparison, conflicts or the rest of the Desktop
workflow.

Repeated confirmation — 2026-11-21: the user tests Desktop saving again and
confirms the file is saved. They did not detail the system nor which
workflow steps were covered; byte comparison, drafts, conflicts and the rest
of the Desktop workflow remain manually undemonstrated.

TUI confirmation with emacsclient — 2026-11-21: the user validates the TUI
workflow in their Emacs/EXWM session following the README example
(`emacsclient` without `-c`, with `--alternate-editor=false`): editing opened
in a buffer of the existing frame, returning with `C-x #` worked, and saving
from the TUI updated the project file. This closes the blocker recorded on
2026-11-15. The remaining TUI workflow steps (bytes, invalid draft, conflict,
write failure, terminal restoration and highlighting in that session) were
not detailed.

Owner decision — 2026-11-21: the smoke test (Desktop saving and the complete
external-editing workflow with emacsclient in the TUI) is considered
validated, and work continues with the next feature. The acceptance matrix is
not marked complete: byte comparison, drafts, conflicts, write failures, the
platform matrix and remote CI remain recorded as not manually demonstrated.
The source order view and the category facets postdate that decision: their
verification is automated only.

Do not mark a checkbox based only on the automated suite. Attach date,
environment, result and a reference to issues or evidence when completing
each workflow.

| System | Manual Desktop | Manual TUI and editor | Keyboard, resize and scale |
|---|---|---|---|
| Linux | Pending | Pending | Pending |
| macOS | Pending | Pending | Pending |
| Windows | Pending | Pending | Pending |

There is no remote CI: the owner removed the GitHub Actions workflow on
2026-11-21 (the prototype relies on local checks). Its single run — headless
Desktop passed on Linux; the macOS core job surfaced two path-resolution
assertions, fixed in the removal commit — is recorded in
[verification](verification.md) and demonstrated no manual workflow. The
columns above remain the pending evidence.

The MVP remains open while this evidence is missing. Live World/nREPL stays
in Milestone 5; the Test Runner, in Milestone 6.
