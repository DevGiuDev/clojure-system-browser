# ADR 003: read-only ClojureDart browsing

Status: adopted.

A project with `deps.edn` and ClojureDart `.cljd` sources opened with no
namespaces and no diagnostics because discovery only included `.clj` and
`.cljc`.

`.cljd` files are included in the same structural source adapter. Namespace
and known top-level forms are recognized when rewrite-clj can parse them,
without executing readers, resolving dependencies, loading the compiler or
starting Flutter. Structural acceptance does not demonstrate semantic validity
for ClojureDart.

`.cljd` definitions are presented as read-only with an explicit reason. Save
keeps the `.clj` limit: the Clojure reader and specs used to validate writes
do not certify the ClojureDart dialect. Writing is not extended and no runtime
is added to solve a discovery problem.

Opening another project clears the session filters and re-detects its roots.
If no namespaces are found, open and reload show the examined roots and the
supported formats instead of presenting just zero errors.
