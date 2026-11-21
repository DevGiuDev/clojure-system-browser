# ADR 010: Polylith workspace discovery

Status: adopted (2026-11-21).

[Issue #1](https://github.com/DevGiuDev/clojure-system-browser/issues/1)
asks for support of Polylith-based projects, which have no top-level `src`
(or `test`) folders. The top-level folders are `bases`, `components` and
`projects`, each brick below `bases` and `components` carries its own
`src`/`test`, the `development` project may carry `src` (and `test`), and
`projects` rarely carry `src`. The root `deps.edn` usually declares no
top-level `:paths`; the paths live in alias `:extra-paths`, which the adapter
does not read, so such a workspace opened empty and in silence.

Decision: when explicit roots are absent and `deps.edn` declares no top-level
`:paths`, the adapter detects a Polylith workspace from the filesystem
conventions alone and derives the roots. Detection accepts a `workspace.edn`
at the project root (or the older `.polylith/workspace.edn`), or the presence
of both `components` and `bases` folders. The derived roots are, in order:
every brick under `components` and `bases` contributes its existing `src` and
`test` directories (bricks listed sorted), `development` contributes its
existing `src` and `test`, and each project under `projects` contributes its
existing `src` only, as the issue describes. Explicit `--source-root` options
and `deps.edn` `:paths` keep precedence, so misconfigured or hybrid projects
keep the documented behavior, and a lone `components` folder does not switch
the layout. Symlinked bricks are excluded because roots must be real
directories, as before.

The content of `workspace.edn` is never read beyond its existence and nothing
of the workspace is executed; this preserves the no-evaluation boundary. The
save path is layout agnostic, so the byte-preservation, revision and conflict
guarantees apply unchanged. `examples/poly-sample-project` is a minimal
workspace with the layout for manual and headless browsing. What remains
open: a Desktop roots picker is not added; users of unusual layouts keep the
`--source-root` option.
