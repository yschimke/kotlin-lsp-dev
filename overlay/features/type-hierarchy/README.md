# Type Hierarchy (`textDocument/typeHierarchy`)

**Status:** ✅ runnable in the enhanced build (verified over stdio against
`262.8190.0`: advertises `typeHierarchyProvider`, `prepare` + `supertypes` return correct
results). Not yet submitted upstream.
_Tracking: (add the upstream PR/issue URL here once opened)_

## What this adds

A Kotlin implementation of `textDocument/typeHierarchy` — supertypes and subtypes of the
class/interface under the cursor. kotlin-lsp already declares the provider interface
(`LSTypeHierarchyProvider`) but ships **no implementation**, so the request currently returns
nothing for Kotlin.

## Layout

| Path | Role | Verified by |
|---|---|---|
| `core/…/typeHierarchy/KotlinTypeHierarchyComputation.kt` | pure-PSI core (super/subtypes via light classes + `ClassInheritorsSearch`) | `../../../src/test/kotlin/overlay/TypeHierarchyTest.kt` (runs in the overlay) |
| `ext/…/LSKotlinTypeHierarchyProvider.kt` | LSP adapter implementing `LSTypeHierarchyProvider` | `scripts/compile-check.sh` + boots via `scripts/build-server.sh` |

The core is deliberately free of closed LSP/server types so it unit-tests against the plain
platform. The adapter is the thin LSP-facing wrapper.

## Upstream target paths (for the PR / for dropping when it lands)

- `features-impl/kotlin/src/com/jetbrains/ls/api/features/impl/kotlin/typeHierarchy/KotlinTypeHierarchyComputation.kt`
- `features-impl/kotlin/src/com/jetbrains/ls/api/features/impl/kotlin/typeHierarchy/LSKotlinTypeHierarchyProvider.kt`
- registration entry in `LSKotlinLanguageConfiguration`

When a kotlin-lsp release ships type hierarchy for Kotlin, delete this directory; the overlay
build drops it automatically.

---

## Draft PR body

> **[kotlin] Implement `textDocument/typeHierarchy` for Kotlin**
>
> `LSTypeHierarchyProvider` is defined in `api.features` but has no implementation, so
> `textDocument/typeHierarchy` returns nothing for Kotlin files. This adds one.
>
> **What it does**
> - `prepareTypeHierarchy`: resolves the class/interface at the cursor to a `TypeHierarchyItem`.
> - `supertypes` / `subtypes`: direct super/sub types, via the Kotlin light class and the
>   platform `ClassInheritorsSearch` (so Java subclasses of Kotlin types and vice-versa are
>   found).
> - The `Any`/`Object` implicit root is filtered from supertypes.
>
> **Structure**
> - `KotlinTypeHierarchyComputation` — the PSI-level logic, free of LSP types.
> - `LSKotlinTypeHierarchyProvider` — the `LSTypeHierarchyProvider` adapter, registered in
>   `LSKotlinLanguageConfiguration`.
>
> **Testing** — unit tests over a PSI fixture (interface → open class → class chain) assert
> supertypes, subtypes, and the empty leaf/root cases.
