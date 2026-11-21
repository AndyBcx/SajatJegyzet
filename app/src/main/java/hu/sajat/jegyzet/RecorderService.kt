package hu.sajat.jegyzet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RecState {
    val recording = MutableStateFlow(false)
    val startedAt = MutableStateFlow(0L)
}

class RecorderService : Service() {
    private var rec: MediaRecorder? = null
    private var file: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRec()
            stopSelf()
        } else {
            startRec()
        }
        return START_NOT_STICKY
    }

    private fun startRec() {
        if (rec != null) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Felvétel", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(
            this, 0, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Felvétel folyamatban")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(0, "Leállítás", stopPi).build())
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        val name = "rec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".m4a"
        val f = File(Store(this).recDir, name)
        try {
            rec = MediaRecorder(this).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(f)
                prepare()
                start()
            }
            file = f
            RecState.startedAt.value = System.currentTimeMillis()
            RecState.recording.value = true
        } catch (e: Exception) {
            rec?.release(); rec = null
            f.delete()
            stopSelf()
        }
    }

    private fun stopRec() {
        val r = rec ?: return
        try { r.stop() } catch (_: RuntimeException) { file?.delete() }
        r.release()
        rec = null
        file = null
        RecState.recording.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopRec()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "hu.sajat.jegyzet.STOP"
        private const val CH = "rec"
    }
}
