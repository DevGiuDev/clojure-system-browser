# ADR 004: lexical highlighting in Desktop

Status: adopted.

The standard TextArea is replaced by RichTextFX's CodeArea **0.11.7**, a
published stable version compatible with JavaFX 25 or later per the project.
The dependency is limited to `:desktop`; core and TUI keep their classpaths.
VirtualizedScrollPane provides horizontal and vertical scrolling. The cljfx
life cycle withdraws the subscription and frees the editor on unmount.

Colors are computed from lexical tokens, without building another index or
duplicating the source parser. Incomplete drafts, strings with escapes,
comments, characters and Unicode are supported. No namespaces, readers or
macros are loaded. Colors do not certify syntactic or semantic validity: Save
keeps the service's validation, the original revision and its writing limits.

Highlighting changes styles and does not replace text when receiving the
draft's echo, preserving cursor, selection and undo. Changing definitions
clears the editor history. RichTextFX represents paragraph breaks as LF; the
synchronization compares that representation to avoid spurious replacements.
Undoing back to the original text restores its exact CRLF/CR endings in the
session. New edits use LF inside the definition; the service keeps preserving
the bytes outside the saved range.

The TUI keeps its current presentation; its editing through `$EDITOR` remains
independent of the Desktop control.

Update: [ADR 006](006-shared-syntax.md) extracts the classifier to
`csb.syntax` and adds colors to the TUI panel. Editing through `$EDITOR`
remains independent.

Official sources consulted on 2026-11-15:

- [RichTextFX: stable version and JavaFX 25+ requirement](https://github.com/FXMisc/RichTextFX)
- [0.11.7 release](https://github.com/FXMisc/RichTextFX/releases/tag/v0.11.7)
- [Artifact and dependencies on Maven Central](https://central.sonatype.com/artifact/org.fxmisc.richtext/richtextfx/0.11.7)
