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
import org.json.JSONArray
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

        // エラー時は停止中に上書きしないためのフラグ
        @Volatile
        private var failed = false

        fun resetError() {
            failed = false
        }
    }

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastFired = 0L

    private data class Trigger(val keyword: String, val message: String)

    private var targetHost = ""
    private var targetPort = 5000
    private var triggers: List<Trigger> = emptyList()

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
        resetError()
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        targetHost = prefs.getString("host", "") ?: ""
        targetPort = prefs.getInt("port", 5000)
        triggers = parseTriggers(prefs)

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
                val label = when {
                    triggers.isEmpty() -> "⚠ トリガー未設定"
                    triggers.size == 1 -> "「${triggers[0].keyword}」"
                    else -> "${triggers.size}件のトリガー"
                }
                setStatus("聞いている… $label → $targetHost:$targetPort")
            } catch (e: Exception) {
                Log.e("TriggerService", "初期化に失敗", e)
                failed = true
                setStatus("初期化エラー: ${e.javaClass.simpleName}: ${e.message}")
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
            // 登録順に走査し、最初に一致したトリガーのメッセージを送る
            val hit = triggers.firstOrNull {
                it.keyword.isNotEmpty() && text.contains(it.keyword, ignoreCase = true)
            }
            if (hit != null && now - lastFired >= COOLDOWN_MS) {
                lastFired = now
                fireUdp(hit.message, text)
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

    // v1.3: triggers(JSON配列)。v1.2までの keyword/message 単一設定も引き継ぐ
    private fun parseTriggers(prefs: android.content.SharedPreferences): List<Trigger> {
        val json = prefs.getString("triggers", null)
        if (json != null) {
            val list = mutableListOf<Trigger>()
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val k = o.optString("keyword")
                    if (k.isNotEmpty()) list += Trigger(k, o.optString("message"))
                }
            } catch (e: Exception) {
                Log.e("TriggerService", "triggers解析失敗", e)
            }
            return list
        }
        val k = prefs.getString("keyword", "").orEmpty()
        val m = prefs.getString("message", "").orEmpty()
        return if (k.isEmpty()) emptyList() else listOf(Trigger(k, m))
    }

    private fun fireUdp(message: String, heard: String) {
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
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setPriority(Notification.PRIORITY_LOW)
                .setShowWhen(false)
        }
        val notif = builder
            .setContentTitle("Voice UDP Trigger")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            // 権限・OS制限でフォアグラウンド化に失敗しても原因を残す
            Log.e("TriggerService", "startForeground失敗", e)
            failed = true
            status = "起動失敗: ${e.javaClass.simpleName}: ${e.message}"
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!failed) status = "停止中"
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
