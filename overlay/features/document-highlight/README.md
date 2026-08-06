# Document highlight (`textDocument/documentHighlight`)

**Status:** runnable and live-verified on the pinned `263.2689.0` release, via the composition
server.

## Why this is not an overlay provider

This is the first feature here that could not be an in-process provider *at all*. The other
overlay features register against a provider interface the shipped server already routes to.
For `textDocument/documentHighlight` there is nothing to register against:

- no `LSDocumentHighlightProvider` (or any equivalent) exists in any jar of the distribution;
- `documentHighlightProvider` is absent from the server's `initialize` result, so no conformant
  client would send the request even if a handler existed.

Both facts are reproducible: `scripts/probe-capabilities.py` prints the advertised capabilities,
and searching the distribution's jars for a highlight provider interface finds nothing. This is
the case [issue #9](../../../issues/9) listed as "unreachable at any layer" — true of the
in-process overlay, but not of the process boundary.

## How it is answered

`bin/enhanced-server` advertises `documentHighlightProvider` on the initialize response and then
answers the request itself, rather than forwarding it to a child that has no handler. The answer
comes from the child's own `textDocument/references`:

1. client sends `textDocument/documentHighlight` at a position;
2. the composition server issues `textDocument/references` at that position to the child, with
   `includeDeclaration: true`;
3. the child resolves the symbol with real Kotlin analysis;
4. results outside the requested document are dropped, and the rest are returned as
   `DocumentHighlight[]`.

So this is not a textual approximation — occurrences come from the same resolution that powers
Find Usages. Shadowed names, imports and same-named members in other scopes behave correctly
because the server, not a regex, decided what the symbol is.

`kind` is deliberately omitted. It is optional in the protocol, and `references` does not say
which occurrences are reads and which are writes; guessing would colour highlights wrongly in
editors that distinguish them.

An error from the child is turned into an empty result rather than an LSP error. Editors send
this request on every cursor move, so a failure to resolve must be silent.

## Upstream target

If kotlin-lsp ever registers a highlight handler and ships a provider interface, this belongs
in-process as an ordinary overlay feature and the routing here should be deleted. Until then the
boundary is the only place it can live.
