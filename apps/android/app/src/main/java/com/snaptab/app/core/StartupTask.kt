package com.snaptab.app.core

/**
 * Something to run once at launch. The live flavour has nothing to do; the demo flavour
 * uses it to seed a signed-in session so the app opens onto content instead of a sign-in
 * screen it cannot complete without a server.
 */
fun interface StartupTask {
    suspend fun run()

    companion object {
        val None = StartupTask { }
    }
}
