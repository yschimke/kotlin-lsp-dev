## Install a server

This extension is a **client only**. It does not download a JetBrains distribution and does not go
looking for a JDK, because the servers it talks to already carry their own runtime. That means one
install step you have to do yourself — and afterwards there is nothing to keep in sync.

Either of these works:

**The enhanced server** — the official release plus the overlay features (extra refactorings, code
vision, the project tree, the workspace commands):

```sh
scripts/install.sh
```

**A stock `kotlin-lsp` release** — download and unpack it, then point the extension at the
directory. Everything still works except the overlay-only commands, which are hidden rather than
broken.

The extension picks whichever launcher the directory has: `bin/enhanced-server`, or the release's
own `bin/intellij-server`.
