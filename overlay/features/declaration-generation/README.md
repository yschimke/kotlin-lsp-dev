# Declaration-generation code actions

**Status:** ✅ runnable on `262.8190.0`. `textDocument/codeAction` dispatch is additive, and the
provider uses the already-advertised `refactor.rewrite` kind. Verified over stdio: a class with
an unimplemented interface function receives an **Implement missing members** action whose edit
adds the override stub.
_Tracking: [kotlin-lsp-dev#4](https://github.com/yschimke/kotlin-lsp-dev/issues/4),
[Kotlin/kotlin-lsp#171](https://github.com/Kotlin/kotlin-lsp/issues/171)_

## What this adds

Two non-interactive code actions on a Kotlin class:

- **Implement missing members** generates stubs for all inherited abstract functions not already
  declared by the class. This is preferred and avoids a client-specific member picker.
- **Override members** generates stubs for inherited, non-final concrete functions not already
  declared by the class.

Generated functions retain parameter names and resolved light-class types, escape Kotlin keyword
names, and use `TODO("Not yet implemented")` bodies. Actions are omitted when there is nothing to
generate and interfaces are not offered implementation actions.

## Layout

| Path | Role | Verified by |
|---|---|---|
| `core/…/codeActions/DeclarationGenerationComputation.kt` | resolves inherited members and renders insertion edits using PSI/light classes | `test/DeclarationGenerationTest.kt` |
| `ext/…/LSKotlinDeclarationGenerationCodeActionProvider.kt` | additive `LSCodeActionProvider` adapter carrying direct workspace edits | server build + live stdio smoke test |

## Upstream target path

- `features-impl/kotlin/src/com/jetbrains/ls/api/features/impl/kotlin/codeActions/…`
- provider registration in `LSKotlinLanguageConfiguration`

## Deliberate first-cut limits

The actions generate functions. Kotlin properties and generic type substitution need Kotlin-aware
rendering rather than JVM accessor/light-class rendering and are left for a follow-up. The first
cut is deliberately non-interactive because the closed protocol layer cannot add a picker request.

---

## Draft PR body

> **[kotlin] Generate implementations and overrides**
>
> Adds `refactor.rewrite` actions that generate all missing abstract functions or all available
> concrete function overrides for the class at the cursor. The actions carry direct workspace
> edits and need no editor-specific command or picker protocol.
>
> `DeclarationGenerationComputation` contains the PSI/light-class member resolution and rendering;
> `LSKotlinDeclarationGenerationCodeActionProvider` is the thin LSP adapter.
>
> Tests cover multiple missing functions, already-implemented functions, concrete overrides, and
> positions outside classes.
