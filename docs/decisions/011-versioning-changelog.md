# ADR 011: versioning and changelog

Status: adopted (2026-11-21).

The application needed a user-visible version and a recorded change history.
`deps.edn` has no version field and no build tool owns one, so the single
source is `csb.cli/version`, a plain def consumed by all clients: the CLI,
TUI and Desktop `--help` output and the Desktop window title. It is kept in
sync manually with `CHANGELOG.md` (Keep a Changelog format), which documents
every release. Releasing means: bump the def, add the changelog entry, tag
the commit. While the project is 0.x, minor bumps carry features and patch
bumps fixes; the MVP keeps freedom to evolve without promising stability
across minors. No build-time stamping was introduced: the version must not
depend on a build step that the documented `clojure -M` workflow does not
run.
