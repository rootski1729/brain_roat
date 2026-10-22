package com.brainrot.pet

import android.util.Log

/**
 * One log format for the whole app: `event key=value ...` under a single tag, so
 * `adb logcat -s BrainRot` explains what the pet did and why. Never log user-typed text, brain
 * names or anything read from other apps' screens; package names and counts only.
 */
object BrainLog {
    const val TAG = "BrainRot"

    fun d(event: String, vararg fields: Pair<String, Any?>) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, format(event, fields))
    }

    fun i(event: String, vararg fields: Pair<String, Any?>) {
        Log.i(TAG, format(event, fields))
    }

    fun w(event: String, error: Throwable? = null, vararg fields: Pair<String, Any?>) {
        Log.w(TAG, format(event, arrayOf(*fields, *errorFields(error))), error)
    }

    fun e(event: String, error: Throwable? = null, vararg fields: Pair<String, Any?>) {
        Log.e(TAG, format(event, arrayOf(*fields, *errorFields(error))), error)
    }

    private fun errorFields(error: Throwable?): Array<Pair<String, Any?>> =
        if (error == null) emptyArray() else arrayOf("error" to error.javaClass.simpleName)

    private fun format(event: String, fields: Array<out Pair<String, Any?>>): String =
        if (fields.isEmpty()) event else event + fields.joinToString(" ", prefix = " ") { "${it.first}=${it.second}" }
}
