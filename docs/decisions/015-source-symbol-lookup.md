# ADR 015 — Source symbol lookup

Desktop shows **Uses** beside the source of the selected function, var,
macro or multimethod. Each row identifies an enclosing definition, the
relative file path, the line of the first use, and a count when that
definition contains multiple uses. Navigation uses the session's draft
guard. Context menus offer definition lookup, symbol uses and related tests.
The editor uses the symbol under the pointer or caret and falls back to the
current definition. Contextual results take over the pane until they close.

The source adapter records direct `:require` aliases and explicit `:refer`
entries per file. It collects symbol positions from the parsed forms and
omits comments, strings, quotes and discarded forms. The service publishes
the reverse index alongside the definitions on project open, reload and
successful reindex after save. Related tests filter those matches to indexed
test definitions. Source browsing reads project code as data.

Local bindings can shadow names. Runtime resolution, macro expansion and
the transitive call graph are outside the analysis. Duplicate target
definitions produce an ambiguity message in the automatic pane. A confirmed
save with a failed reindex makes usage navigation unavailable until reload.
Desktop queries the snapshot in memory and binds row selection to its version.
