# ADR 005: own theme and change-driven refresh in the TUI

Status: adopted.

The TUI inherited the terminal's background and used ANSI white. With some
terminal/Emacs themes the text was barely visible. Additionally, each
iteration cleared the screen and requested a full refresh, even without
input, every 30 ms.

Presentation is separated into `csb.tui-view`, without changing the service
or the editing flow. It defines background and foreground for every cell
using colors from the extended 256-color palette, outside ANSI 0–15. The
initial theme is dark; `t` toggles a light one during the session, keeping
the draft. Terminal, Emacs or external editor configuration is not modified.
A terminal without 256-color support requires additional validation.

The controller keeps the last presented state/size. Without changes it paints
and refreshes nothing; it keeps polling input and resize. The view fills only
the back buffer and uses DELTA for normal updates, avoiding `Screen.clear`
and its automatic erasing on large changes. Startup, resize and return from
`$EDITOR` require COMPLETE: the physical terminal may be empty even if the
front buffer or the session state have not changed. Editor return always
invalidates the presentation, even with an identical draft.

The tests verify explicit colors, minimum text contrast of 4.5:1 in the
built-in palettes, selection and draft retention, absence of idle output,
resize without keys and physical terminal recovery. The real-Vim workflow is
repeated in a PTY. This does not yet demonstrate the appearance or key
delivery inside a user Emacs/EXWM session.
