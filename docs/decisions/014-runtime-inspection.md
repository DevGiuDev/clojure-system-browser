# ADR 014 — Live and Combined runtime inspection

Adopted for milestone 5 (2026-11-21).

Desktop and TUI use a shared inspector under the nREPL panel. The worker
clones a separate inspection session to preserve the console's namespace and
transcript. Opening, drilling down or refreshing sends a fixed query through
standard nREPL eval. The query reads loaded namespaces, interned vars and
selected metadata. The adapter reads and validates bounded EDN responses.
Neither CIDER middleware nor project loading is required.

Live lists observed namespaces and public and private vars. Combined joins
them against the current source index by name. It reports indexed source only,
runtime only, presence in both, or unknown. A shared name marks presence in
both for one recognizable JVM var-defining form in a `.clj` file. Duplicates,
unsupported forms, `.cljc` and `.cljd` matches remain unknown. A failed query
also leaves source rows unknown. Observations can change between requests.
File and line metadata come from the runtime and may be stale. Presence in
both makes no claim about the source text the JVM loaded.

The endpoint defaults to loopback. An editable host and port allow connections
to an explicitly chosen nREPL. Use a trusted server and a secure tunnel for
remote access. The runtime adapter and UI aliases own the nREPL dependency.
