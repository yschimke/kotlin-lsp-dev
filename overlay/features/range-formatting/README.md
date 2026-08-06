# Range formatting capability repair

## Status

Runnable on the pinned `262.8190.0` release through the outer composition server.

The shipped server already registers `textDocument/rangeFormatting` and its built-in Kotlin
formatting provider answers the request, but its `initialize` result omits
`documentRangeFormattingProvider`. Editors therefore do not send Format Selection requests.

Adding another in-process formatting provider is unsafe: the pinned dispatcher rejects multiple
`LSFormattingProvider` instances. The overlay-owned `bin/enhanced-server` instead preserves the
single built-in provider and repairs the capability on the successful initialize response before
it reaches the editor.

## Live verification

`smoke/check.py` requires the repaired capability and sends a real
`textDocument/rangeFormatting` request. It applies the returned edits and checks the selected
Kotlin function is formatted. When the stock launcher is substituted for `enhanced-server`, the
request still formats successfully but the check fails because the capability is absent.
