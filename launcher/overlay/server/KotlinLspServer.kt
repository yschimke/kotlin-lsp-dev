// Copyright 2026 kotlin-lsp-dev contributors. Use of this source code is governed by the Apache 2.0 license.

package overlay.server

import java.lang.reflect.InvocationTargetException

/**
 * Entry point for a Kotlin LSP distribution enhanced by kotlin-lsp-dev.
 *
 * The official launcher still performs all JVM and IntelliJ Platform bootstrapping. It loads this
 * class instead of the product entry point; after this deliberately small seam, control returns to
 * the product. Overlay features are then discovered by the product's normal
 * LanguageServerExtension ServiceLoader path.
 */
object KotlinLspServer {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val upstreamMain = Class.forName("com.intellij.ls.server.MainImpl")
                .getMethod("main", Array<String>::class.java)
            upstreamMain.invoke(null, args as Any)
        } catch (wrapped: InvocationTargetException) {
            throw wrapped.targetException
        }
    }
}
