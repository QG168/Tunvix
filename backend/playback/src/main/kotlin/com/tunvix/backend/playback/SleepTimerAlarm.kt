package com.tunvix.backend.playback

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Wake-up fallback for an in-memory listening session; never creates or resumes a player. */
internal object SleepTimerAlarm {
    fun schedule(context: Context, deadline: Long?) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, SleepTimerReceiver::class.java).putExtra("deadline", deadline ?: 0L)
        val pending = PendingIntent.getBroadcast(context, 31, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        if (deadline != null) alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, pending)
        else pending.cancel()
    }
}

class SleepTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PlaybackRuntime.existing()?.expireSleepTimer(intent.getLongExtra("deadline", 0L))
    }
}
