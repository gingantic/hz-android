package com.rhnxdev.hzplayer.core.util

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import com.rhnxdev.hzplayer.R

/**
 * Play/pause action the PiP window broadcasts back to the app. Both player activities
 * register a receiver for it, so the value must be identical on both sides — a
 * mismatch silently breaks the PiP button instead of failing to compile.
 */
const val ACTION_PIP_PLAY_PAUSE = "com.rhnxdev.hzplayer.ACTION_PIP_PLAY_PAUSE"

/**
 * PiP window params carrying a single play/pause [RemoteAction]. Null below API 26,
 * where PiP does not exist.
 */
fun buildPipParams(context: Context, isPlaying: Boolean): PictureInPictureParams? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
    val actionIntent = Intent(ACTION_PIP_PLAY_PAUSE).setPackage(context.packageName)
    val pendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        actionIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val iconRes = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
    val label = context.getString(if (isPlaying) R.string.pause else R.string.play)
    val action = RemoteAction(
        Icon.createWithResource(context, iconRes),
        label,
        label,
        pendingIntent,
    )
    return PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .setActions(listOf(action))
        .build()
}

/**
 * Receiver for [ACTION_PIP_PLAY_PAUSE]. Both player activities register one and the
 * player is a singleton, so each guards on [isInPip] — otherwise a single tap would
 * toggle play/pause twice.
 */
fun pipPlayPauseReceiver(isInPip: () -> Boolean, onToggle: () -> Unit): BroadcastReceiver =
    object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PIP_PLAY_PAUSE && isInPip()) onToggle()
        }
    }

/** Registers [receiver] for [ACTION_PIP_PLAY_PAUSE], with the API 33+ export flag. */
fun Context.registerPipReceiver(receiver: BroadcastReceiver) {
    val filter = IntentFilter(ACTION_PIP_PLAY_PAUSE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        registerReceiver(receiver, filter)
    }
}

/**
 * Enters PiP when [pipEligible] and not already in it. The two player activities
 * compute eligibility differently but enter identically.
 */
fun Activity.enterPipIfEligible(pipEligible: Boolean, isPlaying: Boolean) {
    if (!pipEligible || isInPictureInPictureMode || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val params = buildPipParams(this, isPlaying)
        ?: PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
    enterPictureInPictureMode(params)
}
