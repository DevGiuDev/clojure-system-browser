# ADR 023 — The `:test` plus `:runner` alias convention

A common Clojure CLI layout splits the test configuration across two
aliases: `:test` carries the extra paths, the testing dependencies and the
JVM options, and `:runner` carries only the `:main-opts` that launch the
runner. The Test Runner never saw `:runner`, so such projects fell through
to a `test` task in `bb.edn` or the default command, and namespace or var
scoped runs were reported unavailable.

When no configured `:test`, `:tests`, `:kaocha` or `:lazytest` alias carries
`:main-opts` or `:exec-fn`, the runner now considers the `:runner` alias,
and only when it launches a recognized test runner — Kaocha, Cognitect or
Lazytest through `-m`, or the Cognitect test-runner `:exec-fn`. Unrelated
`:runner` aliases keep the existing bb.edn, Leiningen and default fallbacks,
and the priority order is unchanged ([ADR 016](016-test-runner.md)).

The child command composes every configured test alias instead of only the
one that supplied the main-opts, so the classpath keeps the paths,
dependencies and JVM options declared in `:test`, and scoped runs reuse the
runner's native selectors ([ADR 021](021-lazytest-provider.md)).

A `test` task in `bb.edn` remains a suite-only command: a bb task cannot
express namespace or var filters without executing it, so its scoped runs
stay unavailable with the existing message.
