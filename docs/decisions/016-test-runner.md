# ADR 016 — Test Runner commands and status

The Tests view groups indexed JVM `deftest` definitions by their existing
namespace. The same namespace indicator appears in the regular browser and
in Tests. Opening the view works offline and preserves the source draft.
Navigating from Tests to a definition passes through the shared session.

Discovery reads project configuration as data. A Run action starts the
selected command in the project directory with separate arguments.
Both clients share one background process, a bounded output buffer, and
cancellation. Clojure CLI uses the project's test aliases, and scoped runs
use the runner's native filters or `clojure.test` on the test classpath.
Leiningen loads the observer through a temporary injection and keeps the
project's test profile and selectors.

An observer installed in the child JVM captures test lifecycle events
alongside the project's own report. The parent validates events and matches
them to names in the source index. Grey marks cases without an observed
outcome. A namespace turns green once every indexed case reports a pass.
An aborted namespace or a failed case turns its row red.

The process reads saved files and leaves the application's nREPL session
independent. Failure events carry the first failure's diagnostic and location
when the runner provides them. Both clients highlight failures in the log and
show the diagnostic above the selected definition. A line highlight requires
the reported location to fall within the definition and its file revision to
match the source index. Rerunning a set of failures and jumping directly to
an assertion remain open.
