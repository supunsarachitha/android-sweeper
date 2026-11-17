package com.supun.sweeper

import android.content.Intent
import com.supun.sweeper.storage.FileOpener

/** Test helper: captures the intents the app would start for "open file", so no real app gets launched. */
class OpenCapture {
    val intents = mutableListOf<Intent>()

    fun start() {
        FileOpener.interceptor = { intents.add(it) }
    }

    fun stop() {
        FileOpener.interceptor = null
    }

    /** The ACTION_VIEW intent inside the chooser, or the intent itself when it is not wrapped. */
    @Suppress("DEPRECATION")
    fun viewIntent(index: Int): Intent {
        val outer = intents[index]
        return if (outer.action == Intent.ACTION_CHOOSER) outer.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!! else outer
    }
}
