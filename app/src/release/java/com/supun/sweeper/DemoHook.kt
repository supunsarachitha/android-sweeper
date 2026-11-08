package com.supun.sweeper

import android.content.Context

/** Release builds: no demo list exists. The debug build has a real implementation in its own source folder. */
object DemoHook {
    @Suppress("UNUSED_PARAMETER")
    fun install(context: Context, fileHash: String?) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun installDeep(context: Context, fileHash: String?) = Unit
}
