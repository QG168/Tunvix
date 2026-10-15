package com.tunvix.backend.playback

import android.app.PendingIntent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Bundle
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.DefaultMediaNotificationProvider

/** One process-wide player, owned by the service rather than the activity. Main-thread only. */
object PlaybackRuntime {
    private var current: Media3PlaybackController? = null

    fun controller(context: Context): Media3PlaybackController = obtain(context).also { it.uiOwned = true }

    internal fun obtain(context: Context): Media3PlaybackController {
        val app = context.applicationContext
        return current ?: Media3PlaybackController.create(app).also { controller ->
            current = controller
            controller.onDisposed = { if (current === controller) current = null }
            controller.scheduleSleepAlarm = { deadline -> SleepTimerAlarm.schedule(app, deadline) }
            controller.beforePlay = {
                // Playback is initiated by a visible activity, or an existing media session.
                app.startService(Intent(app, TunvixPlaybackService::class.java))
            }
        }
    }

    internal fun existing(): Media3PlaybackController? = current

    internal fun release(controller: Media3PlaybackController) {
        controller.stop()
        controller.beforeResume = null
        controller.serviceOwned = false
        if (!controller.uiOwned) controller.dispose()
    }
}

@OptIn(UnstableApi::class)
class TunvixPlaybackService : MediaSessionService() {
    private lateinit var controller: Media3PlaybackController
    private var session: MediaSession? = null
    private val stopCommand = SessionCommand("com.tunvix.STOP_PLAYBACK",Bundle.EMPTY)

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(1001).setChannelId("tunvix_playback").build())
        controller = PlaybackRuntime.obtain(this)
        controller.serviceOwned = true
        controller.beforeResume = ::ensureForegroundForResume
        controller.onSleepTimerExpired = ::stopPlaybackAndService
        // Route system buttons through the same pause/focus/retry state machine as the UI.
        val controls = object : ForwardingSimpleBasePlayer(controller.sessionPlayer) {
            fun refreshCommands() = invalidateState()
            override fun getState(): SimpleBasePlayer.State {
                val state = super.getState()
                val commands = state.availableCommands.buildUpon()
                val skipCommands = intArrayOf(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                if (controller.canSkipChannel) commands.addAll(*skipCommands) else commands.removeAll(*skipCommands)
                return state.buildUpon().setAvailableCommands(commands.build()).build()
            }
            override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
                if (playWhenReady) controller.resume() else controller.pause()
                return Futures.immediateVoidFuture()
            }
            override fun handleStop(): ListenableFuture<*> {
                stopPlaybackAndService()
                return Futures.immediateVoidFuture()
            }
            override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
                when (seekCommand) {
                    Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> controller.skipChannel(-1)
                    Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> controller.skipChannel(1)
                    else -> return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
                }
                return Futures.immediateVoidFuture()
            }
        }
        controller.onChannelQueueChanged = controls::refreshCommands
        val builder = MediaSession.Builder(this, controls)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, info: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    if(!info.isTrusted && info.packageName!=packageName)return MediaSession.ConnectionResult.reject()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(stopCommand).build())
                        .build()
                }
                override fun onCustomCommand(session: MediaSession, info: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if(command.customAction!=stopCommand.customAction)return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                    stopPlaybackAndService()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .setMediaButtonPreferences(listOf(
                CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                    .setDisplayName("上一台").setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS).build(),
                CommandButton.Builder(CommandButton.ICON_NEXT)
                    .setDisplayName("下一台").setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT).build(),
                CommandButton.Builder(CommandButton.ICON_STOP)
                    .setDisplayName("停止播放").setSessionCommand(stopCommand).build(),
            ))
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            launch.putExtra("openPlayback", true)
            builder.setSessionActivity(PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        session = builder.build().also { addSession(it) }
    }

    // A background system resume must establish foreground ownership before requesting audio
    // focus on Android 15+. MediaSessionService replaces this bootstrap with the media controls.
    private fun ensureForegroundForResume() {
        if(controller.snapshot.value.channelId==null)return
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("tunvix_playback", "媒体播放",NotificationManager.IMPORTANCE_LOW))
        val notification=Notification.Builder(this,"tunvix_playback")
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Tunvix")
            .setContentText("正在恢复播放").setCategory(Notification.CATEGORY_TRANSPORT)
            .setContentIntent(session?.sessionActivity).setOngoing(true).build()
        startForeground(1001,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        session?.takeIf { controllerInfo.isTrusted || controllerInfo.packageName == packageName }

    private fun stopPlaybackAndService() {
        controller.stop() // Includes session-only audio reset.
        session?.release()
        session=null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) = stopPlaybackAndService()

    override fun onDestroy() {
        session?.release()
        session = null
        controller.onChannelQueueChanged = null
        controller.onSleepTimerExpired = null
        PlaybackRuntime.release(controller)
        super.onDestroy()
    }
}
