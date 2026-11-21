# Roadmap

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

Latest recorded verification: **65 tests / 648 assertions**, **40 process
checks** and clean lint. Live World/nREPL and the Test Runner remain
unimplemented.

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
| 0: preparation | deps, example, tests, decisions and CI; dependencies available; automated test in pseudoterminal with real Vim | Manual TUI demonstration per platform |
| 1: core and source | Index, categories, extraction and Save; `.cljd` reading; lexical classifier without UI; 40 tests pass with rewrite-clj 1.3.59 | System matrix in CI |
| 2: Desktop | Four panels, syntax highlighting, filter, draft, Save/Discard/Reload; controls, colors, selection, undo, shortcut, conflicts and errors tested headless with JDK 27 | Full visual demonstration and manual interaction |
| 3: TUI | Dark/light themes with `t`, lexical highlighting shared with Desktop, contrast and change-driven refresh; virtual terminal and 40 checks with pseudoterminal/real Vim; Save/Ctrl+S, conflicts, permissions and terminal restoration | Full manual interaction, highlighting inside Emacs/EXWM and per-platform behavior |
| 4: polish/extension | Registry v1, command, view and documentation | Validation of both clients and system matrix |
| 5: Live World | Not implemented | After the MVP closes |
| 6: Test Runner | Not implemented | After validating nREPL |

## Next verifiable step

User confirmation (2026-11-21): Desktop saving works and the TUI workflow with
`emacsclient` in their Emacs/EXWM session works too — editing in a buffer of
the existing frame, returning with `C-x #` and saving from the TUI. This
closes the blocker recorded on 2026-11-15 (the editor opened another
Emacs/frame).

The core and automated interaction suites of Desktop/TUI pass with the pinned
versions. Owner decision (2026-11-21): the smoke test is considered validated
and work continues with the next feature; the full acceptance matrix remains
recorded as not manually demonstrated. The chosen feature is
[definition order](definition-order.md). CI checks run locally; a Git remote
has now been configured (`origin`), but the workflow has not yet run on it —
its first remote execution remains pending. Manual launching
(`workflow_dispatch`) is also enabled.

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
([ADR 008](decisions/008-source-order-view.md)). Still pending, before
extending write operations or designing Live World evaluation, are the
conservative forward-reference diagnostics and the explicit placement or
`declare` options. Automatic code reordering has not been implemented nor
been decided on.

Before Live World, persistent IDs, per-connection capabilities, sessions,
cancellation and partial results will be reviewed. Stable available versions
will be verified again. nREPL is not added to the MVP classpath.
