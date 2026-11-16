package com.cpebach.voiceudptrigger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * マイクロホンを聞き続けて、キーワードを検出したらUDPを送るフォアグラウンドサービス。
 * 設定は SharedPreferences("config")。
 */
class TriggerService : Service(), RecognitionListener {

    companion object {
        private const val CHANNEL_ID = "voice_udp_trigger"
        private const val NOTIF_ID = 42
        private const val COOLDOWN_MS = 2000L

        @Volatile
        var status = "停止中"
    }

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastFired = 0L

    private var targetHost = ""
    private var targetPort = 5000
    private var keyword = ""
    private var message = ""

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "音声トリガー", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        targetHost = prefs.getString("host", "") ?: ""
        targetPort = prefs.getInt("port", 5000)
        keyword = prefs.getString("keyword", "") ?: ""
        message = prefs.getString("message", "") ?: ""

        showNotification("モデル読み込み中…")
        setStatus("モデル読み込み中…")

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voiceudp:listen").apply {
                setReferenceCounted(false)
                acquire(8 * 60 * 60 * 1000L) // 8時間で自動解放
            }

        thread {
            try {
                val m = model ?: Model(StorageService.sync(this, "model", "model")).also { model = it }
                val svc = SpeechService(Recognizer(m, 16000.0f), 16000.0f)
                speechService = svc
                svc.startListening(this@TriggerService)
                setStatus("聞いている… 「$keyword」 → $targetHost:$targetPort")
            } catch (e: Exception) {
                Log.e("TriggerService", "初期化に失敗", e)
                setStatus("エラー: ${e.message}")
                stopSelf()
            }
        }
        return START_STICKY
    }

    //region 認識コールバック
    override fun onPartialResult(hypothesis: String?) {
        val text = parseText(hypothesis)
        if (text.isNotEmpty()) setStatus("聞き取り中… $text")
    }

    override fun onResult(hypothesis: String?) {
        val text = parseText(hypothesis)
        if (text.isNotEmpty()) {
            val now = System.currentTimeMillis()
            if (keyword.isNotEmpty() && text.contains(keyword, ignoreCase = true) &&
                now - lastFired >= COOLDOWN_MS
            ) {
                lastFired = now
                fireUdp(text)
            } else {
                setStatus("聞いた: $text")
            }
        }
        resumeListening()
    }

    override fun onFinalResult(hypothesis: String?) = Unit

    override fun onError(e: Exception?) {
        Log.e("TriggerService", "認識エラー", e)
        setStatus("認識エラー: ${e?.message}")
        resumeListening()
    }

    override fun onTimeout() = resumeListening()
    //endregion

    private fun resumeListening() {
        mainHandler.postDelayed({ speechService?.startListening(this@TriggerService) }, 200)
    }

    private fun parseText(hypothesis: String?): String = try {
        hypothesis?.let { JSONObject(it).optString("text", "") } ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun fireUdp(heard: String) {
        setStatus("送信: 「$message」 → $targetHost:$targetPort")
        showNotification("検出「$heard」送信済み")
        thread {
            val data = message.toByteArray(Charsets.UTF_8)
            val sock = DatagramSocket()
            try {
                sock.send(DatagramPacket(data, data.size, InetAddress.getByName(targetHost), targetPort))
                Log.i("TriggerService", "UDP送信完了 $targetHost:$targetPort")
            } catch (e: Exception) {
                Log.e("TriggerService", "UDP送信失敗", e)
                setStatus("送信失敗: ${e.message}")
            } finally {
                sock.close()
            }
        }
    }

    private fun setStatus(text: String) {
        status = text
        mainHandler.post { showNotification(text) }
    }

    private fun showNotification(text: String) {
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice UDP Trigger")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        status = "停止中"
        try {
            speechService?.stop()
        } catch (_: Exception) {
        }
        speechService = null
        try {
            model?.close()
        } catch (_: Exception) {
        }
        model = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
