package com.TapLinkX3.app

import android.util.Log

/**
 * Utility object for debug logging that only logs in debug builds.
 * All Log.d calls should use this to ensure no debug logs in production.
 */
object DebugLog {
    private val isDebug = BuildConfig.DEBUG

    fun d(tag: String, message: String) {
        if (isDebug) {
            Log.d(tag, message)
        }
    }

    fun d(tag: String, messageProvider: () -> String) {
        if (isDebug) {
            Log.d(tag, messageProvider())
        }
    }

    fun w(tag: String, message: String) {
        if (isDebug) {
            Log.w(tag, message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        // Error logs are always shown, even in production
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
}
