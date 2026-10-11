package com.samtex.eidreader.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

private object SecureFlag {
    private var holders = 0

    fun acquire(activity: Activity) {
        if (holders++ == 0) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(activity: Activity) {
        if (holders > 0 && --holders == 0) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Blocks screenshots, screen recording and recent-apps thumbnails while the calling screen is
 * shown (FLAG_SECURE). Reference-counted so that overlapping screens during navigation keep
 * the protection active.
 */
@Composable
fun SecureScreen() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        SecureFlag.acquire(activity)
        onDispose { SecureFlag.release(activity) }
    }
}
