package dev.shurufa.ime

import android.inputmethodservice.InputMethodService
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray

class ShurufaInputMethodService : InputMethodService() {
    private var handle = 0L
    private lateinit var candidates: LinearLayout
    private lateinit var keyboardRows: LinearLayout
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIsOnDevice: Boolean? = null
    private var acceptingSpeechResults = false
    private var passwordScope = false
    private var nativeRimeAvailable = false
    private var hasComposition = false
    private var nineKeyPinyin = false

    override fun onCreate() {
        super.onCreate()
        val rime = runCatching { RimeAssets.prepare(this) }.getOrElse {
            RimeAssets.Directories(
                java.io.File(filesDir, "rime/shared-unavailable"),
                java.io.File(filesDir, "rime/user-unavailable"),
            )
        }
        handle = NativeIme.create(filesDir.absolutePath, rime.shared.absolutePath, rime.user.absolutePath)
        nativeRimeAvailable = NativeIme.capabilities() and NativeIme.CAP_NATIVE_LIBRIME != 0L &&
            NativeIme.switchEngine(handle, "rime") == 0
    }

    override fun onDestroy() {
        acceptingSpeechResults = false
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        recognizerIsOnDevice = null
        if (handle != 0L) {
            NativeIme.flush(handle)
            NativeIme.destroy(handle)
            handle = 0
        }
        super.onDestroy()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val preferences = getSharedPreferences("shurufa", MODE_PRIVATE)
        pinyin = preferences.getBoolean("default_pinyin", true)
        nineKeyPinyin = preferences.getBoolean("pinyin_nine_key", false)
        NativeIme.switchEngine(handle, selectedEngine())
        val personalizedLearningAllowed =
            ((attribute?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0
        NativeIme.setPrivacy(
            handle,
            preferences.getBoolean("learning_enabled", true) && personalizedLearningAllowed,
            preferences.getBoolean("online_system_speech", false),
        )
        NativeIme.setApplicationId(handle, attribute?.packageName ?: "")
        val variation = (attribute?.inputType ?: 0) and InputType.TYPE_MASK_VARIATION
        passwordScope = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        acceptingSpeechResults = false
        speechRecognizer?.cancel()
        val scope = when {
            passwordScope -> 1
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> 2
            variation == InputType.TYPE_TEXT_VARIATION_URI -> 3
            else -> 0
        }
        NativeIme.setScope(handle, scope)
        NativeIme.command(handle, 3)
        hasComposition = false
    }

    override fun onFinishInput() {
        acceptingSpeechResults = false
        speechRecognizer?.cancel()
        if (handle != 0L) NativeIme.command(handle, 3)
        hasComposition = false
        super.onFinishInput()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (::candidates.isInitialized) {
            candidates.removeAllViews()
            showModeIndicator()
        }
        if (::keyboardRows.isInitialized) renderKeyboard()
    }

    override fun onCreateInputView(): View {
        val basePadding = dp(4)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(KEYBOARD_BACKGROUND)
            setPadding(basePadding, basePadding, basePadding, basePadding)
            setOnApplyWindowInsetsListener { view, insets ->
                val navigationBottom = if (Build.VERSION.SDK_INT >= 30) {
                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetBottom
                }
                view.setPadding(basePadding, basePadding, basePadding, basePadding + navigationBottom)
                insets
            }
        }
        candidates = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            setBackgroundColor(CANDIDATE_BACKGROUND)
            addView(
                candidates,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(candidateHeightDp())))

        keyboardRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(keyboardRows)
        renderKeyboard()
        showModeIndicator()
        root.requestApplyInsets()
        return root
    }

    private var pinyin = false

    private fun toggleEngine() {
        val previous = pinyin
        resetCompositionForModeChange()
        pinyin = !pinyin
        if (NativeIme.switchEngine(handle, selectedEngine()) != 0) {
            pinyin = previous
        } else {
            hasComposition = false
        }
        renderKeyboard()
        renderActions()
    }

    private fun selectedEngine(): String =
        PlatformPolicy.engine(pinyin, nativeRimeAvailable, nineKeyPinyin)

    private fun togglePinyinLayout() {
        if (!pinyin) return
        resetCompositionForModeChange()
        val previous = nineKeyPinyin
        nineKeyPinyin = !nineKeyPinyin
        if (NativeIme.switchEngine(handle, selectedEngine()) != 0) {
            nineKeyPinyin = previous
        } else {
            getSharedPreferences("shurufa", MODE_PRIVATE)
                .edit()
                .putBoolean("pinyin_nine_key", nineKeyPinyin)
                .apply()
        }
        renderKeyboard()
        renderActions()
    }

    private fun resetCompositionForModeChange() {
        if (handle != 0L) NativeIme.command(handle, 3)
        if (hasComposition) currentInputConnection.finishComposingText()
        hasComposition = false
    }

    private fun renderKeyboard() {
        keyboardRows.removeAllViews()
        if (pinyin && nineKeyPinyin) {
            keyboardRows.addView(t9KeyRow(listOf("1\n'" to "'", "2\nABC" to "2", "3\nDEF" to "3")))
            keyboardRows.addView(t9KeyRow(listOf("4\nGHI" to "4", "5\nJKL" to "5", "6\nMNO" to "6")))
            keyboardRows.addView(t9KeyRow(listOf("7\nPQRS" to "7", "8\nTUV" to "8", "9\nWXYZ" to "9")))
        } else {
            keyboardRows.addView(keyRow("qwertyuiop"))
            keyboardRows.addView(keyRow("asdfghjkl", sideWeight = 0.5f))
            keyboardRows.addView(keyRow("zxcvbnm", sideWeight = 1.5f))
        }
        keyboardRows.addView(functionRow())
    }

    private fun t9KeyRow(keys: List<Pair<String, String>>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        keys.forEach { (label, value) ->
            addView(key(label, labelSizeSp = 15f) { feed(value) })
        }
        layoutParams = keyboardRowParams()
    }

    private fun functionRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        if (pinyin) {
            addView(key(if (nineKeyPinyin) "26键" else "9键", 0.9f, 13f) { togglePinyinLayout() })
            addView(key("中/英", 1f, 13f) { toggleEngine() })
            addView(key("语音", 0.9f, 13f) { startSystemDictation() })
            addView(key("空格", 2f, 14f) { pressSpace() })
            addView(key("删除", 1f, 13f) { pressBackspace() })
            addView(key("回车", 1f, 13f) { pressEnter() })
        } else {
            addView(key("中/英", 1.2f, 14f) { toggleEngine() })
            addView(key("语音", 1f, 14f) { startSystemDictation() })
            addView(key("空格", 2.4f, 14f) { pressSpace() })
            addView(key("删除", 1.2f, 14f) { pressBackspace() })
            addView(key("回车", 1.2f, 14f) { pressEnter() })
        }
        layoutParams = keyboardRowParams()
    }

    private fun keyRow(keys: String, sideWeight: Float = 0f) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        if (sideWeight > 0f) addView(keySpacer(sideWeight))
        keys.forEach { character -> addView(key(character.toString())) }
        if (sideWeight > 0f) addView(keySpacer(sideWeight))
        layoutParams = keyboardRowParams()
    }

    private fun keyboardRowParams() =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(keyHeightDp()))

    private fun keyHeightDp(): Int =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 44 else 56

    private fun candidateHeightDp(): Int =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 40 else 48

    private fun keySpacer(weight: Float) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
    }

    private fun key(
        label: String,
        weight: Float = 1f,
        labelSizeSp: Float = 18f,
        action: (() -> Unit)? = null,
    ) = Button(this).apply {
        text = label
        contentDescription = label
        isAllCaps = false
        gravity = Gravity.CENTER
        includeFontPadding = false
        minWidth = 0
        minHeight = 0
        minimumWidth = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSizeSp)
        setTextColor(KEY_TEXT)
        backgroundTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(KEY_PRESSED, KEY_BACKGROUND),
        )
        stateListAnimator = null
        setOnClickListener { action?.invoke() ?: feed(label) }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
            setMargins(dp(2), dp(3), dp(2), dp(3))
        }
    }

    private fun feed(text: String) {
        if (NativeIme.feed(handle, text) == 0) renderActions()
    }

    private fun pressSpace() {
        val wasComposing = hasComposition
        command(2)
        if (PlatformPolicy.shouldInsertLiteralSpace(pinyin, wasComposing)) {
            currentInputConnection.commitText(" ", 1)
        }
    }

    private fun pressBackspace() {
        val wasComposing = hasComposition
        command(0)
        if (PlatformPolicy.shouldFallbackToEditor(wasComposing)) {
            deletePreviousEditorGrapheme()
        }
    }

    private fun deletePreviousEditorGrapheme() {
        if (passwordScope) {
            currentInputConnection.deleteSurroundingTextInCodePoints(1, 0)
            return
        }
        val before = currentInputConnection.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        val utf16Length = PlatformText.previousGraphemeUtf16Length(before)
        if (utf16Length > 0) currentInputConnection.deleteSurroundingText(utf16Length, 0)
    }

    private fun pressEnter() {
        val wasComposing = hasComposition
        command(1)
        if (PlatformPolicy.shouldFallbackToEditor(wasComposing) &&
            !sendDefaultEditorAction(false)
        ) {
            currentInputConnection.commitText("\n", 1)
        }
    }

    private fun command(value: Int) {
        if (NativeIme.command(handle, value) == 0) renderActions()
    }

    private fun renderActions() {
        val actions = JSONArray(NativeIme.actions(handle))
        candidates.removeAllViews()
        showModeIndicator()
        for (index in 0 until actions.length()) {
            val item = actions.get(index)
            if (item is String) {
                if (item == "CloseComposition") {
                    hasComposition = false
                    currentInputConnection.finishComposingText()
                }
                continue
            }
            val action = item as org.json.JSONObject
            when {
                action.has("CommitText") -> {
                    hasComposition = false
                    currentInputConnection.commitText(action.getString("CommitText"), 1)
                }
                action.has("UpdateComposition") -> {
                    val segments = action.getJSONObject("UpdateComposition").getJSONArray("segments")
                    val text = buildString {
                        for (segment in 0 until segments.length()) append(segments.getJSONObject(segment).getString("text"))
                    }
                    hasComposition = text.isNotEmpty()
                    currentInputConnection.setComposingText(text, 1)
                }
                action.has("ShowCandidates") -> {
                    showCandidates(action.getJSONArray("ShowCandidates"))
                }
            }
        }
    }

    private fun showCandidates(values: JSONArray) {
        for (index in 0 until minOf(values.length(), 5)) {
            val value = values.getJSONObject(index)
            candidates.addView(TextView(this).apply {
                text = value.getString("display_text")
                contentDescription = getString(R.string.candidate_description, text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(KEY_TEXT)
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener {
                    NativeIme.select(handle, value.getString("id"))
                    renderActions()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                )
            })
        }
    }

    private fun showModeIndicator() {
        candidates.addView(TextView(this).apply {
            text = when {
                !pinyin -> getString(R.string.mode_english)
                nineKeyPinyin -> getString(R.string.mode_pinyin_nine_key)
                else -> getString(R.string.mode_pinyin_full_keyboard)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(MODE_TEXT)
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
        })
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()

    private fun startSystemDictation() {
        val allowNetwork = getSharedPreferences("shurufa", MODE_PRIVATE)
            .getBoolean("online_system_speech", false)
        val onDeviceAvailable = Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        if (!PlatformPolicy.mayStartSpeech(
                passwordScope,
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                onDeviceAvailable,
                allowNetwork,
            )
        ) return
        val recognizer = obtainSpeechRecognizer(allowNetwork) ?: return
        if (speechRecognizer !== recognizer) {
            speechRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onError(error: Int) { acceptingSpeechResults = false }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onPartialResults(results: Bundle?) = deliverSpeech(results, false)
                override fun onResults(results: Bundle?) = deliverSpeech(results, true)
            })
        }
        val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try {
            acceptingSpeechResults = true
            recognizer.startListening(request)
        } catch (_: SecurityException) {
            acceptingSpeechResults = false
            // The companion settings activity must obtain RECORD_AUDIO first.
        } catch (_: IllegalStateException) {
            acceptingSpeechResults = false
        }
    }

    private fun obtainSpeechRecognizer(allowNetwork: Boolean): SpeechRecognizer? {
        val useOnDevice = Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        if (!useOnDevice && !allowNetwork) return null
        if (speechRecognizer != null && recognizerIsOnDevice == useOnDevice) {
            return speechRecognizer
        }
        acceptingSpeechResults = false
        speechRecognizer?.destroy()
        recognizerIsOnDevice = useOnDevice
        return if (useOnDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
    }

    private fun deliverSpeech(results: Bundle?, finalResult: Boolean) {
        if (!acceptingSpeechResults || passwordScope) return
        val values = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        val text = values.firstOrNull() ?: return
        val confidence = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull() ?: Float.NaN
        NativeIme.speechResult(handle, text, java.util.Locale.getDefault().toLanguageTag(), confidence, finalResult)
        renderActions()
        if (finalResult) acceptingSpeechResults = false
    }

    private companion object {
        const val KEYBOARD_BACKGROUND = 0xFFF1F3F6.toInt()
        const val CANDIDATE_BACKGROUND = 0xFFFFFFFF.toInt()
        const val KEY_BACKGROUND = 0xFFFFFFFF.toInt()
        const val KEY_PRESSED = 0xFFD8E3FF.toInt()
        const val KEY_TEXT = Color.BLACK
        const val MODE_TEXT = 0xFF3454D1.toInt()
    }
}
