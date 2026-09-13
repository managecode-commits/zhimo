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
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
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
    private data class EditorIdentity(
        val packageName: String?,
        val fieldId: Int,
        val inputType: Int,
    )

    private var handle = 0L
    private lateinit var candidates: LinearLayout
    private lateinit var keyboardRows: LinearLayout
    private var spaceKey: Button? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIsOnDevice: Boolean? = null
    private var acceptingSpeechResults = false
    private var passwordScope = false
    private var nativeRimeAvailable = false
    private var hasComposition = false
    private var nineKeyPinyin = false
    private var keyboardPage = KeyboardPage.TEXT
    private var chineseSymbols = true
    private var keyboardStateInitialized = false
    private var activeEditorIdentity: EditorIdentity? = null
    private var uppercase = false

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
        val editorIdentity = EditorIdentity(
            packageName = attribute?.packageName,
            fieldId = attribute?.fieldId ?: 0,
            inputType = attribute?.inputType ?: InputType.TYPE_CLASS_TEXT,
        )
        val initializeKeyboardState =
            PlatformPolicy.shouldInitializeKeyboardState(
                restarting = restarting,
                stateInitialized = keyboardStateInitialized,
                sameEditor = editorIdentity == activeEditorIdentity,
            )
        if (initializeKeyboardState) {
            pinyin = preferences.getBoolean("default_pinyin", true)
            nineKeyPinyin = preferences.getBoolean("pinyin_nine_key", false)
            keyboardPage = PlatformPolicy.initialKeyboardPage(attribute?.inputType ?: InputType.TYPE_CLASS_TEXT)
            chineseSymbols = pinyin
            uppercase = false
            keyboardStateInitialized = true
        }
        activeEditorIdentity = editorIdentity
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
        if (initializeKeyboardState) {
            NativeIme.command(handle, 3)
            hasComposition = false
        }
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
            setBackgroundColor(keyboardBackground())
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
            setBackgroundColor(candidateBackground())
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
            uppercase = false
        }
        renderKeyboard()
        renderActions()
    }

    private fun showKeyboardPage(page: KeyboardPage) {
        if (keyboardPage == page) return
        resetCompositionForModeChange()
        keyboardPage = page
        if (page == KeyboardPage.SYMBOL) chineseSymbols = pinyin
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
        if (hasComposition) currentInputConnection.setComposingText("", 1)
        currentInputConnection.finishComposingText()
        if (handle != 0L) NativeIme.command(handle, 3)
        hasComposition = false
    }

    private fun renderKeyboard() {
        keyboardRows.removeAllViews()
        spaceKey = null
        when (keyboardPage) {
            KeyboardPage.NUMBER -> renderNumberKeyboard()
            KeyboardPage.SYMBOL -> renderSymbolKeyboard()
            KeyboardPage.TEXT -> renderTextKeyboard()
        }
        keyboardRows.addView(functionRow())
    }

    private fun renderTextKeyboard() {
        keyboardRows.addView(textToolbar())
        if (pinyin && nineKeyPinyin) {
            keyboardRows.addView(t9KeyRow(listOf("1\n'" to "'", "2\nABC" to "2", "3\nDEF" to "3")))
            keyboardRows.addView(t9KeyRow(listOf("4\nGHI" to "4", "5\nJKL" to "5", "6\nMNO" to "6")))
            keyboardRows.addView(t9KeyRow(listOf("7\nPQRS" to "7", "8\nTUV" to "8", "9\nWXYZ" to "9")))
        } else {
            val transform: (Char) -> String = { character ->
                if (!pinyin && uppercase) character.uppercase() else character.toString()
            }
            keyboardRows.addView(keyRow("qwertyuiop", transform = transform))
            keyboardRows.addView(keyRow("asdfghjkl", sideWeight = 0.5f, transform = transform))
            keyboardRows.addView(keyRow("zxcvbnm", sideWeight = 1.5f, transform = transform))
        }
    }

    private fun textToolbar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        if (!pinyin) {
            addView(key(if (uppercase) "⇧ 大写" else "⇧", 0.9f, 12f) {
                uppercase = !uppercase
                renderKeyboard()
            })
        } else {
            addView(key(if (nineKeyPinyin) "26键" else "9键", 0.9f, 12f) { togglePinyinLayout() })
        }
        addView(key("123", 0.8f, 12f) { showKeyboardPage(KeyboardPage.NUMBER) })
        addView(key("符号", 0.9f, 12f) { showKeyboardPage(KeyboardPage.SYMBOL) })
        val languageTarget = if (pinyin) "切英" else "切中"
        val languageDescription = if (pinyin) "切换到英文输入" else "切换到中文拼音"
        addView(key(languageTarget, 0.9f, 14f, languageDescription) { toggleEngine() })
        addView(key("语音", 0.9f, 12f) { startSystemDictation() })
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 40 else 44),
        )
    }

    private fun renderNumberKeyboard() {
        keyboardRows.addView(literalKeyRow(listOf("1", "2", "3", "4", "5")))
        keyboardRows.addView(literalKeyRow(listOf("6", "7", "8", "9", "0")))
        keyboardRows.addView(literalKeyRow(listOf(".", ",", "?", "!", "@", "#")))
    }

    private fun renderSymbolKeyboard() {
        val rows = if (chineseSymbols) CHINESE_SYMBOL_ROWS else ENGLISH_SYMBOL_ROWS
        rows.forEach { keyboardRows.addView(literalKeyRow(it)) }
    }

    private fun t9KeyRow(keys: List<Pair<String, String>>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        keys.forEach { (label, value) ->
            addView(key(label, labelSizeSp = 15f) { feed(value) })
        }
        layoutParams = keyboardRowParams()
    }

    private fun literalKeyRow(values: List<String>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        values.forEach { value ->
            addView(key(value, labelSizeSp = 16f) { commitLiteral(value) })
        }
        layoutParams = keyboardRowParams()
    }

    private fun functionRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        when (keyboardPage) {
            KeyboardPage.TEXT -> addTextFunctionKeys()
            KeyboardPage.NUMBER -> {
                addView(key("ABC", 1f, 13f) { showKeyboardPage(KeyboardPage.TEXT) })
                addView(key("符号", 1f, 13f) { showKeyboardPage(KeyboardPage.SYMBOL) })
                addView(key("空格", 2.2f, 14f) { pressSpace() })
                addView(key("删除", 1.1f, 13f) { pressBackspace() })
                addView(key("回车", 1.1f, 13f) { pressEnter() })
            }
            KeyboardPage.SYMBOL -> {
                addView(key("ABC", 0.9f, 13f) { showKeyboardPage(KeyboardPage.TEXT) })
                addView(key("123", 0.9f, 13f) { showKeyboardPage(KeyboardPage.NUMBER) })
                addView(key(if (chineseSymbols) "英符" else "中符", 0.9f, 13f) {
                    chineseSymbols = !chineseSymbols
                    renderKeyboard()
                    renderActions()
                })
                addView(key("空格", 1.8f, 14f) { pressSpace() })
                addView(key("删除", 1f, 13f) { pressBackspace() })
                addView(key("回车", 1f, 13f) { pressEnter() })
            }
        }
        layoutParams = keyboardRowParams()
    }

    private fun LinearLayout.addTextFunctionKeys() {
        addView(key(if (pinyin) "，" else ",", 0.75f, 15f) { commitLiteral(if (pinyin) "，" else ",") })
        if (pinyin) addView(key("'", 0.65f, 16f) { feed("'") })
        spaceKey = key(spaceKeyLabel(), 2.4f, 14f) { pressSpace() }
        addView(spaceKey)
        addView(key(if (pinyin) "。" else ".", 0.75f, 15f) { commitLiteral(if (pinyin) "。" else ".") })
        addView(key("⌫", 0.8f, 18f) { pressBackspace() })
        addView(key(enterKeyLabel(), 1f, 12f) { pressEnter() })
    }

    private fun enterKeyLabel(): String = when (currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)) {
        EditorInfo.IME_ACTION_SEARCH -> "搜索"
        EditorInfo.IME_ACTION_SEND -> "发送"
        EditorInfo.IME_ACTION_DONE -> "完成"
        EditorInfo.IME_ACTION_NEXT -> "下一项"
        EditorInfo.IME_ACTION_GO -> "前往"
        else -> "回车"
    }

    private fun spaceKeyLabel(): String = if (pinyin && hasComposition) "选词" else "空格"

    private fun keyRow(
        keys: String,
        sideWeight: Float = 0f,
        transform: (Char) -> String = { it.toString() },
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        if (sideWeight > 0f) addView(keySpacer(sideWeight))
        keys.forEach { character ->
            val value = transform(character)
            addView(key(value) { feed(value) })
        }
        if (sideWeight > 0f) addView(keySpacer(sideWeight))
        layoutParams = keyboardRowParams()
    }

    private fun keyboardRowParams() =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(keyHeightDp()))

    private fun keyHeightDp(): Int =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 48 else 56

    private fun candidateHeightDp(): Int =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 44 else 48

    private fun keySpacer(weight: Float) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
    }

    private fun key(
        label: String,
        weight: Float = 1f,
        labelSizeSp: Float = 18f,
        description: String = label,
        action: (() -> Unit)? = null,
    ) = Button(this).apply {
        text = label
        contentDescription = description
        isAllCaps = false
        gravity = Gravity.CENTER
        includeFontPadding = false
        minWidth = 0
        minHeight = 0
        minimumWidth = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSizeSp)
        setTextColor(keyText())
        backgroundTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(keyPressed(), keyBackground()),
        )
        stateListAnimator = null
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            action?.invoke() ?: feed(label)
        }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
            setMargins(dp(2), dp(3), dp(2), dp(3))
        }
    }

    private fun feed(text: String) {
        if (NativeIme.feed(handle, text) == 0) renderActions()
    }

    private fun commitLiteral(text: String) {
        if (hasComposition) resetCompositionForModeChange()
        currentInputConnection.commitText(text, 1)
    }

    private fun pressSpace() {
        if (!PlatformPolicy.shouldRouteEditingToEngine(keyboardPage)) {
            currentInputConnection.commitText(" ", 1)
            return
        }
        val wasComposing = hasComposition
        command(2)
        if (wasComposing) NativeIme.flush(handle)
        if (PlatformPolicy.shouldInsertLiteralSpace(pinyin, wasComposing)) {
            currentInputConnection.commitText(" ", 1)
        }
    }

    private fun pressBackspace() {
        if (!PlatformPolicy.shouldRouteEditingToEngine(keyboardPage)) {
            deletePreviousEditorGrapheme()
            return
        }
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
        if (!PlatformPolicy.shouldRouteEditingToEngine(keyboardPage)) {
            if (!sendDefaultEditorAction(false)) currentInputConnection.commitText("\n", 1)
            return
        }
        val wasComposing = hasComposition
        command(1)
        if (wasComposing) NativeIme.flush(handle)
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
        spaceKey?.text = spaceKeyLabel()
        spaceKey?.contentDescription = spaceKeyLabel()
    }

    private fun showCandidates(values: JSONArray) {
        showCandidatePage(values, 0)
    }

    private fun showCandidatePage(values: JSONArray, page: Int) {
        val pageSize = 5
        val safePage = page.coerceIn(0, ((values.length() - 1).coerceAtLeast(0)) / pageSize)
        val start = safePage * pageSize
        val end = minOf(values.length(), start + pageSize)
        if (safePage > 0) addCandidatePager("‹") { redrawCandidatePage(values, safePage - 1) }
        for (index in start until end) {
            val value = values.getJSONObject(index)
            candidates.addView(TextView(this).apply {
                val display = value.getString("display_text")
                val annotation = if (value.isNull("annotation")) "" else value.optString("annotation", "")
                text = candidateLabel(display, annotation)
                contentDescription = getString(R.string.candidate_description, display)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(keyText())
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                if (index == start) setBackgroundColor(keyPressed())
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    NativeIme.select(handle, value.getString("id"))
                    NativeIme.flush(handle)
                    renderActions()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                )
            })
        }
        if (end < values.length()) addCandidatePager("›") { redrawCandidatePage(values, safePage + 1) }
    }

    private fun redrawCandidatePage(values: JSONArray, page: Int) {
        candidates.removeAllViews()
        showModeIndicator()
        showCandidatePage(values, page)
    }

    private fun addCandidatePager(label: String, action: () -> Unit) {
        candidates.addView(TextView(this).apply {
            text = label
            contentDescription = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(keyText())
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
        })
    }

    private fun candidateLabel(display: String, annotation: String): CharSequence {
        if (annotation.isBlank()) return display
        return SpannableString("$display  $annotation").apply {
            val start = display.length + 2
            setSpan(RelativeSizeSpan(0.65f), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(modeText()), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun showModeIndicator() {
        candidates.addView(TextView(this).apply {
            text = when {
                keyboardPage == KeyboardPage.NUMBER -> getString(R.string.mode_numbers)
                keyboardPage == KeyboardPage.SYMBOL && chineseSymbols -> getString(R.string.mode_symbols_chinese)
                keyboardPage == KeyboardPage.SYMBOL -> getString(R.string.mode_symbols_english)
                !pinyin -> getString(R.string.mode_english)
                nineKeyPinyin -> getString(R.string.mode_pinyin_nine_key)
                else -> getString(R.string.mode_pinyin_full_keyboard)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(modeText())
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

    private fun darkMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun keyboardBackground(): Int = if (darkMode()) 0xFF202124.toInt() else 0xFFF1F3F6.toInt()
    private fun candidateBackground(): Int = if (darkMode()) 0xFF292A2D.toInt() else Color.WHITE
    private fun keyBackground(): Int = if (darkMode()) 0xFF3C4043.toInt() else Color.WHITE
    private fun keyPressed(): Int = if (darkMode()) 0xFF536A9E.toInt() else 0xFFD8E3FF.toInt()
    private fun keyText(): Int = if (darkMode()) Color.WHITE else Color.BLACK
    private fun modeText(): Int = if (darkMode()) 0xFFAFC6FF.toInt() else 0xFF3454D1.toInt()

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
        val CHINESE_SYMBOL_ROWS = listOf(
            listOf("，", "。", "？", "！", "：", "；"),
            listOf("“", "”", "‘", "’", "（", "）"),
            listOf("《", "》", "【", "】", "…", "—"),
        )
        val ENGLISH_SYMBOL_ROWS = listOf(
            listOf("~", "!", "@", "#", "$", "%"),
            listOf("^", "&", "*", "(", ")", "_"),
            listOf("+", "-", "=", "/", "\\", ":", ";"),
        )
    }
}
