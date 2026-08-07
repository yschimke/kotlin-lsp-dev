# Upstream PR branches

Nineteen overlay features rewritten into the shape a `Kotlin/kotlin-lsp` pull request would take,
one branch per feature, all prefixed `pr_`. Nothing is pushed and no PR is opened — these are
ready to raise, not raised.

## How a branch is built

Each branch is `main` of `Kotlin/kotlin-lsp` plus **two commits**:

1. **The change itself** — sources at their real upstream paths, registration in
   `LSKotlinLanguageConfiguration`, and any message-bundle keys. Commit message is written as the
   PR body would be, in upstream's `[kotlin] <summary>` style.
2. **`PR.md`** — a working note explaining the feature, the design decisions worth defending in
   review, and a copy-pasteable **Suggested PR description**. Its commit message says to drop it
   before opening the PR, so the first commit is the whole proposed diff.

The mapping from the overlay layout is mechanical:

| Overlay | Upstream branch |
|---|---|
| `overlay/features/<f>/core/com/…` | `features-impl/kotlin/src/com/…` |
| `overlay/features/<f>/ext/com/…` | `features-impl/kotlin/src/com/…` |
| `overlay/features/<f>/ext/overlay/…` | dropped — the `LanguageServerExtension` glue exists only because we cannot edit the server |
| `overlay/features/<f>/resources/META-INF/services/…` | dropped — same reason |
| `overlay/features/<f>/smoke/` | dropped — our harness, not theirs |
| — | new entry in `LSKotlinLanguageConfiguration` |

## The branches

| Branch | Commit subject | Size |
|---|---|---|
| `pr_kotlin-type-hierarchy` | `[kotlin] implement textDocument/typeHierarchy` | 3 files, +157 |
| `pr_kotlin-region-folding` | `[kotlin] fold //region ... //endregion custom regions` | 3 files, +95 |
| `pr_kotlin-move-file` | `[kotlin] implement workspace/willRenameFiles for Kotlin file moves` | 3 files, +100 |
| `pr_kotlin-closing-brace-inlay-hints` | `[kotlin] add closing-brace inlay hints` | 3 files, +110 |
| `pr_kotlin-unused-import-diagnostics` | `[kotlin] report unused imports as diagnostics (#201)` | 4 files, +77 |
| `pr_kotlin-expression-body` | `[kotlin] add "Convert to expression body" code action` | 4 files, +89 |
| `pr_kotlin-extract-variable` | `[kotlin] add "Extract variable" code action` | 4 files, +129 |
| `pr_kotlin-safe-delete` | `[kotlin] add "Safe delete" code action` | 4 files, +147 |
| `pr_kotlin-code-vision-lenses` | `[kotlin] add usage, implementation and run-test code lenses` | 4 files, +159 |
| `pr_kotlin-named-arguments` | `[kotlin] add "Fill call arguments" code action (#175)` | 4 files, +181 |
| `pr_kotlin-inline-function` | `[kotlin] add "Inline function" code action` | 4 files, +193 |
| `pr_kotlin-extract-constant` | `[kotlin] add "Extract constant" code action` | 4 files, +194 |
| `pr_kotlin-inline-variable` | `[kotlin] add "Inline variable" code action` | 4 files, +198 |
| `pr_kotlin-workspace-commands` | `[kotlin] add doctor, stack-trace, dependency-search and FQN workspace commands` | 4 files, +199 |
| `pr_kotlin-implement-override-members` | `[kotlin] add "Implement missing members" and "Override members" code actions (#171)` | 4 files, +203 |
| `pr_kotlin-declaration-code-actions` | `[kotlin] add block-body, explicit-type, split-declaration and flip-binary code actions` | 4 files, +218 |
| `pr_kotlin-conditional-code-actions` | `[kotlin] add invert-if, merge-nested-if and if-chain-to-when code actions` | 4 files, +249 |
| `pr_kotlin-extract-function` | `[kotlin] add "Extract function" code action` | 4 files, +298 |
| `pr_kotlin-change-signature` | `[kotlin] add remove-unused-parameter and introduce-parameter code actions` | 6 files, +468 |

Three features are **not** here, and each for a reason that is about upstream, not about the
feature:

| Not submitted | Why |
|---|---|
| **Range formatting** | The repair is to the `initialize` capability set, which is constructed in `product.jar`. Nothing in the open mirror advertises capabilities, so there is no file to change. The provider it re-enables is already upstream's own. |
| **Document highlight** | There is no `LSDocumentHighlightProvider` — or any equivalent — in `api.features`, and no handler. Adding it means designing a new provider interface *and* wiring a request handler that lives in closed code. Not a contribution the mirror can express. |
| **VS Code client** | Excluded on licensing grounds. |

## What the branches change relative to the overlay

Three deliberate differences, all in the direction of matching upstream's own code:

**User-visible strings move into `LspServerBundle`.** Every code-action title, lens label, command
title and diagnostic message is a bundle key with the `command.`/`warning.` prefixes the file
already uses, following `LSKotlinOrganizeImportsCodeActionProvider` and
`LSJvmRunMainCodeLensProvider`. Parameterised keys double their apostrophes (`''{0}''`), as
`error.unresolved.dependencies` does, because `MessageFormat` treats a single quote as an escape.

**English leaves the computations.** `SafeDeleteComputation` reported its declaration shape as a
`String` (`"local variable"`); it now returns a `Kind` enum and the provider picks the localised
title. `DeclarationGenerationComputation` already did this.

**Command names take the `kotlin.` prefix.** `kotlin-lsp.doctor` becomes `kotlin.doctor`, matching
the existing `kotlin.organize.imports`. Command names must be globally unique or server startup
fails, so the namespace is load-bearing rather than cosmetic. Anything driving these commands
against an overlay-patched server keeps the old names; the rename applies to the upstream branch
only.

## Tests

**No tests are included in the branches, and that is a judgement call worth re-examining if you
disagree.**

`features-impl/kotlin` has no test module in the mirror. The only test module for language-server
code is `api.features/test`, which is a sibling module with its own `.iml` and a `BUILD.bazel`
whose sections are marked auto-generated — they are produced from the `.iml` by JPS-to-Bazel
tooling inside the monorepo, and a new module also has to be registered in project files the
mirror does not carry. On top of that, the mirror cannot be built at all standalone: every Bazel
dependency is an `@community//` label pointing into the IntelliJ monorepo. A hand-written test
module would therefore be unbuildable here, unverifiable by the submitter, and regenerated on the
other side.

So each `PR.md` states plainly what is tested, where, and against which release, and offers to add
the tests in whatever form suits the internal layout. Every feature keeps its unit tests and its
live smoke check in this repository, which is where they can actually run.

## Raising one

```sh
git checkout pr_kotlin-type-hierarchy
git reset --hard HEAD~1        # drop the PR.md commit
git push -u fork pr_kotlin-type-hierarchy
```

Read `PR.md` first — the "Suggested PR description" section at the bottom is the PR body.

## Context worth knowing before raising any of them

`Kotlin/kotlin-lsp`'s README says the repository is a read-only mirror, that direct contributions
are not supported, and that only documentation PRs get integrated manually. **No pull request has
ever been merged** — the merged-PR list is empty, and the four open PRs include one from May.
Upstream commit style (`LSP-1568 [lsp] …`, `[kotlin] …`) is what the branches follow, since there
is no accepted-PR house style to match.

That is the reason these are prepared rather than raised: the branches are worth having in
submittable shape, but the overlay remains the permanent home of every feature.
