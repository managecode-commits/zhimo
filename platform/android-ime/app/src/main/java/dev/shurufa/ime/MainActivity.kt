package dev.shurufa.ime

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("shurufa", MODE_PRIVATE)
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
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
            addView(TextView(this@MainActivity).apply {
                setText(R.string.handwriting_privacy)
                textSize = 16f
                android.text.util.Linkify.addLinks(this, android.text.util.Linkify.WEB_URLS)
            })
            addView(Switch(this@MainActivity).apply {
                text = "实验性离线字形识别（重新打开手写生效）"
                isChecked = preferences.getBoolean("handwriting_image_experimental", true)
                setOnCheckedChangeListener { _, enabled ->
                    preferences.edit().putBoolean("handwriting_image_experimental", enabled).apply()
                }
            })
            addView(TextView(this@MainActivity).apply {
                text = "离线字形模型默认开启，已内置，无需联网；横向连写不限固定格，可调整字界，也可切换单字。自动分字仍在质量验收中，关闭可返回原轨迹模型。首次加载会增加内存使用。"
                textSize = 14f
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
                var mode = OneHandMode.fromStored(preferences.getString("one_hand_mode", null))
                fun modeLabel(): String = when (mode) {
                    OneHandMode.CENTER -> "居中"
                    OneHandMode.LEFT -> "左手"
                    OneHandMode.RIGHT -> "右手"
                }
                text = getString(R.string.keyboard_position, modeLabel())
                setOnClickListener {
                    mode = mode.next()
                    preferences.edit().putString("one_hand_mode", mode.storedValue).apply()
                    text = getString(R.string.keyboard_position, modeLabel())
                }
            })
            addView(Button(this@MainActivity).apply {
                var size = KeyboardSize.fromStored(preferences.getString("keyboard_size", null))
                fun sizeLabel(): String = when (size) {
                    KeyboardSize.COMPACT -> "紧凑"
                    KeyboardSize.STANDARD -> "标准"
                    KeyboardSize.TALL -> "加高"
                }
                text = getString(R.string.keyboard_size, sizeLabel())
                setOnClickListener {
                    size = size.next()
                    preferences.edit().putString("keyboard_size", size.storedValue).apply()
                    text = getString(R.string.keyboard_size, sizeLabel())
                }
            })
            addView(Switch(this@MainActivity).apply {
                setText(R.string.high_contrast)
                isChecked = preferences.getBoolean("high_contrast", false)
                setOnCheckedChangeListener { _, enabled ->
                    preferences.edit().putBoolean("high_contrast", enabled).apply()
                }
            })
            addView(Button(this@MainActivity).apply {
                var size = KeyboardTextSize.fromStored(preferences.getString("keyboard_text_size", null))
                fun label(): String = when (size) {
                    KeyboardTextSize.STANDARD -> "标准"
                    KeyboardTextSize.LARGE -> "大字"
                    KeyboardTextSize.EXTRA_LARGE -> "特大"
                }
                text = getString(R.string.keyboard_text_size, label())
                setOnClickListener {
                    size = size.next()
                    preferences.edit().putString("keyboard_text_size", size.name).apply()
                    text = getString(R.string.keyboard_text_size, label())
                }
            })
            addView(Switch(this@MainActivity).apply {
                setText(R.string.haptic_feedback)
                isChecked = preferences.getBoolean("haptic_enabled", true)
                setOnCheckedChangeListener { _, enabled ->
                    preferences.edit().putBoolean("haptic_enabled", enabled).apply()
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
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            // Permission is persisted by Android; no input data is collected here.
        }
    }
}
