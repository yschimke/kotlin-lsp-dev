#!/usr/bin/env python3
"""Run kotlin-lsp with the usable capabilities supplied by this harness.

The runner starts ``intellij-server --stdio`` and changes only its initialize
response. It speaks stdio by default, so an editor can use it as its language
server command, or it can accept one TCP client for VS Code's serverPort mode.
Every other complete LSP frame is forwarded with its original bytes intact.
"""

import argparse
import json
import os
import socket
import subprocess
import sys
import threading


class ProxyError(Exception):
    pass


def read_frame(stream):
    """Return (raw frame, decoded message), or (None, None) at clean EOF."""
    header_lines = []
    content_length = None
    while True:
        line = stream.readline()
        if not line:
            if not header_lines:
                return None, None
            raise ProxyError("truncated LSP headers")
        header_lines.append(line)
        if line in (b"\r\n", b"\n"):
            break
        try:
            name, value = line.decode("ascii").split(":", 1)
        except (UnicodeDecodeError, ValueError) as error:
            raise ProxyError("invalid LSP header: %r" % line) from error
        if name.lower() == "content-length":
            content_length = int(value.strip())
    if content_length is None:
        raise ProxyError("LSP frame has no Content-Length header")

    body = stream.read(content_length)
    if len(body) != content_length:
        raise ProxyError("truncated LSP body")
    raw = b"".join(header_lines) + body
    try:
        return raw, json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ProxyError("invalid JSON-RPC body") from error


def encode_frame(message):
    body = json.dumps(message, separators=(",", ":")).encode("utf8")
    return b"Content-Length: %d\r\n\r\n" % len(body) + body


def patch_initialize(message, initialize_id):
    """Patch the matching successful response and report whether it changed."""
    if initialize_id is None or message.get("id") != initialize_id:
        return message, False
    result = message.get("result")
    if not isinstance(result, dict):
        return message, False
    capabilities = result.get("capabilities")
    if not isinstance(capabilities, dict):
        return message, False
    capabilities["documentRangeFormattingProvider"] = True
    return message, True


def copy_client_to_server(client, server_stdin, state):
    while True:
        raw, message = read_frame(client)
        if raw is None:
            return
        if message.get("method") == "initialize" and "id" in message:
            state["initialize_id"] = message["id"]
        server_stdin.write(raw)
        server_stdin.flush()


def copy_server_to_client(server_stdout, client, state):
    while True:
        raw, message = read_frame(server_stdout)
        if raw is None:
            return
        message, changed = patch_initialize(message, state.get("initialize_id"))
        client.write(encode_frame(message) if changed else raw)
        client.flush()
        if changed:
            print("[enhanced-server] advertised documentRangeFormattingProvider=true",
                  file=sys.stderr)


def drain_stderr(stream):
    for chunk in iter(lambda: stream.read(8192), b""):
        sys.stderr.buffer.write(chunk)
        sys.stderr.buffer.flush()


def run(server_dir, client_input, client_output):
    """Run one server process connected to the supplied client streams."""
    launcher = os.path.join(os.path.abspath(server_dir), "bin", "intellij-server")
    if not os.path.isfile(launcher):
        raise ProxyError("not an unpacked kotlin-lsp server: %s" % server_dir)

    process = subprocess.Popen(
        [launcher, "--stdio"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE)
    state = {}
    stderr_thread = threading.Thread(target=drain_stderr, args=(process.stderr,), daemon=True)
    upstream_thread = threading.Thread(
        target=copy_client_to_server, args=(client_input, process.stdin, state), daemon=True)
    stderr_thread.start()
    upstream_thread.start()
    try:
        copy_server_to_client(process.stdout, client_output, state)
    finally:
        if process.poll() is None:
            process.terminate()
        process.wait(timeout=20)


def serve_tcp(server_dir, host, port):
    with socket.socket() as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind((host, port))
        listener.listen(1)
        actual_port = listener.getsockname()[1]
        print("[enhanced-server] listening on %s:%d" % (host, actual_port),
              file=sys.stderr, flush=True)
        connection, address = listener.accept()

    print("[enhanced-server] client connected from %s:%d" % address,
          file=sys.stderr, flush=True)
    try:
        with connection.makefile("rwb", buffering=0) as client:
            run(server_dir, client, client)
    finally:
        connection.close()


def installed_server_dir():
    """The server root when this script is installed as <server>/bin/enhanced-server."""
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("server_dir", nargs="?", default=installed_server_dir(),
                        help="unpacked kotlin-lsp distribution (inferred when installed)")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--stdio", action="store_true",
                      help="speak LSP on stdin/stdout (default)")
    mode.add_argument("--port", type=int,
                      help="accept one TCP client on this port")
    parser.add_argument("--host", default="127.0.0.1",
                        help="TCP bind address (with --port)")
    args = parser.parse_args()
    try:
        if args.port is None:
            run(args.server_dir, sys.stdin.buffer, sys.stdout.buffer)
        else:
            serve_tcp(args.server_dir, args.host, args.port)
    except (OSError, ProxyError, subprocess.SubprocessError) as error:
        parser.exit(1, "enhanced-server: %s\n" % error)
    return 0


if __name__ == "__main__":
    sys.exit(main())
