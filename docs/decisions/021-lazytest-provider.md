# ADR 021 — Lazytest support in Tests

The source index recognizes top-level `lazytest.core/defdescribe` forms,
including namespace aliases and referred or renamed macros in direct
`:require` libspecs. Each suite remains a read-only definition with its own
source location. Opening a project reads configuration and source as data.

When the selected Clojure CLI alias launches `lazytest.main`, both clients use
the existing Test Runner process. Scoped runs replace the alias's namespace
and var selectors with the selected namespace or fully qualified suite var.
The process runs once and uses the project's dependency, paths, metadata
filters and human-readable reporters.

A reporter in the child JVM maps Lazytest's namespace and var events to the
existing status stream. The status of a suite reflects its executed cases.
Suites omitted by Lazytest's filters stay gray. Runs target the framework
associated with the configured command. Nested cases remain visible in the
runner output and share their parent suite's source navigation.
