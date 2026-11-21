# Clojure System Browser: project instructions

These instructions apply to the whole repository. Explicit user instructions
take precedence over this document.

## Context to read

1. `README.md`: execution, requirements and product limitations.
2. `docs/roadmap.md`: current scope and pending work.
3. `docs/verification.md`: what has actually been tested and with which environment.
4. `docs/architecture.md` and `docs/decisions/`: boundaries and technical decisions.
5. `clojure-system-browser-project-prompt.md`: complete original specification.

The specification also describes future phases. Their presence in that document
does not mean they should be implemented in the current task nor that they are
available. The observed code and verifications determine the actual state.

## Product scope

- The main unit is the Clojure definition, not the file nor a general-purpose IDE.
- The MVP is Source World: explore and edit sources without starting or
  connecting REPLs.
- Desktop and TUI consume the same service and the same use cases.
- Closing the MVP requires validating the editing, conflict and error flows
  in both clients. Headless tests do not replace manual interaction.
- Live World/nREPL belongs to milestone 5 and the Test Runner to milestone 6.
  Do not add them prematurely unless the task authorizes advancing to those
  phases.

## Architecture boundaries

| Area | Namespaces / location |
|---|---|
| Model, ports and results | `csb.model` |
| Discovery, parsing and writing | `csb.source` |
| Headless API and coordination | `csb.service` |
| Draft selection and life cycle | `csb.session` |
| External editor over temporaries | `csb.editor` |
| Extension and capability registry | `csb.extensions` |
| Startup without loading JavaFX prematurely | `csb.desktop-launcher` |
| Desktop | `apps/desktop/src` |
| TUI | `apps/tui/src` |

- Do not introduce JavaFX, cljfx, Lanterna or nREPL into core dependencies.
- Keep the Desktop, TUI and core test aliases independent.
- Interfaces must not write sources nor duplicate parsers or indexes.
- The service returns results and errors as data, with explicit types.
- Keep functions small, names clear and side effects localized.
- Do not assume a 1:1 correspondence between any definition and a var.
- Current IDs are locators within a revision, not persistent historical
  identities. Do not promise stability that is not implemented.
- Extensions are trusted code, loaded explicitly. Context capabilities are
  not a sandbox within the same JVM.

## Reading and saving invariants

- Do not execute project sources, macros, `project.clj`, aliases or custom
  readers as a side effect of browsing or validating an edit.
- Normal Clojure files are the persistent source of truth.
- Initial writing: a single unambiguous top-level `defn`/`defn-` in UTF-8
  `.clj`. Clearly reject unsupported operations, renames and class changes.
- Preserve bytes outside the edited range: comments, spacing, Unicode, BOM
  and LF/CRLF/CR line endings. Do not reserialize the whole file.
- The rewrite-clj parser accepting a draft does not prove validity for the
  Clojure reader. Keep the additional validation without evaluation.
- Require the original revision and check external changes before replacing.
- Use a temporary file in the same directory and atomic replacement. Do not
  add a fallback that truncates the original file if atomic replacement is
  not supported.
- Do not present hash-check + move as compare-and-swap against all external
  writers nor promise durability under crash.
- Distinguish a failure before writing from a reindexing failure after a
  confirmed write.
- Errors must retain the draft. Navigation and normal exit must not discard
  it silently.
- `$EDITOR` works on a temporary of the definition; Save goes through the
  service.
- When Live World arrives, Save and Eval will remain distinct actions.

## JDK and dependencies

- Use stable versions and verify official publications before upgrading.
  `deps.edn` is the reference for pinned versions; avoid snapshots and EA.
- The current target is JDK 27. JavaFX 27 requires Java 25 or later.
- Check the Java used by the Clojure CLI. In this launcher, setting only
  `JAVA_HOME` may not change the Java on the PATH; `JAVA_CMD` allows
  selecting it per command. The README has an example for the JDK installed
  on this machine.
- Keep the version check before JavaFX class loading.
- JavaFX boolean properties must receive booleans, never `nil`.
- Do not modify global JDK or tooling configuration to solve a local project
  requirement.
- Do not downgrade pinned versions to hide a resolution failure. A test with
  an alternative cache must explicitly identify the versions used.

## Verification

With a compatible JDK selected, run the relevant checks:

```sh
clojure -Srepro -M:test
clj-kondo --lint src apps test test-desktop test-tui test-support deps.edn
clojure -Srepro -M:desktop:desktop-test
clojure -Srepro -M:tui:tui-test
```

- The Desktop test enables the JavaFX 27 built-in Headless backend only in
  the test alias. Normal startup must keep opening a window.
- The TUI test uses a Lanterna virtual terminal.
- For save changes, cover byte preservation, stale revisions, external
  changes, invalid syntax, I/O errors and draft retention.
- For startup or UI changes, also check the initial state and the state with
  a project loaded. Static analysis does not catch every interop error.
- Add tests that demonstrate behaviors or regressions; do not duplicate the
  implementation in the assertions.
- Record results that were actually executed. Do not claim that remote CI
  passed, that a visual review happened, or that a platform was checked
  when it was not.

## Documentation and delivery

- Update README, roadmap or verification when requirements, commands,
  capabilities or relevant results change. Record new decisions in brief
  ADRs.
- Keep the boundaries that affect source integrity explicit.
- Communicate what changed, how it was tested and what remains pending,
  distinguishing written code, headless execution and real interaction.
- Do not store credentials or machine-specific personal configuration in the
  repository.
- Do not create equivalent copies of these instructions for each tool unless
  a concrete integration needs them.
