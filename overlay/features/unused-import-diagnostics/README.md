# Unused-import diagnostics

**Status:** runnable on the pinned release; unit-tested and verified over live stdio.

_Tracking: [Kotlin/kotlin-lsp#201](https://github.com/Kotlin/kotlin-lsp/issues/201)_

Publishes `UNUSED_IMPORT` warnings tagged `Unnecessary`. The computation delegates to the Kotlin
plugin's `KotlinOptimizeImportsFacility`, the same analysis used by the built-in **Organize
Imports** action, rather than attempting unreliable text matching. This fills the deliberate
`KotlinUnusedImportInspection` blacklist entry (`LSP-704`) without changing upstream jars.

Diagnostics are additive on `262.8190.0`, so this provider safely runs alongside syntax,
compiler, and inspection providers. The existing organize-imports source action supplies the fix.

## Upstream target

- `features-impl/kotlin/src/com/jetbrains/ls/api/features/impl/kotlin/diagnostics/imports/`
- register `LSKotlinUnusedImportDiagnosticProvider` in `LSKotlinLanguageConfiguration`

## Verification

- Core: unused, used, and aliased imports are covered by platform fixture tests.
- Adapter: `scripts/build-server.sh --jar-only` compiles it against the pinned distribution.
- Live: `textDocument/diagnostic` returns exactly one warning for the unused import, including the
  `Unnecessary` tag; the used import is not reported.
