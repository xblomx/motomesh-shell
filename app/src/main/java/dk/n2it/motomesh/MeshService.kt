// Moto Mesh Shell v1.0.1 · 2026-07-19
// The legal keeper of background mic + location. While this notification lives,
// Android lets the WebView's WebRTC capture and geolocation continue behind any app.
// It also puts the device in COMMUNICATION mode with voice-call audio focus,
// so Google Maps DUCKS around the intercom instead of silencing it — and the
// July "owner mic asymmetry" dies natively.
package dk.n2it.motomesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

class MeshService : Service() {

    private var wake: PowerManager.WakeLock? = null
    private var focus: AudioFocusRequest? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        acquireWake()
        enterCommMode()
    }

    private fun startAsForeground() {
        val chId = "mesh"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(chId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(chId, "Moto Mesh ride", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(this, chId)
            .setContentTitle("Moto Mesh · intercom active")
            .setContentText("Voice and position stay live · tap to open")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                1, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else startForeground(1, n)
    }

    private fun acquireWake() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "motomesh:mesh")
                .apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {}
    }

    private fun enterCommMode() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 26) {
                focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(true)
                    .build()
                am.requestAudioFocus(focus!!)
            }
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf() // swiping the app away ends the ride keeper deliberately
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_NORMAL
            if (Build.VERSION.SDK_INT >= 26) focus?.let { am.abandonAudioFocusRequest(it) }
        } catch (_: Exception) {}
        try { wake?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
