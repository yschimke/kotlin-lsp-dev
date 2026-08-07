## Find the features

Much of what the server can do is behind editor UI that never mentions Kotlin, so it is easy to
miss:

**Hierarchies.** `Shift+Alt+H` opens the Call Hierarchy view — incoming and outgoing calls, as a
tree. Right-click → **Show Type Hierarchy** does the same for supertypes and subtypes. Both are
built-in VS Code views; nothing here has to be enabled.

**Testing panel.** Kotlin tests are discovered from the `▶ Run test` code lenses and appear in the
Testing view. Run or debug them individually or in groups; results come from Gradle's JUnit reports,
so a failing assertion is attributed to the test that failed.

**Kotlin project tree.** In the Explorer sidebar: modules, their source roots, and their
dependencies. Expand a jar to walk its packages and open any class as decompiled source.

**Refactorings.** Twenty of them, on the lightbulb (`Ctrl+.`) — extract variable/function/constant,
inline, move file, safe delete, change signature, invert `if`, `if`-chain to `when`, and more. See
`docs/refactorings.md` for the full list.

**Chat tools.** `#kotlinDoctor` reports how the workspace actually imported; `#kotlinJars` searches
inside dependency jars. Both answer questions the source alone cannot.

**Terminal stack traces.** Frames like `at pkg.Class.method(File.kt:42)` are clickable.
