package com.cpebach.voiceudptrigger

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var triggerList: LinearLayout
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())

    private class TriggerRow(val keyword: EditText, val message: EditText, val container: LinearLayout)
    private val rows = mutableListOf<TriggerRow>()

    private val poll = object : Runnable {
        override fun run() {
            statusText.text = TriggerService.status
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("config", Context.MODE_PRIVATE)

        hostInput = findViewById(R.id.host)
        portInput = findViewById(R.id.port)
        triggerList = findViewById(R.id.trigger_list)
        statusText = findViewById(R.id.status)

        hostInput.setText(prefs.getString("host", "192.168.1.100"))
        portInput.setText(prefs.getInt("port", 5000).toString())
        for ((k, m) in loadTriggers()) addRow(k, m)

        findViewById<Button>(R.id.add_trigger).setOnClickListener { addRow("", "") }
        findViewById<Button>(R.id.start).setOnClickListener { prepareAndStart() }
        findViewById<Button>(R.id.stop).setOnClickListener {
            stopService(Intent(this, TriggerService::class.java))
        }
    }

    // v1.3: triggers(JSON配列)。v1.2まで: keyword/message単一 → 変換して引き継ぐ
    private fun loadTriggers(): List<Pair<String, String>> {
        val json = prefs.getString("triggers", null)
        if (json != null) {
            val list = mutableListOf<Pair<String, String>>()
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val k = o.optString("keyword")
                    if (k.isNotEmpty()) list += k to o.optString("message")
                }
            } catch (_: Exception) {
            }
            return list
        }
        val k = prefs.getString("keyword", "コンピューター").orEmpty()
        val m = prefs.getString("message", "POWER ON").orEmpty()
        return if (k.isEmpty()) emptyList() else listOf(k to m)
    }

    private fun addRow(keyword: String, message: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val kw = EditText(this).apply {
            hint = "キーワード"
            setText(keyword)
            isSingleLine = true
            if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val msg = EditText(this).apply {
            hint = "送るメッセージ"
            setText(message)
            isSingleLine = true
            if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        kw.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        msg.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val del = Button(this).apply { text = "✕" }
        val rowRef = TriggerRow(kw, msg, row)
        del.setOnClickListener {
            triggerList.removeView(row)
            rows.remove(rowRef)
        }
        row.addView(kw)
        row.addView(msg)
        row.addView(del)
        triggerList.addView(row)
        rows += rowRef
    }

    private fun saveConfig(): Boolean {
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().toIntOrNull()
        if (host.isEmpty() || port == null || port !in 1..65535) {
            Toast.makeText(this, "IP・ポート(1-65535)を入力して", Toast.LENGTH_LONG).show()
            return false
        }
        val arr = JSONArray()
        for (r in rows) {
            val k = r.keyword.text.toString().trim()
            if (k.isEmpty()) continue
            arr.put(JSONObject().put("keyword", k).put("message", r.message.text.toString()))
        }
        if (arr.length() == 0) {
            Toast.makeText(this, "キーワードを1つ以上登録して", Toast.LENGTH_LONG).show()
            return false
        }
        prefs.edit()
            .putString("host", host)
            .putInt("port", port)
            .putString("triggers", arr.toString())
            .apply()
        return true
    }

    private fun prepareAndStart() {
        if (!saveConfig()) return
        // 必須はマイクのみ。通知(Android13+)が無くても聞けるだけなら動く
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERM)
        } else {
            startTrigger()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        var micDenied = false
        permissions.forEachIndexed { i, p ->
            if (p == Manifest.permission.RECORD_AUDIO &&
                grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED
            ) {
                micDenied = true
            }
        }
        if (micDenied) {
            Toast.makeText(this, "マイクの許可がないと聞けません", Toast.LENGTH_LONG).show()
        } else {
            // 通知が断られても開始する(通知は出ないが動作はする)
            startTrigger()
        }
    }

    private fun startTrigger() {
        val intent = Intent(this, TriggerService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    companion object {
        private const val REQ_PERM = 1
    }
}
