# The composition server: escaping provider and dispatch limits at the LSP boundary

_Design note. The architecture described here is implemented in
[`launcher/overlay/server/KotlinLspServer.kt`](../launcher/overlay/server/KotlinLspServer.kt) and
installed as `bin/enhanced-server`. Originally proposed in
[PR #14](https://github.com/yschimke/kotlin-lsp-dev/pull/14); rewritten here to describe what
shipped._

## The problem

[Issue #9](https://github.com/yschimke/kotlin-lsp-dev/issues/9) records three limits inside the
pinned server:

- some requests have **no registered handler**, so nothing routes them;
- some provider combiners **reject a second provider**, fatally rather than by degrading;
- some capabilities are **implemented but never advertised**, so no conformant client asks.

All three are limits of an in-process `LanguageServerExtension`. None of them is a limit of the
Language Server Protocol. An extension lives inside the child's dispatcher and is bound by its
rules; a process that owns the protocol boundary is not.

## The shape

The editor starts `bin/enhanced-server`, which starts the official `bin/intellij-server` as a
child and speaks LSP on both sides. To the client it is one language server. It can:

- advertise capabilities the child does not;
- answer a request without forwarding it;
- forward a request unchanged (the overwhelming majority);
- forward a request and replace, filter, or merge the child's response;
- issue requests of its own to the child, correlated independently of the client's traffic.

What it explicitly does **not** do is make a missing JetBrains provider API appear. It relocates
the implementation outside the closed server, which means the feature needs a source of answers
that does not depend on the API that is missing.

## Three tiers, and picking the right one

Prefer the highest tier that works. Lower tiers cost more and are less upstream-shaped.

| Tier | Condition | Example |
|---|---|---|
| 1. In-process provider | capability advertised **and** dispatch is additive | type hierarchy, code actions, diagnostics, inlay hints, code lenses |
| 2. Capability repair at the boundary | server implements the operation but omits the flag | `rangeFormatting` |
| 3. Answered at the boundary | no handler **and** no provider interface | `documentHighlight` |

Tier 1 is where nearly everything belongs. Tier 2 is nearly free — one flag on one response, no
behaviour change. Tier 3 is where the design earns its keep, and where it is easiest to do
something dishonest.

## The constraint that keeps tier 3 honest

**The boundary has no Kotlin analysis. It sees JSON, not PSI.**

So a tier-3 feature is only legitimate if it reduces to requests the child already answers.
`documentHighlight` qualifies: it is a `textDocument/references` at the same position, filtered to
the requested document. The child resolves the symbol with the same analysis that powers Find
Usages, so shadowing, imports and same-named members in other scopes behave correctly. We supply
routing, not semantics.

The counter-example is `selectionRange`. It has no provider interface and is not advertised, so it
is out in-process, and a brace- and indentation-based approximation at the boundary would be easy
to write. It would also be the wrong thing to ship: a client that trusts the advertised capability
would show *wrong* selections rather than none, and wrong is worse than absent for an operation
bound to a keystroke. Advertising a capability commits you to answering it properly.

This is the rule to apply to any new tier-3 candidate: if you cannot name the child request whose
answer you are reshaping, you are about to approximate semantics, and you should stop.

## How each limit is bypassed

| In-process limitation | Boundary behaviour |
|---|---|
| No registered handler (`documentHighlight`) | Advertise the capability and consume the method here; never forward it, because the child would error. |
| Capability not advertised (`rangeFormatting`) | Set the flag on the successful `initialize` response. Keep forwarding the request — the child's handler is the implementation. |
| A second provider breaks dispatch (completion, formatting, signature help) | Keep exactly one provider in the child. Make one ordinary child request and merge or replace **after** the response leaves the child. |
| First non-null provider wins (hover, rename) | Intercept before the child sees it. Prefer ours, prefer the child's, or merge, by an explicit policy rather than by ServiceLoader order. |

The rewrite must happen on the **successful response** to `initialize`, not on the request. And
for object-valued capabilities — code lens, selection ranges, completion triggers — the published
value must have the shape the negotiated LSP version requires, not merely `true`.

## Implementation notes

These are the parts that are easy to get wrong and are already handled:

- **Id space.** Requests the boundary originates use string ids prefixed `kotlin-lsp-dev/`. They
  cannot collide with the client's ids, and responses carrying them are intercepted rather than
  relayed — the client never learns those requests happened.
- **Both pumps write to the client.** The client-to-child pump answers tier-3 requests directly
  while the child-to-client pump is relaying, so every client write goes through a synchronized
  emit.
- **Only `initialize` is re-encoded.** Every other frame is relayed byte for byte. Semantic tokens
  and diagnostics are large and frequent; a parse-and-reserialise on the hot path would be a real
  cost for no benefit.
- **Errors on cursor-driven requests become empty results.** Editors send `documentHighlight` on
  every cursor move. An error per keystroke is worse than no highlight.
- **Transport.** `--stdio` and `--socket <port>`; `--socket 0` announces an ephemeral port in the
  shipped launcher's own wording, so the composition server is a drop-in wherever
  `bin/intellij-server` is spawned — including the official VS Code extension, via
  `intellij.dev.serverPort`. CI runs the whole smoke suite over both transports.

## Ownership rule

Use the overlay for safe additive providers; use the boundary for missing or non-composable
operations. **Do not implement the same method in both layers.** A boundary feature still gets a
directory under `overlay/features/<name>/` with a README and a `smoke/check.py`, even with no
`core/` or `ext/`, so that deleting the directory removes its coverage with it — and so the smoke
check, which is the only thing that can prove a boundary feature works, is never optional.

## What would retire this

If a release registers a handler and ships a provider interface for one of these operations, the
feature belongs in-process as an ordinary overlay feature and the routing here should be deleted.
That has already happened once in the other direction: code lenses were release-gated until
`263.2689.0` shipped `LSCodeLensProvider`, at which point the in-process feature activated with no
code change. Re-check with `scripts/probe-capabilities.py` on every release bump.
