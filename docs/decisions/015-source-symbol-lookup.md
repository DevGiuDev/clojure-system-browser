# ADR 015 — Source symbol lookup

Desktop offers definition lookup, symbol uses and related tests from the
editor and definition list. The editor uses the symbol under the pointer or
caret and falls back to the current definition. Results open in a pane beside
the code, with navigation subject to the session's draft guard.

The source adapter records direct `:require` aliases and explicit `:refer`
entries per file. The service uses that context to match names against the
indexed project. Usage searches walk candidate definitions with the existing
parser and omit comments, strings, quotes and discarded forms. Related tests
filter those matches to indexed `deftest` forms. Sources are never evaluated.

Matches identify enclosing definitions. Local bindings can shadow names, and
runtime resolution, macro expansion and the transitive call graph are outside
the analysis. The service owns the lookup and Desktop runs it off the UI thread.
