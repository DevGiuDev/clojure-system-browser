# ADR 020 — Desktop namespace tree

The Desktop Namespaces panel starts with a flat list. Its **Tree** checkbox
switches to expandable branches built from the dot-separated segments of
the filtered namespace names. A branch can also represent a real namespace
and open its definitions. Grouping-only branches organize the visible names.

The view choice lives in Desktop state. Both presentations use the current
namespace, filter and navigation guards from the source browser. Switching
the presentation preserves any draft.
