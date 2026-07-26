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

Usage: smoke-test.py <server-dir> [--expect type-hierarchy,region-folding,...]

<server-dir> is an unpacked server with the overlay already applied (see
install-overlay.sh). --expect narrows the run to named features and fails if one of them
has no check; the default is every feature that ships one.
"""

import importlib.util
import json
import os
import subprocess
import sys
import threading
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FEATURES_DIR = os.path.join(ROOT, "overlay", "features")

TIMEOUT = int(os.environ.get("SMOKE_TIMEOUT", "300"))

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

    def __init__(self, server_dir, root):
        self.proc = subprocess.Popen(
            [os.path.join(server_dir, "bin", "intellij-server"), "--stdio"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, cwd=root)
        self.next_id = 0
        self.stderr_tail = []
        self.log_file = None  # the server announces its own log path over window/logMessage
        threading.Thread(target=self._drain_stderr, daemon=True).start()

    def _drain_stderr(self):
        for line in self.proc.stderr:
            self.stderr_tail.append(line.decode("utf8", "replace").rstrip())
            del self.stderr_tail[:-200]

    def _write(self, msg):
        body = json.dumps(msg).encode()
        self.proc.stdin.write(b"Content-Length: %d\r\n\r\n" % len(body) + body)
        self.proc.stdin.flush()

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
            line = self.proc.stdout.readline()
            if not line:
                raise SmokeError("server closed stdout\n" + "\n".join(self.stderr_tail[-30:]))
            line = line.decode().strip()
            if not line:
                break
            key, value = line.split(":", 1)
            headers[key.strip().lower()] = value.strip()
        n = int(headers["content-length"])
        buf = b""
        while len(buf) < n:
            chunk = self.proc.stdout.read(n - len(buf))
            if not chunk:
                raise SmokeError("truncated message from server")
            buf += chunk
        return json.loads(buf)

    def _await(self, want):
        deadline = time.time() + TIMEOUT
        while time.time() < deadline:
            msg = self._read()
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

    def shutdown(self):
        try:
            self.request("shutdown", None)
            self.notify("exit", None)
            self.proc.wait(timeout=20)
        except Exception:
            self.proc.kill()


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
    for arg in sys.argv[2:]:
        if arg.startswith("--expect="):
            wanted = [f for f in arg.split("=", 1)[1].split(",") if f]
            missing = [f for f in wanted if f not in [n for n, _ in features]]
            if missing:
                sys.exit("no smoke check for: %s (found: %s)"
                         % (", ".join(missing), ", ".join(n for n, _ in features) or "none"))
            features = [(n, m) for n, m in features if n in wanted]
    if not features:
        sys.exit("no feature defines overlay/features/<name>/smoke/check.py")

    launcher = os.path.join(server_dir, "bin", "intellij-server")
    if not os.path.isfile(launcher):
        sys.exit("error: %s is not an unpacked kotlin-lsp server" % server_dir)

    # One workspace holding every feature's fixture as its own file, so a single server start
    # covers them all. Each check only ever sees the URI of its own fixture.
    root = os.path.join(os.environ.get("SMOKE_WORKDIR", "/tmp"), "kotlin-lsp-smoke")
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

    print("[smoke] server:    %s" % server_dir)
    print("[smoke] workspace: %s" % root)
    print("[smoke] features:  %s" % ", ".join(name for name, _ in features))

    root_uri = "file://" + root
    lsp = Server(server_dir, root)
    started = time.time()
    failures = []
    try:
        lsp.request("initialize", {
            "processId": os.getpid(),
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
        lsp.notify("initialized", {})
        for name, module in features:
            lsp.notify("textDocument/didOpen", {"textDocument": {
                "uri": uris[name], "languageId": "kotlin", "version": 1,
                "text": module.FIXTURE}})

        # Deliberately no assertion on the advertised capabilities: stock 262.8190.0 already
        # advertises typeHierarchyProvider (and then answers nothing), so capabilities prove
        # nothing about the overlay. Only the request/response checks below do.
        print("[smoke] initialized in %.1fs" % (time.time() - started))

        for name, module in features:
            try:
                print("[smoke]   PASS %-16s %s" % (name, module.check(lsp, uris[name])))
            except (AssertionError, SmokeError) as err:
                print("[smoke]   FAIL %-16s %s" % (name, err))
                failures.append(name)
    finally:
        lsp.shutdown()

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
