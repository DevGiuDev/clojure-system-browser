# ADR 019 — Namespace filter and project search

The Desktop namespace and definition panels each gain a substring filter at
the head of their column: client-side, case-insensitive, reset when another
project is opened and preserved while navigating. The definition filter
moved out of the toolbar into the Definitions column to align both. The
service returns namespaces as symbols and nothing else; narrowing the list
is presentation.

Project-wide search is a service use case, `csb.service/search`, extending
the argument of [ADR 009](009-category-facets.md): one aggregation over the
indexed snapshot, so Desktop, the TUI and headless consumers share the same
contract. It matches names only — namespace names and the qualified
`namespace/name` string of each definition, case-insensitive substrings —
and returns identity, kind, category and location per result, never sources.
Definition text and docstrings are out of scope; the index does not extract
them and no new parsing is introduced. Runtime vars remain a Live World
concern.

Desktop opens the panel with Ctrl+F / Cmd+F over a centered overlay with a
dimmed scrim. Results are grouped — namespaces first, then categories in
name order — with section headers that are not selectable rows. Enter opens
the selection through the same session guards as any navigation (a pending
draft blocks it and keeps the panel open), arrows move the selection from
the query field, and Esc or a click on the scrim closes the panel. Opening
a result goes through the reveal path, which reselects the namespace and
clears the definition filter.

The search runs synchronously on the UI thread: it is an in-memory scan of
the snapshot, like the reads the view already performs, and avoids the
busy/disable flicker of the worker queue on every keystroke.
