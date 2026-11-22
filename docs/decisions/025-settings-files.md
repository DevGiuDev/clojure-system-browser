# ADR 025 — Settings files (global EDN plus per-project overrides)

CSB persists its own settings as EDN in two layers that merge over built-in
defaults, in order: the global file, then the project file. The global file
is `$XDG_CONFIG_HOME/clojure-system-browser/settings.edn` (defaulting to
`~/.config/...` on Linux). The project override is
`<project-root>/.csb/settings.edn`; a dot-directory keeps room for future
project-scoped files the way `.clj-kondo/`, `.lsp/` and the Polylith
`.polylith/` already do. Both paths are user choices; CSB never requires
them and nothing is written on open, browse or validate.

This extends the boundary set by the specification against proprietary
project persistence: `.csb/settings.edn` is created only by an explicit
save from the settings UI (or by the user's hand), `deps.edn` is never
touched, and deleting the file returns the project to defaults. The
per-project JDK preference of [ADR 017](017-project-java-vms.md) stays in
Java Preferences; migrating it into EDN is left for later.

Reading is tolerant so browsing never fails because of settings: a missing
layer is skipped, and unreadable EDN, tagged literals, unknown keys or
unsupported values turn the whole layer into a warning (surfaced in the
session status and kept in `csb.settings/load-settings`) while the rest of
the merge continues. Nothing in a settings file is evaluated — `clojure.edn`
with a rejecting `:default` handler, the same posture as reading
`deps.edn`.

Saving is strict: unknown keys or values that fail the schema reject the
whole save with `:settings-invalid` and leave the file untouched, and an
existing unreadable file must be fixed or removed by hand first (the UI
never overwrites content it could not read). Writes merge the requested
values into the current layer, drop unset entries, and replace the file
atomically through a temporary in the same directory and an
`ATOMIC_MOVE`; filesystems without atomic replacement refuse the save
instead of falling back to truncation.

The first keys are `:theme` (`:dark`/`:light`, consumed by the TUI),
`:namespace-tree?` and `:source-order?` (initial view on project open),
`:repl` (`{:host string :port integer}`, the panel defaults; the project's
`.nrepl-port` still wins when detected) and `:editor-command` (an argv
vector that now takes precedence over `CSB_EDITOR_ARGS`, `VISUAL` and
`EDITOR`). Both clients expose the same rows through one session
round-trip: the Desktop gear opens a settings overlay, the TUI panel opens
with `g`, and each row shows the origin of its effective value
(default/global/project) next to a global/project save scope.
