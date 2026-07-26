# Escaping provider and request-dispatch limits with an LSP proxy

[Issue #9](https://github.com/yschimke/kotlin-lsp-dev/issues/9) records limits inside the pinned
server: some requests have no handler, some provider combiners reject a second provider, and some
capabilities are not advertised. Those are limits of an **in-process `LanguageServerExtension`**,
not of the Language Server Protocol.

## Conclusion

A proxy (or wrapper server) can work around all three classes of limit. The editor starts the proxy
instead of `bin/intellij-server`; the proxy starts the real server as a child and speaks LSP on both
sides. To the client it is one language server. It can:

- advertise capabilities that the child does not advertise;
- answer a request without sending it to the child;
- forward a request unchanged;
- forward a request and replace, filter, or merge the child's response; and
- observe document and workspace notifications needed by its own implementations.

This does **not** make a missing JetBrains provider API available. It moves the new implementation
outside the closed server. A feature still needs an independent computation engine or must be
expressible in terms of requests that the child already supports.

## Why this bypasses each limit

| In-process limitation | Proxy behaviour |
|---|---|
| No registered handler, for example `textDocument/selectionRange` | Add the capability to the child's `initialize` response and consume that method in the proxy. |
| Child does not advertise a capability, for example `codeLensProvider` | Add it to the response returned to the client. Do not forward the request unless a future child advertises and implements it. |
| A second provider makes dispatch fail, for example completion or formatting | Keep exactly one provider inside the child. Make one normal child request and merge/replace the result **after** it leaves the child. |
| First non-null provider wins, for example hover | Intercept before child dispatch. The proxy can prefer its result, prefer the child's, or merge both according to an explicit policy. |

The capability rewrite must happen on the successful response to `initialize`, not just in the
request. For object-valued capabilities (code lens, selection ranges, completion triggers, and so
on), the proxy must publish the shape required by the negotiated LSP version rather than merely
setting every field to `true`.

## Recommended architecture

Use a small standalone wrapper process rather than embedding another provider in the JetBrains
process:

```text
editor  <--- JSON-RPC/LSP over stdio --->  wrapper  <--- JSON-RPC/LSP over stdio --->  Kotlin LSP
                                             |
                                             +--- document store / added feature engine
```

The wrapper should have four separable pieces:

1. **Framing pump** — parse `Content-Length` headers and JSON bodies in both directions. Never use
   line-oriented JSON; LSP messages can contain newlines and multiple headers.
2. **Router** — classify client requests as `forward`, `local`, or `augment`. Pass server-to-client
   requests (such as `workspace/applyEdit`) and their client responses through as well as ordinary
   notifications.
3. **Capability rewriter** — retain the child initialize result, publish the union of child and
   wrapper capabilities, and remember which side owns each method.
4. **Feature engine** — keep versioned text from `didOpen`, `didChange`, and `didClose`, plus
   workspace-folder and configuration state. Each added operation is implemented and tested here.

A JVM wrapper is the most promising production choice for this repository. It can reuse the pure
Kotlin/PSI computations under `overlay/features/*/core` without importing closed server types.
A Python or Node wrapper is useful for validating routing, but recreating accurate Kotlin syntax
and semantic analysis there would duplicate substantial compiler work.

## Routing policies

Policies must be per method rather than a single global fallback:

- **Local:** answer an absent operation such as selection ranges or document highlights.
- **Child:** transparent pass-through for everything the wrapper does not own.
- **Replace:** use a wrapper formatter/completion implementation and do not call the child.
- **Augment:** call the child once and merge outside it. This is safe for completion because no
  second completion provider is registered in the child.
- **Fallback:** try the wrapper, then call the child when the wrapper deliberately has no result.

For augmentation, preserve the client's request id while allocating a distinct internal id for any
proxy-originated child call. The proxy needs an id map because both peers may concurrently issue
requests. It must route `$/cancelRequest` to the active owner and discard late responses after a
locally cancelled request.

## What can be implemented realistically

### Good first proxy-only features

- **Selection range:** needs a Kotlin parser and the proxy's current document text, but no project
  index for a useful first version.
- **Document highlight:** lexical same-file occurrences are easy; correct read/write classification
  and symbol identity require PSI resolution.
- **Document links / colors:** practical when rules are syntactic or project-independent.
- **Formatting replacement:** can call an external formatter and return edits, entirely bypassing
  the child's single-provider guard.
- **Completion augmentation:** can call the child once, normalize `CompletionList` versus array
  responses, add proxy items, and combine `isIncomplete` deliberately.

### Features that still need serious infrastructure

- Code-lens reference and implementation counts need a project index or carefully composed child
  requests. One `references` request per visible declaration may work as a prototype but is likely
  too expensive and must handle cancellation and document versions.
- Rename, declaration, and semantic document highlights need Kotlin symbol resolution. Mirroring
  text alone is insufficient for production correctness.
- Run/debug lenses require editor commands and a build-system/test discovery integration in
  addition to the LSP response.

The child cannot expose its live PSI objects across JSON-RPC. Reusing the existing pure-PSI cores
therefore means the wrapper must create and maintain its own IntelliJ/Kotlin analysis environment,
or a feature must be rewritten to use child LSP requests. This memory and indexing duplication is
the main cost of the approach.

## Protocol details that are easy to miss

A transparent wrapper must correctly handle all of the following before it is safe as the default
server:

- bidirectional requests, notifications, responses, errors, and out-of-order completion;
- numeric **and** string JSON-RPC ids without collisions;
- incremental text edits in UTF-16 LSP coordinates and monotonically versioned snapshots;
- `initialize`, `initialized`, `shutdown`, `exit`, child crashes, stderr forwarding, and signals;
- `$/cancelRequest`, `window/workDoneProgress/create`, progress, and partial-result tokens;
- dynamic `client/registerCapability` / `client/unregisterCapability` requests;
- workspace folders, configuration changes, watched files, and file rename/create/delete events;
- URI normalization without assuming every document is a local file;
- bounded queues/back-pressure so a noisy child cannot deadlock on full stdout or stderr pipes;
- preserving unknown headers and fields where possible so future protocol additions pass through.

If the child dynamically registers a method owned by the wrapper, the wrapper must suppress or
rewrite that registration. Conversely, a future release may acquire a currently local method; an
explicit ownership policy prevents silently returning duplicate results after an upgrade.

## Suggested delivery plan

1. Build a framing/router spike that transparently proxies the current smoke suite. Its first
   acceptance test is byte-semantics, not a new Kotlin feature.
2. Add an initialize-response capability patch and one deliberately simple local request, with a
   fake-child integration test covering interleaved ids and a real-server stdio smoke test.
3. Add the document mirror and selection ranges using a reusable pure-PSI computation.
4. Prototype completion augmentation to prove that a child operation forbidden to multiple
   in-process providers can be safely extended outside the child.
5. Only then consider a persistent Kotlin analysis/index in the wrapper. Measure startup time,
   memory, indexing latency, cancellation, and correctness against the child before committing to
   reference-heavy code lenses.

The wrapper should initially remain optional. A proxy bug can affect every operation, whereas an
in-process additive overlay can usually affect only its own request. Keep using the existing
overlay for additive providers: it has lower latency, shares the child's PSI/index, and has a much
smaller protocol surface.

## Decision

Yes: “run a new server and call through to the real one” is the recommended escape hatch for issue
#9. It is a composition server, conventionally called an LSP proxy or middleware server. It can add
and replace operations because dispatch occurs before and after the child's internal provider
combiner. It should complement, not replace, the overlay until its protocol transparency and
analysis costs have been demonstrated.
