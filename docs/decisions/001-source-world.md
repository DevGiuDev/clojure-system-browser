# ADR 001: Source World and initial structure

Status: adopted for the initial implementation.

A single `deps.edn` is used, with separated namespaces and exclusive aliases
for each client. The domain, service, source adapter and extension registry do
not depend on UI. Ports use Clojure protocols; results and errors are data.
There is no fake runtime and no nREPL dependency.

Desktop with cljfx/JavaFX and a TUI with Lanterna are kept, per the plan's
candidates. Lanterna provides input, a screen buffer, a virtual terminal and
resize. The client defines the layout, without implementing ANSI escapes or a
terminal engine. Initial editing is through a temporary and `$EDITOR`.

The TUI choice is **provisional until the technical test is run**. It is not
claimed that maintenance or compatibility on the three systems is demonstrated
just by an artifact being published.

The stable versions verified on 2026-11-15 are pinned in `deps.edn`.
JavaFX is aligned at 27.0.1 to avoid mixing transitive versions.

Sources:

- [Clojure](https://clojure.org/releases/downloads)
- [Clojure CLI](https://clojure.org/releases/tools)
- [rewrite-clj 1.3.59](https://clojars.org/rewrite-clj/versions/1.3.59)
- [cljfx](https://clojars.org/cljfx)
- [JavaFX](https://openjfx.io/openjfx-docs/)
- [Lanterna](https://central.sonatype.com/artifact/com.googlecode.lanterna/lanterna)

These versions are not silently replaced by the ones available in cache.
The offline check script requires explicitly choosing any alternative version
and shows which one runs.
