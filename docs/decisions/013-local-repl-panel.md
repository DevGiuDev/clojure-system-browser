# ADR 013 — Local nREPL panel

Adopted for the first slice of milestone 5 (2026-11-21).

Desktop and TUI share an adapter in `apps/live/src` and use nREPL 1.7.0 through
their respective aliases. The TUI uberjar includes the adapter. The base
classpath, CLI alias and core tests stay independent of the runtime.

The panel reads the default port from `.nrepl-port` at the root of the open
project. The user can override it before connecting to the existing
Clojure/JVM nREPL on `127.0.0.1`.
An adapter worker handles the TCP connection, capability check and persistent
session. Both clients issue the same explicit actions and display the status,
session namespace and bounded transcript. Output and evaluation errors arrive
as separate messages. Interruption targets the active request. A timeout
requests interruption, then closes the connection if the evaluation fails to
finish within the grace period. Cancellation cannot undo side effects that
have already run.

Evaluate definition sends the visible text, including any unsaved edit, for
evaluation in the definition's loaded Clojure/JVM namespace. Save persists
text through the source service. [ADR 014](014-runtime-inspection.md) adds
host configuration and runtime exploration.
