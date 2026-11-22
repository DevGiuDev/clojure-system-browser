# ADR 009: category facets with counts and unfiltered view by default

Status: adopted (2026-11-21).

The central panel of the three listings showed categories derived only from
the definition kind; in a typical namespace that was two or three uninformative
rows. It evolves into facets with counts and a namespace summary:
`list-categories` returns `{:total :files :facets [{:category :count}]}`,
aggregated over the already-indexed definitions (no new parsing), with facets
only for present types, ordered by name. The aggregation lives in the service
so Desktop, the TUI and headless consumers (CLI, extensions) share the same
contract; the clients only format.

The session composes the visible list with a final "(all)" entry
whose `:category` is nil, and stops preselecting the first category: opening
or reloading a namespace shows all of its definitions. `definitions` filters
only when a facet is chosen; selecting "(all)" restores the full list.
Derived behavior: the initially selected definition can change (it is now the
first alphabetical one of all, not the first of the first category), and the
top bar's text filter keeps applying over the already-faceted list.

There is no new correspondence with vars nor historical identity: the counts
are data derived from the in-memory revision. Per-file detail and real order
remain in the source order view (ADR 008).

Update (2026-11-21, same day): the sentinel label is "(all)"; the initial
implementation shipped it as "(todas)". Product strings and documentation are
English throughout.

Update (2026-11-22). Both clients add a "(requires)" selector for each
namespace. It shows the indexed `:require` and `:require-macros` clauses in
the source pane as read-only text. The index preserves the declarations and
their reader conditionals in source order, grouped by file. Definition facet
counts continue to describe definitions.
