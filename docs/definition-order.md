# Definition order: problem and pending design

Status: **option 1 implemented (2026-11-21,
[ADR 008](decisions/008-source-order-view.md)); options 2–4 open**.
Recorded on 2026-11-15 for short-term treatment. Start with a Source World
navigation design; resolve placement rules before adding insertions/moves,
and evaluation rules before implementing Live World. It does not bring those
milestones forward.

## Why it matters

Clojure loads a file's forms sequentially. A definition may need a symbol to
already be defined or declared when its body compiles. Available macros and
the values or effects produced when loading forms also matter. A browser's
alphabetical order does not replace that order.

For example, in a fresh namespace, if `subtotal` does not exist and is not
declared:

```clojure
(defn total [lines] (subtotal lines))
(defn subtotal [lines] (reduce + 0 (map :amount lines)))
```

The forward reference requires a solution, such as placing `subtotal` first
or using a proper declaration. `declare` creates vars without binding their
values: it can help with cross-function references, but provides neither an
implementation, an initial value nor a macro's expansion. It is not a
universal solution.

## What the browser does today

| Aspect | Current behavior |
|---|---|
| Listing | `csb.service/list-definitions` sorts by name, file and row; the session filters by category |
| Source order view | `csb.service/list-source-forms` exposes per file all significant top-level forms in real order, including unindexed ones; Desktop and the TUI present them with the current definition marked |
| Source position | File, location, range and ordinal within the revision are preserved |
| Saving | Replaces a `defn`/`defn-` at its current position; does not move other forms |
| Validation | Checks the reader and `defn` arguments; does not resolve new dependencies nor demonstrate that the file loads |
| Future operations | No creation, movement, reordering or evaluation of definitions |

Therefore, editing a function can introduce a reference Save accepts
syntactically but that does not resolve when loading from scratch. Preserving
position avoids reordering the file by accident, but does not by itself
address that case. Current IDs remain locators within a revision, not
historical identities for future move operations.

## Options to study

1. **Proposed first step, no writing — implemented on 2026-11-21
   ([ADR 008](decisions/008-source-order-view.md)):** an alternative view of
   the real per-file order, showing positions and context. Navigation by
   name/category is kept and the view marks the current definition, showing
   which forms precede and follow it. It includes `ns`, `declare`, macros and
   forms not indexed as definitions today; listing only functions would hide
   relevant context.
2. **Conservative diagnostics:** study forward references and dependencies
   without executing the project. Unknown cases must be explicit; static
   analysis must not promise understanding of all macros or load effects.
3. **Future writing:** agree how to choose placement when creating or moving
   forms, and when to propose a declaration. Any change must be explicit,
   reviewable and respect revisions, conflicts, unrelated bytes and
   comments. Do not adopt automatic reordering as a design assumption.
4. **Live World:** distinguish file order from the evaluation history. A var
   existing in a session can hide a load-from-scratch problem; evaluating a
   definition does not demonstrate the whole file is loadable.

The source order view and its scope shared by Desktop and the TUI are
implemented. The next concrete decision is the scope of the conservative
diagnostics (option 2): which forward references can be flagged without
executing the project, and how unknown cases are presented.

## References

- [Official reference: evaluation and sequential loading](https://clojure.org/reference/evaluation).
- [Official API: `declare`](https://clojure.github.io/clojure/clojure.core-api.html#clojure.core/declare).
- Current implementation: `src/csb/service.clj`, `src/csb/source.clj` and
  `src/csb/session.clj`; save limits in [ADR 002](decisions/002-safe-save.md).
