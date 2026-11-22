# ADR 024 — Development aliases in `:runner` convention runs

Projects that follow the `:test` plus `:runner` convention often also keep
their development configuration in aliases that are neither library nor test
aliases: `:dev` carries extra development paths, and `:optional` carries
runtime dependencies exercised by the tests and marked `:optional true`.
Rephrase keeps nREPL this way, and its canonical command composes them
(`clojure -M:1.10:dev:optional:test:runner`). With only the test aliases on
the child classpath the test namespace that requires nREPL fails to load and
the run reports a failure before any test executes.

When the `:runner` alias supplies the command, the child now also composes
every alias named `:dev` or `:optional` that exists as a plain map, before
the runner and test aliases (`:dev`, `:optional`, `:runner`, then every test
alias). This is a convention, not a dependency resolver: the detection does
not look inside the aliases beyond their presence, and a configured test
alias with its own `:main-opts` or `:exec-fn` keeps the composition from
[ADR 023](023-runner-alias-convention.md) until a real project needs it
there. The `:main-opts` of the composed aliases never win — the process
monitor alias stays last — but their paths, dependencies and JVM options
apply, exactly as in the canonical development commands.
