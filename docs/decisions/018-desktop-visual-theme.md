# ADR 018 — Desktop visual theme

Desktop keeps one light theme: neutral green-tinted surfaces, a teal accent
for selection and the primary action, and amber, red and green state tones.
Colors live as named `-csb-*` variables in
`apps/desktop/src/csb/desktop/theme.css`, loaded once from the scene;
`code-editor.css` reuses the same variables and keeps its lexical classes.
The dark theme stays out of scope for this iteration.

Icons are one vector family of 16 px stroked SVG paths drawn with JavaFX
(`csb.desktop.icons`), no external assets. Actions, views and list headers
keep visible labels; definition kinds, read-only locks and operation states
add icons with accessible text, and header icons use a muted tone so the
definition name stands out.

Operation severity is data, not inference: `csb.session` attaches a
`:status-tone` (`:warning`, `:error`, `:success` or nil) when it builds a
status message, from the result's error type or the operation it already
knows. Clients never derive severity from the message text. A confirmed
save whose reindexing failed reports `:warning`; draft and error markers
render simultaneously.
