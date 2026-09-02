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
                text = "Shurufa 默认离线输入。系统语音 Provider 的离线能力由设备决定；也可安装本地模型。"
                textSize = 18f
            })
            addView(Button(this@MainActivity).apply {
                text = "授权语音输入"
                setOnClickListener { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10) }
            })
            addView(Switch(this@MainActivity).apply {
                text = "默认使用中文拼音"
                isChecked = preferences.getBoolean("default_pinyin", true)
                setOnCheckedChangeListener { _, enabled ->
                    preferences.edit().putBoolean("default_pinyin", enabled).apply()
                }
            })
            addView(Switch(this@MainActivity).apply {
                text = "允许在本机学习候选习惯"
                isChecked = preferences.getBoolean("learning_enabled", true)
                setOnCheckedChangeListener { _, enabled ->
                    preferences.edit().putBoolean("learning_enabled", enabled).apply()
                }
            })
            addView(Button(this@MainActivity).apply {
                text = "启用输入法"
                setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            })
            addView(Button(this@MainActivity).apply {
                text = "选择输入法"
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
