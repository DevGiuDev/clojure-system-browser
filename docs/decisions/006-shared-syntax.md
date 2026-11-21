# ADR 006: lexical highlighting shared between Desktop and TUI

Status: adopted.

The TUI panel had its own theme, but showed every form in the same color. The
existing lexical classifier is extracted from Desktop into `csb.syntax`, a
pure function with no UI dependencies. Its tests move to the core alias.
Desktop keeps applying the same ranges and styles; the TUI uses those tokens
with indexed colors from its dark/light themes. The source parser is not
duplicated and no dependencies or evaluation capabilities are added.

The TUI classifies the whole definition before splitting and clipping lines.
This way multiline strings, including incomplete ones, keep their style when
their opening falls outside the view. Only CRLF/CR are normalized in the
displayed copy; the draft and the saving service keep the original text.
Width and segment clipping use Lanterna's utilities. Help and diagnostics are
not treated as code. The width limits of some Unicode characters still
require per-terminal visual validation.

Colors do not certify validity for Save. The tests check real buffer colors
and PTY output in both themes, contrast, scroll, incomplete text,
Unicode/CRLF, read-only and draft retention. The loop keeps its
change-driven refresh, with no idle output.
