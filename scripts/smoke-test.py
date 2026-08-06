#!/usr/bin/env python3
"""End-to-end smoke test: drive a kotlin-lsp server over stdio and assert that the
overlay's features actually answer real LSP requests.

This is the only check that exercises the *shipped* code path — overlay classes injected
into the release jars, discovered by the server's own ServiceLoader, dispatched by the real
request router. The Gradle tests exercise the computation cores in isolation, and
compile-check.sh only type-checks; neither proves the server serves the feature.

This file is only the harness. Each feature owns its own check under
`overlay/features/<name>/smoke/check.py`, which must define:

    FIXTURE            Kotlin source, written into the workspace as <name>.kt
    check(lsp, uri)    returns a detail string; raises on failure

so that deleting a feature directory drops its smoke coverage with it — the same
PR-then-drop property the cores and unit tests have. A feature with no smoke/ directory
simply has no end-to-end coverage, which is the correct state for one that is
release-gated or non-additive and therefore cannot be served at all.

Usage: smoke-test.py <server-dir> [--expect=type-hierarchy,... | --each] [--socket] [--stock]

<server-dir> is an unpacked server with the overlay already applied (see
install-overlay.sh). --expect narrows the run to named features and fails if one of them
has no check; --each runs every discovered check in its own server process and workspace.
The default runs every feature together in one server to verify that they compose.
--socket drives the same checks over the TCP transport the VS Code extension uses, rather
than over stdio.

--stock is the negative control, and inverts the verdict: it drives the *shipped*
bin/intellij-server on a server with no overlay applied, and requires every check to FAIL.
A check that passes there is asserting something the stock server already does, so it
would keep passing if the feature were deleted -- which makes it worthless as evidence
that the feature works. Two of the original three checks were in exactly that state and
were caught by hand; this makes it a harness property instead.
"""

import importlib.util
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FEATURES_DIR = os.path.join(ROOT, "overlay", "features")

TIMEOUT = int(os.environ.get("SMOKE_TIMEOUT", "300"))
# Socket mode has to wait for the child server to boot before the port is announced.
PORT_ANNOUNCE_TIMEOUT = int(os.environ.get("SMOKE_PORT_TIMEOUT", "180"))

# A module definition for the server's JSON workspace importer (upstream's JsonWorkspaceImporter
# picks up `workspace.json` in the project root). This is what puts the fixtures into a real
# module with a source root: without it the files are opened outside any module and index-backed
# queries — the inheritor search behind typeHierarchy/subtypes — legitimately return nothing.
# Gradle/Maven importers would work too but would drag a build-tool download into CI.
WORKSPACE_JSON = {
    "modules": [{
        "name": "smoke",
        "dependencies": [{"type": "moduleSource"}, {"type": "inheritedSdk"}],
        "contentRoots": [{
            "path": "<WORKSPACE>/",
            "sourceRoots": [{"path": "<WORKSPACE>/src", "type": "java-source"}],
        }],
    }],
}


class SmokeError(Exception):
    pass


class Server:
    """Minimal LSP client. The object passed to each feature's check()."""

    def __init__(self, server_dir, root, transport="stdio", launcher_name="enhanced-server"):
        system_path = os.path.join(root, ".server-system")
        env = os.environ.copy()
        env.update({
            "XDG_CACHE_HOME": os.path.join(root, ".xdg", "cache"),
            "XDG_CONFIG_HOME": os.path.join(root, ".xdg", "config"),
            "XDG_DATA_HOME": os.path.join(root, ".xdg", "data"),
        })
        java_options = env.get("JAVA_TOOL_OPTIONS", "")
        env["JAVA_TOOL_OPTIONS"] = (
            java_options + " -Duser.home=" + os.path.join(root, ".user-home")
        ).strip()
        launcher = os.path.join(server_dir, "bin", launcher_name)
        # --socket 0 binds an ephemeral port and announces it the way the shipped launcher does;
        # this is the transport the VS Code extension uses, so exercising it end-to-end is what
        # proves the composition server is a drop-in rather than a stdio-only convenience.
        transport_args = ["--stdio"] if transport == "stdio" else ["--socket", "0"]
        self.proc = subprocess.Popen(
            [launcher] + transport_args + ["--system-path", system_path],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, cwd=root, env=env)
        self.next_id = 0
        self.capabilities = {}
        self.stderr_tail = []
        self.log_file = None  # the server announces its own log path over window/logMessage
        self.socket = None
        threading.Thread(target=self._drain_stderr, daemon=True).start()

        if transport == "stdio":
            self._in, self._out = self.proc.stdout, self.proc.stdin
        else:
            self._in = self._out = self._connect(self._announced_port())

    def _announced_port(self):
        deadline = time.time() + PORT_ANNOUNCE_TIMEOUT
        while time.time() < deadline:
            line = self.proc.stdout.readline()
            if not line:
                raise SmokeError("server exited before announcing a port\n"
                                 + "\n".join(self.stderr_tail[-30:]))
            text = line.decode("utf8", "replace").strip()
            if "Server is listening on " in text:
                return int(text.rsplit(":", 1)[1])
        raise SmokeError("no port announcement within %ds" % PORT_ANNOUNCE_TIMEOUT)

    def _connect(self, port):
        self.socket = socket.create_connection(("127.0.0.1", port), timeout=TIMEOUT)
        self.socket.settimeout(TIMEOUT)
        return self.socket.makefile("rwb")

    def _drain_stderr(self):
        for line in self.proc.stderr:
            self.stderr_tail.append(line.decode("utf8", "replace").rstrip())
            del self.stderr_tail[:-200]

    def _write(self, msg):
        body = json.dumps(msg).encode()
        self._out.write(b"Content-Length: %d\r\n\r\n" % len(body) + body)
        self._out.flush()

    def notify(self, method, params):
        self._write({"jsonrpc": "2.0", "method": method, "params": params})

    def request(self, method, params):
        self.next_id += 1
        self._write({"jsonrpc": "2.0", "id": self.next_id, "method": method, "params": params})
        return self._await(self.next_id)

    def poll(self, seconds, interval=2):
        """Yield until `seconds` have elapsed, for index-backed queries that need retrying."""
        deadline = time.time() + seconds
        first = True
        while time.time() < deadline:
            if not first:
                time.sleep(interval)
            first = False
            yield

    def _read(self):
        headers = {}
        while True:
            line = self._in.readline()
            if not line:
                raise SmokeError("server closed the connection\n"
                                 + "\n".join(self.stderr_tail[-30:]))
            line = line.decode().strip()
            if not line:
                break
            key, value = line.split(":", 1)
            headers[key.strip().lower()] = value.strip()
        n = int(headers["content-length"])
        buf = b""
        while len(buf) < n:
            chunk = self._in.read(n - len(buf))
            if not chunk:
                raise SmokeError("truncated message from server")
            buf += chunk
        return json.loads(buf)

    def _await(self, want):
        deadline = time.time() + TIMEOUT
        while time.time() < deadline:
            msg = self._read()
            if os.environ.get("SMOKE_TRACE"):
                print("[smoke] <- %s" % json.dumps(msg, sort_keys=True), file=sys.stderr)
            if msg.get("id") == want and ("result" in msg or "error" in msg):
                if "error" in msg:
                    raise SmokeError("%s failed: %s" % (want, msg["error"]))
                return msg["result"]
            if msg.get("method") == "window/logMessage":
                text = (msg.get("params") or {}).get("message", "")
                if text.startswith("Log file: "):
                    self.log_file = text[len("Log file: "):].strip()
            # Server-to-client request: provide the response shape required by configuration;
            # reply null to other methods so the server never blocks on us.
            if "id" in msg and "method" in msg:
                if msg["method"] == "workspace/configuration":
                    items = (msg.get("params") or {}).get("items", [])
                    result = [{} for _ in items]
                else:
                    result = None
                self._write({"jsonrpc": "2.0", "id": msg["id"], "result": result})
        raise SmokeError("timed out after %ds waiting for response %s" % (TIMEOUT, want))

    @staticmethod
    def apply_edits(text, edits):
        """Apply LSP TextEdits to `text` the way a conforming client would.

        Ranges are all computed against the original document and must not overlap, so applying
        them back-to-front is safe and order-independent. Shared here rather than copied into each
        feature so that a check asserting on the *result* of an edit costs nothing to write --
        asserting an edit merely exists is what lets a corrupt TextEdit pass.
        """
        def offset(position):
            lines = text.splitlines(keepends=True)
            return sum(len(line) for line in lines[:position["line"]]) + position["character"]

        positioned = [
            (offset(e["range"]["start"]), offset(e["range"]["end"]), e["newText"]) for e in edits
        ]
        out = text
        for start, end, replacement in sorted(positioned, reverse=True):
            out = out[:start] + replacement + out[end:]
        return out

    def shutdown(self):
        try:
            self.request("shutdown", None)
            self.notify("exit", None)
            self.proc.wait(timeout=20)
        except Exception:
            self.proc.kill()


def _brief(err, limit=90):
    """First line of a failure reason, trimmed -- the stock run prints one per feature."""
    text = str(err).strip().splitlines()[0] if str(err).strip() else type(err).__name__
    return text if len(text) <= limit else text[:limit - 3] + "..."


def discover_features():
    """Every overlay/features/<name>/smoke/check.py, as (name, module) pairs."""
    found = []
    for name in sorted(os.listdir(FEATURES_DIR)):
        path = os.path.join(FEATURES_DIR, name, "smoke", "check.py")
        if not os.path.isfile(path):
            continue
        spec = importlib.util.spec_from_file_location("smoke_%s" % name.replace("-", "_"), path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        for attr in ("FIXTURE", "check"):
            if not hasattr(module, attr):
                raise SystemExit("%s defines no %s" % (path, attr))
        found.append((name, module))
    return found


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    server_dir = os.path.abspath(sys.argv[1])

    features = discover_features()
    run_each = False
    stock = False
    transport = "stdio"
    for arg in sys.argv[2:]:
        if arg.startswith("--expect="):
            wanted = [f for f in arg.split("=", 1)[1].split(",") if f]
            missing = [f for f in wanted if f not in [n for n, _ in features]]
            if missing:
                sys.exit("no smoke check for: %s (found: %s)"
                         % (", ".join(missing), ", ".join(n for n, _ in features) or "none"))
            features = [(n, m) for n, m in features if n in wanted]
        elif arg == "--each":
            run_each = True
        elif arg == "--socket":
            transport = "socket"
        elif arg == "--stock":
            stock = True
        else:
            sys.exit("unknown argument: %s\n\n%s" % (arg, __doc__))
    if not features:
        sys.exit("no feature defines overlay/features/<name>/smoke/check.py")
    if run_each and any(arg.startswith("--expect=") for arg in sys.argv[2:]):
        sys.exit("--each and --expect cannot be used together")

    # The negative control drives the shipped launcher on a server with no overlay applied.
    # Anything the overlay adds -- injected providers and the composition server's repairs alike --
    # is absent there, so every check must fail. One that passes is not proving what it claims.
    launcher_name = "intellij-server" if stock else "enhanced-server"
    launcher = os.path.join(server_dir, "bin", launcher_name)
    if not os.path.isfile(launcher):
        sys.exit("error: %s has no bin/%s" % (server_dir, launcher_name))
    if stock and transport == "socket":
        sys.exit("--stock and --socket cannot be used together: the TCP transport is the "
                 "composition server's, and a stock server has no composition server")

    if run_each:
        failures = []
        print("[smoke-each] server:   %s" % server_dir, flush=True)
        print("[smoke-each] features: %s" % ", ".join(name for name, _ in features), flush=True)
        for name, _ in features:
            print("\n[smoke-each] === %s ===" % name, flush=True)
            child_env = os.environ.copy()
            child_env["SMOKE_WORKDIR"] = tempfile.mkdtemp(
                prefix="kotlin-lsp-smoke-%s-" % name,
                dir=child_env.get("SMOKE_WORKDIR"),
            )
            result = subprocess.run(
                [sys.executable, os.path.abspath(__file__), server_dir, "--expect=" + name]
                + (["--socket"] if transport == "socket" else [])
                + (["--stock"] if stock else []),
                env=child_env,
            )
            if result.returncode:
                failures.append(name)
        if failures:
            print("\n[smoke-each] FAILED: %s" % ", ".join(failures))
            return 1
        if stock:
            print("\n[smoke-each] PASS -- %d check(s) correctly failed in isolation"
                  % len(features))
        else:
            print("\n[smoke-each] PASS -- %d feature(s) served independently" % len(features))
        return 0

    # One workspace holding every feature's fixture as its own file, so a single server start
    # covers them all. Each check only ever sees the URI of its own fixture.
    workdir = os.environ.get("SMOKE_WORKDIR")
    root = (
        os.path.join(workdir, "kotlin-lsp-smoke")
        if workdir
        else tempfile.mkdtemp(prefix="kotlin-lsp-smoke-")
    )
    src = os.path.join(root, "src")
    os.makedirs(src, exist_ok=True)
    with open(os.path.join(root, "workspace.json"), "w") as fh:
        json.dump(WORKSPACE_JSON, fh, indent=2)
    uris = {}
    for name, module in features:
        path = os.path.join(src, "%s.kt" % name.replace("-", "_"))
        with open(path, "w") as fh:
            fh.write(module.FIXTURE)
        uris[name] = "file://" + path

        # A feature may ship extra workspace files under smoke/project/, copied in with their
        # relative paths preserved. FIXTURE alone is a single Kotlin file, which cannot express
        # what several features actually need to be tested on -- a Java subclass of a Kotlin type,
        # a cross-file reference, a second package. Names must not collide with another feature's,
        # since the combined run shares one workspace; prefix them with the feature name.
        project = os.path.join(FEATURES_DIR, name, "smoke", "project")
        if os.path.isdir(project):
            shutil.copytree(project, root, dirs_exist_ok=True)

    print("[smoke] server:    %s" % server_dir)
    print("[smoke] workspace: %s" % root)
    print("[smoke] features:  %s" % ", ".join(name for name, _ in features))

    root_uri = "file://" + root
    lsp = Server(server_dir, root, transport, launcher_name)
    lsp.root = root
    lsp.root_uri = root_uri
    started = time.time()
    failures = []
    try:
        initialize = lsp.request("initialize", {
            # The test owns the server process and always shuts it down explicitly. Using null
            # also avoids coupling the server's parent-process monitor to CI/container PID
            # namespaces, where the Python PID may not be visible to the bundled JVM.
            "processId": None,
            "rootUri": root_uri,
            "workspaceFolders": [{"uri": root_uri, "name": "smoke"}],
            "capabilities": {
                "textDocument": {
                    "inlayHint": {"dynamicRegistration": True, "resolveSupport": {"properties": []}},
                    "typeHierarchy": {"dynamicRegistration": True},
                    "foldingRange": {"dynamicRegistration": True},
                    "codeAction": {"resolveSupport": {"properties": ["edit"]}},
                    "synchronization": {"didSave": True},
                },
                "workspace": {"workspaceFolders": True},
            },
        })
        lsp.capabilities = initialize.get("capabilities", {})
        lsp.notify("initialized", {})
        for name, module in features:
            lsp.notify("textDocument/didOpen", {"textDocument": {
                "uri": uris[name], "languageId": "kotlin", "version": 1,
                "text": module.FIXTURE}})

        # Provider capability flags alone prove nothing: stock 262.8190.0 advertises
        # typeHierarchyProvider and then answers nothing. Each feature check exercises its real
        # request; proxy-only repairs can additionally inspect lsp.capabilities.
        print("[smoke] initialized in %.1fs" % (time.time() - started))

        for name, module in features:
            try:
                detail = module.check(lsp, uris[name])
            except (AssertionError, SmokeError) as err:
                if stock:
                    # Declined by an unmodified server, which is the whole point of this run.
                    print("[stock]   OK   %-16s correctly unavailable: %s" % (name, _brief(err)))
                else:
                    print("[smoke]   FAIL %-16s %s" % (name, err))
                    failures.append(name)
            except Exception as err:  # noqa: BLE001 - a check crash is a check bug either way
                print("[%s]   FAIL %-16s check raised %s: %s"
                      % ("stock" if stock else "smoke", name, type(err).__name__, err))
                failures.append(name)
            else:
                if stock:
                    print("[stock]   FAIL %-16s passed against an unmodified server: %s"
                          % (name, detail))
                    failures.append(name)
                else:
                    print("[smoke]   PASS %-16s %s" % (name, detail))
    finally:
        lsp.shutdown()

    if stock:
        if failures:
            print("\n[stock] FAILED -- these checks do not discriminate: %s" % ", ".join(failures))
            print("[stock] A check that passes without the overlay is asserting something the "
                  "shipped server already does.")
            return 1
        print("\n[stock] PASS -- all %d check(s) correctly fail without the overlay" % len(features))
        return 0

    if failures:
        print("\n[smoke] FAILED: %s" % ", ".join(failures))
        # CI has no other window into the server, so bring its log to the console.
        if lsp.log_file and os.path.isfile(lsp.log_file):
            print("\n[smoke] tail of %s:" % lsp.log_file)
            with open(lsp.log_file, errors="replace") as fh:
                for line in fh.readlines()[-60:]:
                    print("  " + line.rstrip())
        return 1
    print("\n[smoke] PASS -- %d feature(s) served end-to-end" % len(features))
    return 0


if __name__ == "__main__":
    sys.exit(main())
