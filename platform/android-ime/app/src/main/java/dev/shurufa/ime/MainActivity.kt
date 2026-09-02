package dev.shurufa.ime

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("shurufa", MODE_PRIVATE)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            addView(TextView(this@MainActivity).apply {
                setText(R.string.settings_summary)
                textSize = 18f
            })
            addView(Button(this@MainActivity).apply {
                setText(R.string.grant_microphone)
                setOnClickListener { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10) }
            })
            addView(Switch(this@MainActivity).apply {
                setText(R.string.default_pinyin)
                isChecked = preferences.getBoolean("default_pinyin", true)
                setOnCheckedChangeListener { _, enabled ->
                    preferences.edit().putBoolean("default_pinyin", enabled).apply()
                }
            })
            addView(Switch(this@MainActivity).apply {
                setText(R.string.local_learning)
                isChecked = preferences.getBoolean("learning_enabled", true)
                setOnCheckedChangeListener { _, enabled ->
                    preferences.edit().putBoolean("learning_enabled", enabled).apply()
                }
            })
            addView(Switch(this@MainActivity).apply {
                setText(R.string.allow_online_speech)
                isChecked = preferences.getBoolean("online_system_speech", false)
                setOnCheckedChangeListener { _, enabled ->
                    preferences.edit().putBoolean("online_system_speech", enabled).apply()
                }
            })
            addView(Button(this@MainActivity).apply {
                setText(R.string.enable_ime)
                setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            })
            addView(Button(this@MainActivity).apply {
                setText(R.string.select_ime)
                setOnClickListener { getSystemService(android.view.inputmethod.InputMethodManager::class.java).showInputMethodPicker() }
            })
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            // Permission is persisted by Android; no input data is collected here.
        }
    }
}
