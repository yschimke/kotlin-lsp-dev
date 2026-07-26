#!/usr/bin/env python3

import importlib.util
import io
import json
import os
import unittest


SCRIPT = os.path.join(os.path.dirname(__file__), "enhanced-server.py")
SPEC = importlib.util.spec_from_file_location("enhanced_server", SCRIPT)
PROXY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROXY)


class EnhancedServerTest(unittest.TestCase):
    def frame(self, message, newline=b"\r\n", extra=b""):
        body = json.dumps(message).encode()
        return (b"Content-Length: %d" % len(body) + newline + extra
                + newline + body)

    def test_read_frame_preserves_original_bytes(self):
        message = {"jsonrpc": "2.0", "method": "initialized", "params": {}}
        raw = self.frame(message, newline=b"\n", extra=b"X-Test: yes\n")

        actual_raw, actual_message = PROXY.read_frame(io.BytesIO(raw))

        self.assertEqual(raw, actual_raw)
        self.assertEqual(message, actual_message)

    def test_patch_initialize_adds_range_formatting_capability(self):
        message = {"jsonrpc": "2.0", "id": 7, "result": {"capabilities": {
            "documentFormattingProvider": True,
        }}}

        patched, changed = PROXY.patch_initialize(message, 7)

        self.assertTrue(changed)
        self.assertIs(patched, message)
        self.assertTrue(patched["result"]["capabilities"]
                        ["documentRangeFormattingProvider"])

    def test_patch_initialize_ignores_other_responses(self):
        message = {"jsonrpc": "2.0", "id": 8, "result": {"capabilities": {}}}

        patched, changed = PROXY.patch_initialize(message, 7)

        self.assertFalse(changed)
        self.assertEqual(message, patched)

    def test_installed_server_dir_is_parent_of_bin(self):
        expected = os.path.dirname(os.path.dirname(os.path.abspath(SCRIPT)))

        self.assertEqual(expected, PROXY.installed_server_dir())


if __name__ == "__main__":
    unittest.main()
