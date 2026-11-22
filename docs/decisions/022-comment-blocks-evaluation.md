# ADR 022 — Comment blocks and contextual evaluation

Top-level `(comment …)` forms become first-class read-only entries of the
Source index. `csb.source` emits them beside the definitions of the same
revision as locator entries identified by file, top-level ordinal and
revision — the same id space as definitions, never colliding, because a
top-level form is either a definition or a block, never both. Each entry
keeps the whole form verbatim from the parser range (inner comments, spacing
and line endings included) plus the direct body forms in order; discarded
forms between them are skipped, and the wrapper itself never runs. Nothing
is evaluated while browsing.

The namespace category list gains a `(comment)` facet with its own counter,
shown only when the namespace has blocks. It lists the blocks by file and
line, and selecting one shows the whole block in the read-only code panel
through the same navigation guards as any definition: a pending draft blocks
it and survives, editing and saving are refused with an explicit reason, and
`(all)`, search, the uses index and symbol lookup keep working on symbols
only. Blocks in `.cljd` files are listed but never evaluated.

Whole-block evaluation reuses the local nREPL panel (`csb.repl`) and the
existing console session, history, limits, timeout and interruption. The
**Evaluate** button and F6 dispatch on the current entry: a definition keeps
evaluating the visible draft, a block sends its body forms one by one in the
source namespace, advancing after a complete response without error and
stopping at the first failure — earlier forms keep their effects, the
remaining ones stay unexecuted, and an interrupt, timeout or disconnection
drops the queue. An empty block, a busy session, a stale revision against
the snapshot and a missing or unloaded namespace are rejected with an
explicit message; the browser never requires the namespace itself.

Selections are evaluated literally: the exact text captured when the menu
opened is sent as typed in the namespace of the shown code, so a selection
that includes the outer `(comment …)` form returns nil and runs no body
form — running the body is the whole-block path, not the selection path.
Desktop captures the row or the selection when its menu opens and re-checks
the revision when it executes, opening the console if it was closed or
showing the inspection. Evaluating never writes sources, never changes the
draft and never touches the console input; when Live World's Save/Eval split
arrives this stays a distinct action from Save.

### Verification

Behaviour tests were written first and confirmed failing before the
implementation: block discovery and counters in `test/csb/source_test.clj`
and `test/csb/session_test.clj`; sequential evaluation, stop on error,
interruption, disconnection, stale rows, `.cljd`, literal selections and
unloaded namespaces in `test-live/csb/repl_test.clj` against a real nREPL;
the Desktop menus, disabled states, draft retention and initial view in
`test-desktop/csb/desktop_smoke.clj`; the TUI navigation, F6 dispatch and
transcript in `test-tui/csb/tui_smoke.clj`. See
[verification](../verification.md) for the executed results.
