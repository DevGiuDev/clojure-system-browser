#!/usr/bin/env python3
"""Exercise the production TUI and Vim through a POSIX pseudoterminal.

Requires pexpect and pyte in the Python environment, plus Vim and Clojure CLI.
This is automated process-level evidence, not human/manual acceptance.
Only a disposable project copy is edited. Evidence defaults to .cache/terminal-check/run.
"""
import argparse
import json
import os
from pathlib import Path
import shlex
import shutil
import sys
import tempfile
import termios
import time

try:
    import pexpect
    import pyte
except ImportError:
    sys.exit("This check needs pexpect and pyte in its Python environment.")


class Terminal(pyte.Screen):
    def write_process_input(self, data):
        self.process.send(data)

    def save_cursor(self, *_):
        super().save_cursor()

    def restore_cursor(self, *_):
        super().restore_cursor()

    def select_graphic_rendition(self, *attrs, **kwargs):
        # Vim negotiates xterm modifyOtherKeys with CSI > ... m. Pyte 0.8.2
        # routes that private sequence here; it is not a text colour change.
        if not kwargs.get("private"):
            super().select_graphic_rendition(*attrs)


class TerminalStream(pyte.Stream):
    # Lanterna saves/restores the cursor around its terminal-size query with
    # CSI s/u. Pyte 0.8.2 recognises the equivalent ESC 7/8, but lacks these aliases.
    csi = dict(pyte.Stream.csi, s="save_cursor", u="restore_cursor")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    parser.add_argument("--vim", default=shutil.which("vim"))
    parser.add_argument("--maven-repo")
    parser.add_argument("--editor-setting", choices=["args", "visual", "editor"], default="args")
    parser.add_argument("--evidence-dir", default=".cache/terminal-check/run")
    args = parser.parse_args()
    if not args.vim:
        parser.error("Vim is required")
    repo = Path(__file__).resolve().parent.parent
    evidence = Path(args.evidence_dir).resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    results = []
    retained = []
    terminal = Terminal(120, 35)
    stream = TerminalStream(terminal)

    def frame():
        return "\n".join(terminal.display)

    def wait_until(predicate, description, timeout=15):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                stream.feed(process.read_nonblocking(65536, timeout=0.1))
            except pexpect.TIMEOUT:
                pass
            if predicate(frame()):
                return
        raise AssertionError(f"Terminal did not reach {description}\n{frame()}")

    def wait_for(text, timeout=15):
        wait_until(lambda current: text in current, repr(text), timeout)

    def check(name, condition=True):
        if not condition:
            raise AssertionError(name)
        results.append(name)
        print("PASS:", name, flush=True)

    def press(key, visible):
        process.send(key)
        wait_for(visible)

    def drain_frame():
        # A visible label may arrive before the rest of the same terminal frame.
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            try:
                stream.feed(process.read_nonblocking(65536, timeout=0.2))
            except pexpect.TIMEOUT:
                return
        raise AssertionError("The terminal keeps redrawing instead of becoming idle")

    def capture(name):
        drain_frame()
        (evidence / (name + ".txt")).write_text(frame(), encoding="utf-8")
        (evidence / (name + ".json")).write_text(json.dumps([
            [{"text": terminal.buffer[y][x].data, "fg": terminal.buffer[y][x].fg,
              "bg": terminal.buffer[y][x].bg} for x in range(terminal.columns)]
            for y in range(terminal.lines)
        ], ensure_ascii=False), encoding="utf-8")

    def foreground(fragment):
        for y, line in enumerate(terminal.display):
            x = line.find(fragment)
            if x >= 0:
                return terminal.buffer[y][x].fg
        raise AssertionError(f"Text is not visible: {fragment}")

    with tempfile.TemporaryDirectory(prefix="csb-terminal-") as temp:
        work = Path(temp)
        project = work / "project"
        shutil.copytree(repo / "examples/sample-project", project)
        file = project / "src/shop/orders.clj"
        original = b"\xef\xbb\xbf;; caf\xc3\xa9 \xf0\x9f\x98\x80\r\n" + file.read_bytes().replace(b"\n", b"\r\n")
        file.write_bytes(original)
        text = original.decode("utf-8")
        start = text.index("(defn total")
        # The draft replaces only the definition itself: blank lines between
        # forms may differ after manual edits of the bundled example, so the
        # boundary is the next form with any trailing newlines stripped.
        end = text.index("(defn- discount")
        before = text[start:end].rstrip("\r\n")
        # Test an executable whose path contains spaces, with real Vim arguments.
        editor = work / "editor with spaces"
        editor.symlink_to(Path(args.vim).resolve())
        argv = [str(editor), "-Nu", "NONE", "-n", "-i", "NONE"]
        env = dict(os.environ, TERM="xterm-256color", JAVA_CMD=args.java, LC_ALL="C.UTF-8")
        for setting in ("CSB_EDITOR_ARGS", "VISUAL", "EDITOR"):
            env.pop(setting, None)
        setting = {"args": "CSB_EDITOR_ARGS", "visual": "VISUAL", "editor": "EDITOR"}[args.editor_setting]
        env[setting] = json.dumps(argv) if args.editor_setting == "args" else shlex.join(argv)
        command = ["clojure", "-Srepro"]
        if args.maven_repo:
            command += ["-Sdeps", "{:mvn/local-repo " + json.dumps(args.maven_repo) + "}"]
        command += ["-M:tui", "--project", str(project), "--example-extension"]
        config = '{:paths ["src" "apps/tui/src"]'
        if args.maven_repo:
            config += " :mvn/local-repo " + json.dumps(args.maven_repo)
        config += "}"
        startup_failure = [
            "clojure", "-Srepro", "-Sdeps", config, "-M", "-e",
            "(require 'csb.tui-terminal) "
            '(try (csb.tui-terminal/with-input-settings '
            '(fn [] (throw (ex-info "startup failure fixture" {})))) '
            '(catch clojure.lang.ExceptionInfo e '
            '(if (= "startup failure fixture" (ex-message e)) '
            '(println "CSB_EXPECTED_STARTUP_FAILURE") (throw e))))',
        ]
        # A noninteractive shell avoids readline changing termios at its prompt.
        # Start with XON/XOFF enabled so this also detects the original Ctrl+S bug.
        shell = ('stty ixon\nbefore=$(stty -g)\n' + shlex.join(command) + '\ncode=$?\n'
                 'after=$(stty -g)\n'
                 'if [ "$before" = "$after" ]; then printf "CSB_MODES_RESTORED\\n"; '
                 'else printf "CSB_MODES_CHANGED\\nBEFORE:%s\\nAFTER:%s\\n" "$before" "$after"; fi\n'
                 'printf "CSB_EXIT:%s\\n" "$code"\n'
                 'before=$(stty -g)\n' + shlex.join(startup_failure) + '\n'
                 'after=$(stty -g)\n'
                 'if [ "$before" = "$after" ]; then printf "CSB_EXCEPTION_MODES_RESTORED\\n"; '
                 'else printf "CSB_EXCEPTION_MODES_CHANGED\\n"; fi\nexit "$code"')
        process = pexpect.spawn("/bin/sh", ["-c", shell], cwd=str(repo), env=env,
                                encoding="utf-8", timeout=15, dimensions=(35, 120))
        terminal.process = process
        process.delaybeforesend = 0.05

        def edit(draft, fail=False):
            press("e", "csb-definition-")
            path_log = work / "editor-path.txt"
            process.send(f':call writefile([expand("%:p")], "{path_log}")\r')
            deadline = time.monotonic() + 5
            while not path_log.exists() and time.monotonic() < deadline:
                try:
                    stream.feed(process.read_nonblocking(65536, timeout=0.1))
                except pexpect.TIMEOUT:
                    pass
            temporary = Path(path_log.read_text().strip())
            path_log.unlink()
            retained.append(temporary)
            check("Vim receives only the selected definition", temporary.read_text().strip().startswith("(defn total"))
            check("Vim never receives the project source path", temporary != file and project not in temporary.parents)
            process.send("ggdGi" + draft + "\x1b")
            process.send(":setlocal fileformat=unix nofixendofline noeol\r")
            process.send(":w\r:cq\r" if fail else ":wq\r")
            wait_for("Editor failed; draft retained" if fail else "Draft loaded from editor.")
            return temporary

        with (evidence / "terminal.ansi").open("w", encoding="utf-8") as transcript:
            process.logfile_read = transcript
            try:
                wait_for("2 namespaces")
                check("Production TUI opens the disposable project", "shop.orders" in frame())
                wait_for("t: theme")
                capture("theme-dark")
                check("Dark theme uses an explicit background", terminal.buffer[2][0].bg == "1c1c1c")
                # The captured frame has settled; observe a further quiet interval.
                try:
                    unexpected = process.read_nonblocking(65536, timeout=0.5)
                except pexpect.TIMEOUT:
                    unexpected = ""
                check("Idle TUI emits no terminal output", not unexpected)
                press("t", "Theme: light")
                capture("theme-light")
                check("Light theme uses an explicit background", terminal.buffer[2][0].bg == "eeeeee")
                press("t", "Theme: dark")
                check("Theme toggling preserves navigation", "shop.orders" in frame())
                # The default view lists every kind, so the alphabetical first
                # definition is discount; total is two rows below it.
                press("\t\t\x1b[B\x1b[B", "(defn total")
                check("Navigation shows one definition", "(ns shop.orders)" not in frame())
                capture("source-dark")
                check("Dark source colours reach the real terminal",
                      [foreground(token) for token in ("defn total", '"Calculate', "reduce")]
                      == ["5fd7ff", "afd787", "dadada"])
                press("t", "Theme: light")
                capture("source-light")
                check("Light source colours reach the real terminal",
                      [foreground(token) for token in ("defn total", '"Calculate', "reduce")]
                      == ["005f87", "005f00", "1c1c1c"])
                press("t", "Theme: dark")
                press("?", "e: edit definition in $EDITOR")
                press("!", "No source errors")
                press("!", "(defn total")
                press("x", "Hello from the Source World extension")
                press("v", ":world :source")
                check("Help, diagnostics and extension keys work")
                terminal.resize(lines=10, columns=40)
                process.setwinsize(10, 40)
                wait_for("Resize terminal")
                drain_frame()
                terminal.resize(lines=35, columns=120)
                process.setwinsize(35, 120)
                wait_for("(defn total")
                drain_frame()
                check("Resize recovers the source and selection")
                process.send("\x1b[6~")
                wait_until(lambda current: "(defn total" not in current, "scrolled source")
                press("\x1b[5~", "(defn total")
                check("Source scroll returns to the selected definition")

                draft = "(defn total [lines] (+ 10 (reduce + 0 (map :amount lines))))"
                temporary = edit(draft)
                check("Editor return preserves disk until Save", file.read_bytes() == original)
                check("Successful editor temporary is removed", not temporary.exists())
                press("q", "Unsaved changes")
                press("r", "Unsaved changes: Save or Discard before navigating")
                press("\x1b[A", "Unsaved changes: Save or Discard before navigating")
                check("Quit, reload and navigation retain the dirty draft", draft in frame())
                press("s", "Saved")
                expected = text.replace(before, draft).encode("utf-8")
                check("Save preserves every byte outside the definition (BOM/CRLF/Unicode)", file.read_bytes() == expected)
                (evidence / "saved.txt").write_text(frame(), encoding="utf-8")

                second = "(defn total [lines] (+ 20 (count lines)))"
                edit(second)
                check("Software flow control remains disabled after Vim",
                      not (termios.tcgetattr(process.child_fd)[0] & termios.IXON))
                press("\x13", "Saved")
                expected = expected.replace(draft.encode(), second.encode())
                check("Ctrl+S reaches Save through the real terminal", file.read_bytes() == expected)

                edit("(defn total [")
                press("s", "parse:")
                check("Invalid syntax retains draft and disk", "(defn total [" in frame() and file.read_bytes() == expected)
                (evidence / "invalid.txt").write_text(frame(), encoding="utf-8")
                press("d", "Draft discarded")
                edit(draft)
                external = expected + b"\r\n;; external change\r\n"
                file.write_bytes(external)
                press("s", "conflict:")
                check("External changes retain both disk and draft", file.read_bytes() == external and draft in frame())
                (evidence / "conflict.txt").write_text(frame(), encoding="utf-8")
                press("d", "Draft discarded")
                press("r", "Reloaded")
                press("\x1b[B\x1b[B", "(defn total")

                edit(draft)
                directory_mode = file.parent.stat().st_mode & 0o777
                try:
                    file.parent.chmod(0o555)
                    press("s", "io:")
                    check("Real permission failure retains disk and draft", file.read_bytes() == external and draft in frame())
                    (evidence / "io-error.txt").write_text(frame(), encoding="utf-8")
                finally:
                    file.parent.chmod(directory_mode)
                press("d", "Draft discarded")

                temporary = edit(draft, fail=True)
                check("Failed Vim preserves a recoverable temporary", temporary.read_text() == draft)
                check("Failed Vim preserves the project file", file.read_bytes() == external)
                wait_for("Editor exited unsuccessfully")
                check("Failed Vim shows its reason and exit code", "exit 1" in frame())
                capture("editor-error")
                press("\x1b", "(defn total")
                check("Escape returns to source without losing the retained temporary", temporary.exists())
                press("?", "e: edit definition in $EDITOR")
                check("TUI resumes input after failed Vim")
                process.send("q")
                wait_for("CSB_MODES_RESTORED")
                check("TUI restores original terminal modes")
                wait_for("CSB_EXIT:0")
                check("Clean exit returns zero to the shell")
                wait_for("CSB_EXPECTED_STARTUP_FAILURE")
                wait_for("CSB_EXCEPTION_MODES_RESTORED")
                check("Terminal settings are restored when application startup throws")
                process.expect(pexpect.EOF)
                (evidence / "results.json").write_text(json.dumps({
                    "kind": "automated-posix-pty", "checks": results,
                    "terminal": "xterm-256color / pyte", "editor": argv,
                    "editor_setting": setting,
                    "manual_acceptance": False,
                }, indent=2) + "\n", encoding="utf-8")
                (evidence / "failure.txt").unlink(missing_ok=True)
            except BaseException:
                (evidence / "failure.txt").write_text(frame(), encoding="utf-8")
                raise
            finally:
                if process.isalive():
                    process.terminate(force=True)
                process.close()
                for temporary in retained:
                    temporary.unlink(missing_ok=True)
    print(f"Passed {len(results)} process-level checks. Evidence: {evidence}")


if __name__ == "__main__":
    main()
