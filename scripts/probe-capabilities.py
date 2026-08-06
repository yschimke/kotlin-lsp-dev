#!/usr/bin/env python3
"""Print the initialize capabilities a server advertises.

The initialize result is the real gate on what any conformant client will ever request, and it
is almost entirely hardcoded in the shipped server. Reading it directly is the fastest way to
tell whether a new release unblocks a release-gated feature -- far more reliable than
disassembling the obfuscated launcher, which is re-obfuscated on every build.

Usage: probe-capabilities.py <server-dir> [--launcher bin/intellij-server]
"""

import json
import os
import subprocess
import sys
import tempfile
import threading

TIMEOUT = 180


def read_frame(stream):
    length = None
    while True:
        line = stream.readline()
        if not line:
            return None
        line = line.decode("utf-8", "replace").strip()
        if not line:
            break
        name, _, value = line.partition(":")
        if name.strip().lower() == "content-length":
            length = int(value.strip())
    if length is None:
        return None
    return json.loads(stream.read(length).decode("utf-8"))


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

    # Give the probe its own caches and home. Without this it contends with any server the
    # editor already has running against the shared XDG directories, and simply never answers.
    env = os.environ.copy()
    env.update({
        "XDG_CACHE_HOME": os.path.join(root, ".xdg", "cache"),
        "XDG_CONFIG_HOME": os.path.join(root, ".xdg", "config"),
        "XDG_DATA_HOME": os.path.join(root, ".xdg", "data"),
    })
    env["JAVA_TOOL_OPTIONS"] = (
        env.get("JAVA_TOOL_OPTIONS", "") + " -Duser.home=" + os.path.join(root, ".user-home")
    ).strip()

    proc = subprocess.Popen(
        [launcher, "--stdio", "--system-path", os.path.join(root, ".system")],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        cwd=root, env=env)

    body = json.dumps({
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {
            "processId": None,
            "rootUri": "file://" + root,
            "workspaceFolders": [{"uri": "file://" + root, "name": "probe"}],
            "capabilities": {"textDocument": {}, "workspace": {"workspaceFolders": True}},
        },
    }).encode()
    proc.stdin.write(b"Content-Length: %d\r\n\r\n" % len(body) + body)
    proc.stdin.flush()

    result = {}
    done = threading.Event()

    def pump():
        while True:
            msg = read_frame(proc.stdout)
            if msg is None:
                break
            if msg.get("id") == 1 and "result" in msg:
                result.update(msg["result"])
                done.set()
                break

    threading.Thread(target=pump, daemon=True).start()
    if not done.wait(TIMEOUT):
        proc.kill()
        sys.exit("timed out waiting for the initialize result")
    proc.kill()

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
