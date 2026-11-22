# ADR 017 — Project Java VMs

Desktop and TUI share a JDK setting keyed by the canonical project root in
the user's Java Preferences. An empty selection inherits the launcher
environment. The preference lives outside the project and source browsing
requires no JDK discovery or project execution.

Opening the Java VMs panel lists installations from the current runtime,
`JAVA_HOME`, `PATH`, and common installation folders. Both clients use the
same discovery results. Discovery checks executable paths, resolves and
deduplicates aliases, and reads version and vendor from `release` without
launching Java. Selecting an installation fills the editable path field.

CSB checks the selected directory for an executable `bin/java` (or
`bin/java.exe`) on save and before starting the subprocess. The Test Runner
sets `JAVA_HOME`, `JAVA_CMD`, `LEIN_JAVA_CMD` and puts the selected `bin`
first on the child's `PATH`. Leiningen can override its project JVM with
an explicit `:java-cmd` in the project configuration. Babashka runs natively
and passes the environment to programs it launches.

The choice applies to commands started by the Test Runner, including
compilation within those commands. CSB starts with its launcher JVM, and
Live World connects to an independently started nREPL.
