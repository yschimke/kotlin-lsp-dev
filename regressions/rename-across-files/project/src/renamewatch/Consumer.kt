package renamewatch

// A *second* file referring to the renamed class is the whole point of this check: a rename that
// only edits the declaration's own file looks successful and leaves the project uncompilable.
class Consumer {
    fun build(): RenameTarget = RenameTarget()

    fun describe(target: RenameTarget): String = target.label()
}
