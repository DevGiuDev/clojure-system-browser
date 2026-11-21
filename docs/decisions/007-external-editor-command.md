# ADR 007: external editor configuration and diagnostics

Status: adopted.

The TUI edited through a temporary and an external process, but interpreted
the whole `VISUAL`/`EDITOR` value as a single executable. Common values such
as `emacsclient -c` failed. Additionally, the generic return message omitted
the cause reported by the launcher and the exit code.

`csb.editor/command` selects `CSB_EDITOR_ARGS`, `VISUAL`, `EDITOR` or `vi`.
Explicit configuration requires a single EDN vector of strings. Environment
commands accept quotes and escapes to group arguments; no shell is executed
and no expansions or redirections are applied. Compatibility with existing
literal paths containing spaces is preserved. Empty `VISUAL` and `EDITOR`
values are ignored and invalid configurations are rejected before creating a
temporary.

The process still receives exclusively the definition's temporary, with
inherited I/O for terminal editors. CSB waits for its termination. The
explicit `-n`/`--no-wait` options of `emacsclient` are rejected: returning
before editing finishes does not allow importing the draft. Emacs is not
started or configured automatically.

The TUI announces the external editor and presents errors as data in a panel
with reason, exit code, command and recoverable path when applicable. `Esc`
closes that panel without discarding changes; pagination adapts to the
visible height. A failed process does not replace the current draft nor
delete its temporary. A successful process imports the text; only Save
writes the sources through the service.

Priorities, quotes, paths with spaces and absence of expansion are verified
in the core; executable/configuration errors, failed exit and draft retention
in the virtual terminal; and the full workflow with real Vim using `EDITOR`
with arguments. The user's Emacs/EXWM session still requires confirmation.
