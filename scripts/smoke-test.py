#!/usr/bin/env python3
"""End-to-end smoke test: drive a kotlin-lsp server over stdio and assert that the
overlay's features actually answer real LSP requests.

This is the only check that exercises the *shipped* code path — overlay classes injected
into the release jars, discovered by the server's own ServiceLoader, dispatched by the real
request router. The Gradle tests exercise the computation cores in isolation, and
compile-check.sh only type-checks; neither proves the server serves the feature.

Usage: smoke-test.py <server-dir> [--expect type-hierarchy,region-folding,...]

<server-dir> is an unpacked server with the overlay already applied (see
install-overlay.sh). --expect names the features that must be runnable on this release;
each one that is not answered correctly fails the run. Defaults to every feature that
build-server.sh compiles as runnable on the pinned release.
"""

import json
import os
import subprocess
import sys
import threading
import time

TIMEOUT = int(os.environ.get("SMOKE_TIMEOUT", "300"))
# How long index-backed queries (inheritor search) may take to become answerable.
INDEX_TIMEOUT = int(os.environ.get("SMOKE_INDEX_TIMEOUT", "120"))

# A module definition for the server's JSON workspace importer (upstream's JsonWorkspaceImporter
# picks up `workspace.json` in the project root). This is what puts the fixture into a real
# module with a source root: without it the file is opened outside any module and index-backed
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

FIXTURE = """\
package sample

interface Shape {
    fun area(): Double
}

open class Base : Shape {
    override fun area(): Double {
        return 1.0
    }
}

class Circle(val r: Double) : Base() {
    //region geometry
    override fun area(): Double {
        return 3.14 * r * r
    }
    //endregion
}
"""

# 0-based line numbers into FIXTURE, referenced by the checks below.
LINE_SHAPE = 2       # interface Shape
LINE_BASE = 6        # open class Base : Shape
LINE_REGION = 13     # //region geometry
LINE_ENDREGION = 17  # //endregion
LINE_CIRCLE_AREA = 14  # override fun area() inside Circle


class Server:
    def __init__(self, server_dir, root):
        self.proc = subprocess.Popen(
            [os.path.join(server_dir, "bin", "intellij-server"), "--stdio"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            cwd=root)
        self.next_id = 0
        self.stderr_tail = []
        self.log_file = None  # the server announces its own log path over window/logMessage
        threading.Thread(target=self._drain_stderr, daemon=True).start()

    def _drain_stderr(self):
        for line in self.proc.stderr:
            text = line.decode("utf8", "replace").rstrip()
            self.stderr_tail.append(text)
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
            # Server-to-client request: reply null so the server never blocks on us.
            if "id" in msg and "method" in msg:
                self._write({"jsonrpc": "2.0", "id": msg["id"], "result": None})
        raise SmokeError("timed out after %ds waiting for response %s" % (TIMEOUT, want))

    def shutdown(self):
        try:
            self.request("shutdown", None)
            self.notify("exit", None)
            self.proc.wait(timeout=20)
        except Exception:
            self.proc.kill()


class SmokeError(Exception):
    pass


def check_type_hierarchy(lsp, uri):
    """Overlay feature: textDocument/prepareTypeHierarchy + supertypes/subtypes."""
    items = lsp.request("textDocument/prepareTypeHierarchy", {
        "textDocument": {"uri": uri},
        "position": {"line": LINE_BASE, "character": 11},  # on `Base`
    })
    if not items:
        raise SmokeError("prepareTypeHierarchy on `Base` returned nothing")
    base = items[0]
    if base.get("name") != "Base":
        raise SmokeError("prepareTypeHierarchy resolved %r, expected Base" % base.get("name"))

    supertypes = lsp.request("typeHierarchy/supertypes", {"item": base}) or []
    names = sorted(item["name"] for item in supertypes)
    if "Shape" not in names:
        raise SmokeError("supertypes of Base = %s, expected to contain Shape" % names)

    # subtypes runs an inheritor search over the index, which is still being built for a
    # freshly opened workspace — poll rather than race it.
    names = []
    deadline = time.time() + INDEX_TIMEOUT
    while time.time() < deadline:
        subtypes = lsp.request("typeHierarchy/subtypes", {"item": base}) or []
        names = sorted(item["name"] for item in subtypes)
        if "Circle" in names:
            break
        time.sleep(2)
    if "Circle" not in names:
        raise SmokeError("subtypes of Base = %s after %ds, expected to contain Circle"
                         % (names, INDEX_TIMEOUT))
    return "prepare→Base, supertypes⊇[Shape], subtypes⊇[Circle]"


def check_region_folding(lsp, uri):
    """Overlay feature: //region…//endregion ranges, merged with the built-in ones.

    The kind matters. Stock 262.8190.0 already returns a range over the same two lines with
    kind "comment" (it folds the comment block), so asserting only on the line numbers would
    pass against an un-patched server. The overlay's contribution is the "region" kind.
    """
    ranges = lsp.request("textDocument/foldingRange", {"textDocument": {"uri": uri}}) or []
    region = [r for r in ranges
              if r.get("startLine") == LINE_REGION
              and r.get("endLine") == LINE_ENDREGION
              and r.get("kind") == "region"]
    if not region:
        raise SmokeError(
            "no region-kind fold at lines %d-%d; got %s"
            % (LINE_REGION, LINE_ENDREGION,
               [(r.get("startLine"), r.get("endLine"), r.get("kind")) for r in ranges]))
    # The built-in provider must still be answering — additivity is the whole premise.
    others = [r for r in ranges if r not in region]
    if not others:
        raise SmokeError("only the region fold came back; the built-in folds went missing")
    return "region fold %d-%d + %d built-in fold(s)" % (LINE_REGION, LINE_ENDREGION, len(others))


def check_expression_body(lsp, uri):
    """Overlay feature: the 'Convert to expression body' code action."""
    actions = lsp.request("textDocument/codeAction", {
        "textDocument": {"uri": uri},
        "range": {"start": {"line": LINE_CIRCLE_AREA, "character": 17},
                  "end": {"line": LINE_CIRCLE_AREA, "character": 17}},
        "context": {"diagnostics": []},
    }) or []
    titles = [a.get("title", "") for a in actions]
    match = [a for a in actions if "expression body" in a.get("title", "").lower()]
    if not match:
        raise SmokeError("no 'expression body' code action at the body of Circle.area(); "
                         "got %s" % titles)
    action = match[0]
    edit = action.get("edit")
    if edit is None and action.get("data") is not None:
        action = lsp.request("codeAction/resolve", action)
        edit = action.get("edit")
    changes = (edit or {}).get("changes", {}) or (edit or {}).get("documentChanges", [])
    if not changes:
        raise SmokeError("'%s' resolved to an empty edit" % action.get("title"))
    return "action %r with a non-empty edit" % action.get("title")


CHECKS = {
    "type-hierarchy": check_type_hierarchy,
    "region-folding": check_region_folding,
    "expression-body": check_expression_body,
}


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    server_dir = os.path.abspath(sys.argv[1])
    expect = list(CHECKS)
    for arg in sys.argv[2:]:
        if arg.startswith("--expect="):
            expect = [f for f in arg.split("=", 1)[1].split(",") if f]
    unknown = [f for f in expect if f not in CHECKS]
    if unknown:
        sys.exit("unknown feature(s) in --expect: %s (known: %s)"
                 % (", ".join(unknown), ", ".join(CHECKS)))

    launcher = os.path.join(server_dir, "bin", "intellij-server")
    if not os.path.isfile(launcher):
        sys.exit("error: %s is not an unpacked kotlin-lsp server" % server_dir)

    root = os.path.join(os.environ.get("SMOKE_WORKDIR", "/tmp"), "kotlin-lsp-smoke")
    os.makedirs(os.path.join(root, "src"), exist_ok=True)
    source = os.path.join(root, "src", "Sample.kt")
    with open(source, "w") as fh:
        fh.write(FIXTURE)
    with open(os.path.join(root, "workspace.json"), "w") as fh:
        json.dump(WORKSPACE_JSON, fh, indent=2)
    root_uri = "file://" + root
    uri = "file://" + source

    print("[smoke] server:  %s" % server_dir)
    print("[smoke] fixture: %s" % source)
    lsp = Server(server_dir, root)
    started = time.time()
    try:
        lsp.request("initialize", {
            "processId": os.getpid(),
            "rootUri": root_uri,
            "workspaceFolders": [{"uri": root_uri, "name": "smoke"}],
            "capabilities": {
                "textDocument": {
                    "typeHierarchy": {"dynamicRegistration": True},
                    "foldingRange": {"dynamicRegistration": True},
                    "codeAction": {"resolveSupport": {"properties": ["edit"]}},
                    "synchronization": {"didSave": True},
                },
                "workspace": {"workspaceFolders": True},
            },
        })
        lsp.notify("initialized", {})
        lsp.notify("textDocument/didOpen", {"textDocument": {
            "uri": uri, "languageId": "kotlin", "version": 1, "text": FIXTURE}})

        # Deliberately no assertion on the advertised capabilities: stock 262.8190.0 already
        # advertises typeHierarchyProvider (and then answers nothing), so capabilities prove
        # nothing about the overlay. Only the request/response checks below do.
        print("[smoke] initialized in %.1fs" % (time.time() - started))

        failures = []
        for feature in expect:
            try:
                detail = CHECKS[feature](lsp, uri)
                print("[smoke]   PASS %-16s %s" % (feature, detail))
            except SmokeError as err:
                print("[smoke]   FAIL %-16s %s" % (feature, err))
                failures.append(feature)
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
    print("\n[smoke] PASS -- %d feature(s) served end-to-end" % len(expect))
    return 0


if __name__ == "__main__":
    sys.exit(main())
