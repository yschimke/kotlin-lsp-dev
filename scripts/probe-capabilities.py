#!/usr/bin/env python3
"""Print the initialize capabilities a server advertises.

The initialize result is the real gate on what any conformant client will ever request, and it
is almost entirely hardcoded in the shipped server. Reading it directly is the fastest way to
tell whether a new release unblocks a release-gated feature -- far more reliable than
disassembling the obfuscated launcher, which is re-obfuscated on every build.

Usage: probe-capabilities.py <server-dir> [--launcher bin/intellij-server]
"""

import importlib.util
import json
import os
import shutil
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The LSP client, process-group teardown and cache isolation all live in the smoke harness. This
# used to carry its own copies, which drifted: it killed only the launcher -- orphaning the
# `intellij-server` child it had spawned -- and never removed its temp directory, so repeated
# probing leaked a server process and a few hundred megabytes each time. Sharing the harness means
# one implementation to keep correct. smoke-test.py is not importable by name (hyphen).
_spec = importlib.util.spec_from_file_location("smoke", os.path.join(ROOT, "scripts", "smoke-test.py"))
smoke = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(smoke)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    server_dir = os.path.abspath(sys.argv[1])
    launcher_rel = "bin/intellij-server"
    if "--launcher" in sys.argv:
        launcher_rel = sys.argv[sys.argv.index("--launcher") + 1]
    launcher = os.path.join(server_dir, launcher_rel)
    if not os.path.isfile(launcher):
        sys.exit("not a server directory: %s" % server_dir)

    root = tempfile.mkdtemp(prefix="kotlin-lsp-probe-")
    os.makedirs(os.path.join(root, "src"), exist_ok=True)
    with open(os.path.join(root, "workspace.json"), "w") as fh:
        json.dump({"name": "probe", "modules": [
            {"name": "probe", "contentRoots": [{"path": "src", "type": "source"}]}]}, fh)

    # The harness gives the probe its own caches and home. Without that it contends with any server
    # the editor already has running against the shared XDG directories, and simply never answers.
    lsp = smoke.Server(server_dir, root, "stdio", os.path.basename(launcher_rel))
    try:
        result = lsp.request("initialize", {
            "processId": None,
            "rootUri": "file://" + root,
            "workspaceFolders": [{"uri": "file://" + root, "name": "probe"}],
            "capabilities": {"textDocument": {}, "workspace": {"workspaceFolders": True}},
        })
    finally:
        lsp.shutdown()
        shutil.rmtree(root, ignore_errors=True)

    caps = result.get("capabilities", {})
    print("server:  %s" % server_dir)
    print("version: %s" % (result.get("serverInfo") or {}))
    print()
    for name in sorted(caps):
        value = caps[name]
        rendered = json.dumps(value) if not isinstance(value, (bool, str)) else value
        if isinstance(rendered, str) and len(rendered) > 110:
            rendered = rendered[:107] + "..."
        print("  %-42s %s" % (name, rendered))
    return 0


if __name__ == "__main__":
    sys.exit(main())
