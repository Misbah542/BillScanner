package com.snaptab.app.core

import com.snaptab.app.BuildConfig

/**
 * The handful of places where the build variant changes what the app can do, in
 * one object so the answer is never derived twice and never derived differently.
 */
object BuildFlags {

    /** True in the `demo` flavour, which serves everything from a seeded fake API. */
    val demo: Boolean get() = BuildConfig.DEMO_MODE

    /**
     * Whether reading bank alerts off the phone is possible in this build at all.
     *
     * The demo flavour removes RECEIVE_SMS and READ_SMS from its manifest — it is
     * the build that gets handed round, and a demo that cannot read your messages
     * is easier to accept than one that promises not to. Requesting a permission
     * the manifest does not declare is denied instantly and without a dialog, so
     * the toggle has to be hidden rather than merely left off: a switch that
     * silently refuses to move looks like a bug, and explaining why is cheaper
     * than being asked.
     *
     * The seeded alerts are still in the demo inbox. They come from the fake API.
     */
    val smsReadingAvailable: Boolean get() = !BuildConfig.DEMO_MODE
}
