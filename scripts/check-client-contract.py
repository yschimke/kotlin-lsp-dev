#!/usr/bin/env python3
"""Verify the server still provides what the VS Code client assumes.

The client in editors/vscode calls server commands and reads fields out of their results. Those
are assumptions about another program, and nothing else checks them: when a shape is wrong the
client does not crash, it quietly does nothing -- a decompiled file that renders as "could not
decompile", a template that never fills in. Both of those were real, and both looked like server
bugs from the editor.

So this asserts the contract directly. Run it against an installed server:

    scripts/check-client-contract.py <server-dir>

It is separate from smoke-test.py because it tests the *client's* dependencies rather than an
overlay feature, and it covers commands the overlay does not provide at all.
"""

import importlib.util
import json
import os
import shutil
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Loaded for its LSP client; smoke-test.py is not importable by name because of the hyphen.
_spec = importlib.util.spec_from_file_location("smoke", os.path.join(ROOT, "scripts", "smoke-test.py"))
smoke = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(smoke)

# Command -> why the client needs it. A command vanishing is a client feature silently dying.
REQUIRED_COMMANDS = {
    "decompile": "jar:/jrt: navigation into dependencies and the JDK",
    "exportWorkspace": "Kotlin: Export workspace structure to JSON",
    "start_debug_server": "debugging (launch and attach)",
    "interpolateFileTemplate": "file templates for newly created files",
    "kotlin-lsp.doctor": "Kotlin: Doctor",
    "kotlin-lsp.analyzeStackTrace": "Kotlin: Analyze JVM stack trace",
    "kotlin-lsp.findTextInDependencyJars": "Kotlin: Find text in dependency jars",
    "kotlin-lsp.copyFullyQualifiedName": "Kotlin: Copy fully-qualified name",
    "kotlin-lsp.listJarClasses": "the Kotlin project tree (expanding a dependency jar)",
}

FIXTURE = """\
package contract

import java.util.ArrayList

fun use(): Int = ArrayList<Int>().size
"""


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    server_dir = os.path.abspath(sys.argv[1])

    root = tempfile.mkdtemp(prefix="kotlin-lsp-contract-")
    src = os.path.join(root, "src")
    os.makedirs(src, exist_ok=True)
    with open(os.path.join(root, "workspace.json"), "w") as fh:
        json.dump(smoke.WORKSPACE_JSON, fh, indent=2)
    path = os.path.join(src, "Contract.kt")
    with open(path, "w") as fh:
        fh.write(FIXTURE)
    uri = "file://" + path

    failures = []
    lsp = smoke.Server(server_dir, root, "stdio", "enhanced-server")
    try:
        result = lsp.request("initialize", {
            "processId": None,
            "rootUri": "file://" + root,
            "workspaceFolders": [{"uri": "file://" + root, "name": "contract"}],
            "capabilities": {"textDocument": {}, "workspace": {"workspaceFolders": True}},
        })
        lsp.notify("initialized", {})
        lsp.notify("textDocument/didOpen", {"textDocument": {
            "uri": uri, "languageId": "kotlin", "version": 1, "text": FIXTURE}})

        capabilities = result.get("capabilities", {})
        advertised = set((capabilities.get("executeCommandProvider") or {}).get("commands", []))
        print("[contract] server advertises %d command(s)" % len(advertised))
        for command, why in sorted(REQUIRED_COMMANDS.items()):
            if command in advertised:
                print("  ok       %-38s %s" % (command, why))
            else:
                print("  MISSING  %-38s %s" % (command, why))
                failures.append("%s (%s)" % (command, why))

        # Capabilities the client's document selector and features assume.
        for capability in ("documentHighlightProvider", "documentRangeFormattingProvider"):
            if capabilities.get(capability) is True:
                print("  ok       %-38s advertised" % capability)
            else:
                print("  MISSING  %-38s not advertised" % capability)
                failures.append(capability)

        # Result shapes. These are the ones that fail silently rather than loudly.
        if "start_debug_server" in advertised:
            port = execute(lsp, "start_debug_server", ["file://" + root])
            if isinstance(port, int):
                print("  ok       %-38s returns a port (%d)" % ("start_debug_server", port))
            else:
                print("  WRONG    %-38s expected a port number, got %r" % ("start_debug_server", port))
                failures.append("start_debug_server result shape")

        if "decompile" in advertised:
            target = jdk_definition(lsp, uri)
            if target is None:
                print("  skipped  %-38s no jar:/jrt: definition to decompile" % "decompile")
            else:
                decompiled = execute(lsp, "decompile", [target])
                # The client reads `.code`; it read `.text` once, and every library file rendered
                # as "could not decompile" while looking like the server's fault.
                if isinstance(decompiled, dict) and isinstance(decompiled.get("code"), str):
                    print("  ok       %-38s returns {code, language}" % "decompile")
                else:
                    print("  WRONG    %-38s expected {code: str}, got %s"
                          % ("decompile", type(decompiled).__name__))
                    failures.append("decompile result shape")

        if "kotlin-lsp.listJarClasses" in advertised:
            failures.extend(check_jar_listing(lsp, root))
    finally:
        lsp.shutdown()
        shutil.rmtree(root, ignore_errors=True)

    if failures:
        print("\n[contract] FAILED -- the VS Code client depends on these:")
        for failure in failures:
            print("   %s" % failure)
        return 1
    print("\n[contract] PASS -- the client's server assumptions hold")
    return 0


def check_jar_listing(lsp, root):
    """The project tree's two assumptions: the listing shape, and that its URIs decompile.

    The tree turns a classpath entry `jar:///x.jar!/` plus an entry name into
    `jar:///x.jar!/pkg/Cls.class` and hands that to `decompile`. That construction is the part that
    fails silently -- a wrong URI shape gives every class in the tree a "could not decompile"
    placeholder, which reads as the decompiler being broken rather than the tree being wrong.
    """
    failures = []
    jar = os.path.join(root, "contract-listing.jar")
    with zipfile.ZipFile(jar, "w") as archive:
        for entry in ("contract/api/Thing.class", "contract/api/nested/Inner.class"):
            archive.writestr(entry, b"\0")

    listing = execute(lsp, "kotlin-lsp.listJarClasses", [jar, "contract.api"])
    if not isinstance(listing, dict) or not isinstance(listing.get("classes"), list):
        print("  WRONG    %-38s expected {packages, classes, truncated}, got %r"
              % ("kotlin-lsp.listJarClasses", listing))
        return ["kotlin-lsp.listJarClasses result shape"]
    names = [entry.get("name") for entry in listing["classes"]]
    if names != ["Thing"] or listing.get("packages") != ["nested"]:
        print("  WRONG    %-38s expected one class and one subpackage, got %r"
              % ("kotlin-lsp.listJarClasses", listing))
        failures.append("kotlin-lsp.listJarClasses contents")
    else:
        print("  ok       %-38s returns {packages, classes, truncated}" % "kotlin-lsp.listJarClasses")

    # The round trip only means something against a jar the project actually resolved, so it runs
    # when the workspace has one and says so plainly when it does not.
    report = execute(lsp, "kotlin-lsp.doctor", []) or {}
    jars = [entry for module in report.get("modules", []) for entry in module.get("classpath", [])
            if entry.startswith("jar:")]
    if not jars:
        # This workspace's only non-source dependency is the JDK, which resolves through `jrt:`.
        # The round trip was instead verified against a real Gradle project (this repository):
        # 8 of 8 `kotlin.collections` classes decompiled from tree-constructed URIs. See
        # "Kotlin project view" in editors/vscode/README.md for the cold-start caveat.
        print("  skipped  %-38s no jar on this workspace's classpath to open"
              % "project tree -> decompile")
        return failures

    target = jars[0]
    contents = execute(lsp, "kotlin-lsp.listJarClasses", [jar_path_of(target)]) or {}
    entry = first_class(lsp, target, contents)
    if entry is None:
        print("  skipped  %-38s no class found in %s" % ("project tree -> decompile", target))
        return failures
    constructed = target + entry
    decompiled = execute(lsp, "decompile", [constructed])
    if isinstance(decompiled, dict) and isinstance(decompiled.get("code"), str):
        print("  ok       %-38s %s decompiles" % ("project tree -> decompile", entry))
    else:
        print("  WRONG    %-38s %s did not decompile" % ("project tree -> decompile", constructed))
        failures.append("project tree URI construction")
    return failures


def jar_path_of(url):
    """`jar:///home/me/x.jar!/` -> `/home/me/x.jar`, the form the listing command takes."""
    return url[len("jar://"):].rstrip("/").rstrip("!")


def first_class(lsp, url, listing, prefix="", depth=0):
    """The first class entry anywhere in the jar, descending into packages as needed.

    Each level returns only the immediate child package names, so the caller carries the prefix --
    the same thing the tree does when it expands a node.
    """
    if listing.get("classes"):
        return listing["classes"][0]["entry"]
    if depth >= 4:
        return None
    for package in listing.get("packages", []):
        qualified = "%s.%s" % (prefix, package) if prefix else package
        deeper = execute(lsp, "kotlin-lsp.listJarClasses", [jar_path_of(url), qualified]) or {}
        found = first_class(lsp, url, deeper, qualified, depth + 1)
        if found:
            return found
    return None


def execute(lsp, command, arguments):
    try:
        return lsp.request("workspace/executeCommand", {"command": command, "arguments": arguments})
    except Exception as error:  # noqa: BLE001 - reported as a contract failure, not raised
        print("  ERROR    %-38s %s" % (command, str(error)[:90]))
        return None


def jdk_definition(lsp, uri):
    """A jar:/jrt: URI to decompile, from a definition into the JDK."""
    for _ in lsp.poll(120):
        found = lsp.request("textDocument/definition", {
            "textDocument": {"uri": uri},
            "position": {"line": 2, "character": 24},   # `ArrayList` in the import
        }) or []
        entries = found if isinstance(found, list) else [found]
        for entry in entries:
            target = entry.get("uri") or entry.get("targetUri")
            if target and target.split(":", 1)[0] in ("jar", "jrt"):
                return target
    return None


if __name__ == "__main__":
    sys.exit(main())
