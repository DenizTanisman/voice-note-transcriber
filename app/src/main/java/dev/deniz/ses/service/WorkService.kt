package dev.deniz.ses.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.deniz.ses.R

/**
 * Held while the whisper queue has work. With the screen off Samsung moves plain background processes to the
 * "restricted" cpuset (little + two mid cores, ~3× slower); a foreground service gets the "foreground" set.
 */
class WorkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notification.Builder(this, Notifier.CH_WORK)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Sesli mesaj yazıya dökülüyor…")
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        try { startForeground(NID, n, type) } catch (e: Exception) { Log.w(TAG, "startForeground", e); stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "SesWork"
        private const val NID = 1

        /** Best effort: if the system refuses (background start limits), the work just runs slower. */
        fun start(ctx: Context) = runCatching { ctx.startForegroundService(Intent(ctx, WorkService::class.java)) }
            .onFailure { Log.w(TAG, "start refused: $it") }.isSuccess

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, WorkService::class.java)) }
    }
}
