## Check the import

Open a Gradle or Maven project. The status bar shows the server's progress, and the tick matters
more than it looks:

| Status bar | Meaning |
|---|---|
| `⟳ Kotlin: starting` | the server process is coming up |
| `⟳ Kotlin: indexing` | imported, but the index is **not** complete |
| `✓ Kotlin` | indexed — index-backed results are now complete |

**Before the tick, index-backed operations answer from a partial index rather than failing.** A
rename can come back with the declaration renamed and every usage missed, and nothing reports an
error. This is why the state is in the status bar at all, and it is worth waiting for.

If the import fails outright the status bar says so and offers **Doctor**, which reports what the
server actually resolved: modules, source roots, classpath, and the JDK. Zero modules means the
import did not happen, and most features cannot work until it does.
