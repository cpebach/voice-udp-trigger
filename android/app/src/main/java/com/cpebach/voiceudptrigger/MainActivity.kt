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
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var keywordInput: EditText
    private lateinit var messageInput: EditText
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())

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
        keywordInput = findViewById(R.id.keyword)
        messageInput = findViewById(R.id.message)
        statusText = findViewById(R.id.status)

        hostInput.setText(prefs.getString("host", "192.168.1.100"))
        portInput.setText(prefs.getInt("port", 5000).toString())
        keywordInput.setText(prefs.getString("keyword", "コンピューター"))
        messageInput.setText(prefs.getString("message", "POWER ON"))

        findViewById<Button>(R.id.start).setOnClickListener { prepareAndStart() }
        findViewById<Button>(R.id.stop).setOnClickListener {
            stopService(Intent(this, TriggerService::class.java))
        }
    }

    private fun saveConfig(): Boolean {
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().toIntOrNull()
        val keyword = keywordInput.text.toString().trim()
        val message = messageInput.text.toString()
        if (host.isEmpty() || port == null || port !in 1..65535 || keyword.isEmpty()) {
            Toast.makeText(this, "IP・ポート(1-65535)・キーワードを入力して", Toast.LENGTH_LONG).show()
            return false
        }
        prefs.edit()
            .putString("host", host)
            .putInt("port", port)
            .putString("keyword", keyword)
            .putString("message", message)
            .apply()
        return true
    }

    private fun prepareAndStart() {
        if (!saveConfig()) return
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
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
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startTrigger()
        } else {
            Toast.makeText(this, "マイクの許可がないと聞けません", Toast.LENGTH_LONG).show()
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
