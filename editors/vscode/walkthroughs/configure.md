## Point the extension at it

`kotlinLspDev.serverPath` is the directory of an unpacked server. Left empty it means
`~/.local/share/kotlin-lsp-enhanced`, which is where `scripts/install.sh` puts one — so if you used
that, there is nothing to set.

Two settings worth knowing about now, because both cause confusing failures if you meet them by
accident:

**`kotlinLspDev.isolateIndex`** (on by default) gives this workspace its own index cache. The shared
cache is *locked* by whichever server holds it, so with isolation off, a second server on the same
project — the official extension's, or one you started yourself — cannot start at all.

**`kotlinLspDev.serverPort`** attaches to a server you started yourself with
`bin/enhanced-server --socket 9999` instead of launching one. That keeps the JVM warm across editor
reloads and puts the server log in your own terminal.
