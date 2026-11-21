# ADR 008: source order view shared by the clients

Status: adopted (2026-11-21).

Navigation by name and category hides the real order of the forms, which in
Clojure is semantically significant. The first step agreed in
[definition-order](../definition-order.md) is read-only: the service
publishes `list-source-forms`, which exposes per file all significant
top-level forms in their real order, including those not indexed today as
definitions (`ns`, `declare`, `(comment ...)`, `#_`, `#=`, unknown heads).
Each entry carries ordinal, row/column, head/name, label, first line
(`:preview`) and `:definition-id` when the form is indexed.

The ordinals are the same ones the `[file ordinal]` IDs already use: counting
only significant forms matches what the reader skips when loading. Comments
and blank lines do not count, as before. The view promises neither historical
identities nor load validity: it is a structural description from rewrite-clj
without evaluation, macro expansion or dependency analysis.

Desktop presents the view with a button that replaces the editor with a list
(`Esc` or the button itself return) and allows jumping to the selected
definition while the panel stays open; the TUI shows it as an auxiliary
panel with `o`, following the pattern of `?` and `!`, with the current
definition marked. The `:source-order?` flag lives in the shared session and
resets when a project is opened. No client parses or indexes on its own.
Files with parse errors remain visible only in diagnostics. Options 2–4 of
the design note stay open: conservative forward-reference diagnostics,
writing with explicit placement and Live World evaluation order.
