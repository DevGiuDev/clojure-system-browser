# ADR 012: release packaging — TUI/CLI uberjar

Status: adopted (2026-11-21).

The application needed an easy way to download and use the browser without
cloning the repository. The owner chose the mainstream Clojure pattern over
the alternatives: one uberjar built with tools.build, published to GitHub
Releases together with a small launcher script. GraalVM native binaries
(the babashka/clj-kondo model: single executables on PATH) were rejected as
out of proportion for a prototype with a Lanterna TUI and a JavaFX client;
`jpackage` installers (the standard JavaFX distribution, bundling a JRE per
platform) were rejected for now because they multiply the build matrix and
the Desktop already requires a JDK 25+ installation to be useful.

`build.clj` exposes `uber` (and `version`, which reads the version from
`src/csb/cli.clj` without evaluating it). The uberjar includes only the core
sources and the TUI: the basis comes from the `:tui` alias, `csb.tui` is the
AOT-compiled main with `(:gen-class)` so `java -jar` finds a real main class,
and the jar contains no JavaFX, cljfx or RichTextFX classes. It runs the TUI
on Java 21 or later; the headless EDN CLI stays a repository alias, and the
Desktop keeps the documented repo workflow.

The tag-triggered workflow (`v*`) is build-only: it checks that the tag
matches the declared version, builds the jar on JDK 21, and publishes jar
plus the `csb` launcher (which honors `JAVA_CMD`). It runs no tests and
makes no verification claims, keeping the recorded CI decision — checks
remain local; nothing is claimed about remote execution. Users install by
copying `csb` and the jar into a directory on `PATH`.
