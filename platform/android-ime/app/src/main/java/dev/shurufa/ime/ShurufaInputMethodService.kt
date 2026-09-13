package dev.shurufa.ime

import android.annotation.SuppressLint
import android.inputmethodservice.InputMethodService
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray

class ShurufaInputMethodService : InputMethodService() {
    private data class EditorIdentity(
        val packageName: String?,
        val fieldId: Int,
        val inputType: Int,
    )

    private var handle = 0L
    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var modeIndicator: TextView
    private lateinit var candidates: LinearLayout
    private lateinit var candidateExpandKey: Button
    private lateinit var keyboardRows: LinearLayout
    private var spaceKey: Button? = null
    private var voiceKey: Button? = null
    private var keyPreview: PopupWindow? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIsOnDevice: Boolean? = null
    private var acceptingSpeechResults = false
    private var passwordScope = false
    private var nativeRimeAvailable = false
    private var hasComposition = false
    private var nineKeyPinyin = false
    private var keyboardPage = KeyboardPage.TEXT
    private var chineseSymbols = true
    private var symbolPage = 0
    private var candidatePanelExpanded = false
    private var candidatePanelPage = 0
    private var lastCandidates = JSONArray()
    private var keyboardStateInitialized = false
    private var activeEditorIdentity: EditorIdentity? = null
    private var uppercase = false
    private var oneHandMode = OneHandMode.CENTER
    private var keyboardSize = KeyboardSize.STANDARD
    private var highContrast = false
    private var hapticEnabled = true
    private var voiceState = VoiceState.IDLE

    private enum class VoiceState {
        IDLE,
        LISTENING,
        PROCESSING,
        ERROR,
    }

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
        keyPreview?.dismiss()
        keyPreview = null
        uiHandler.removeCallbacksAndMessages(null)
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
            oneHandMode = OneHandMode.fromStored(preferences.getString("one_hand_mode", null))
            keyboardSize = KeyboardSize.fromStored(preferences.getString("keyboard_size", null))
            highContrast = preferences.getBoolean("high_contrast", false)
            hapticEnabled = preferences.getBoolean("haptic_enabled", true)
            symbolPage = 0
            candidatePanelExpanded = false
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
        voiceState = VoiceState.IDLE
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
        voiceState = VoiceState.IDLE
        if (handle != 0L) NativeIme.command(handle, 3)
        hasComposition = false
        super.onFinishInput()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (::candidates.isInitialized) {
            candidates.removeAllViews()
            updateCandidateHeader()
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
        modeIndicator = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(modeText())
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(6), 0)
        }
        candidates = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        candidateExpandKey = key("⌄", 1f, 18f, "展开全部候选") { toggleCandidatePanel() }.apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.MATCH_PARENT)
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(candidateBackground())
            addView(modeIndicator, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
            addView(HorizontalScrollView(this@ShurufaInputMethodService).apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = true
                addView(
                    candidates,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(candidateExpandKey)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(candidateHeightDp())))

        keyboardRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(keyboardRows)
        renderKeyboard()
        updateCandidateHeader()
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
        if (page == KeyboardPage.SYMBOL) symbolPage = 0
        candidatePanelExpanded = false
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
        candidatePanelExpanded = false
        lastCandidates = JSONArray()
    }

    private fun renderKeyboard() {
        keyboardRows.removeAllViews()
        spaceKey = null
        voiceKey = null
        applyKeyboardWidth()
        if (candidatePanelExpanded && lastCandidates.length() > 0) {
            renderExpandedCandidates()
            return
        }
        when (keyboardPage) {
            KeyboardPage.NUMBER -> renderNumberKeyboard()
            KeyboardPage.SYMBOL -> renderSymbolKeyboard()
            KeyboardPage.EMOJI -> renderEmojiKeyboard()
            KeyboardPage.TEXT -> renderTextKeyboard()
        }
        keyboardRows.addView(functionRow())
    }

    private fun applyKeyboardWidth() {
        val fraction = KeyboardUiModel.keyboardWidthFraction(
            oneHandMode,
            resources.configuration.smallestScreenWidthDp,
        )
        keyboardRows.layoutParams = LinearLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * fraction).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = when (oneHandMode) {
                OneHandMode.LEFT -> Gravity.START
                OneHandMode.RIGHT -> Gravity.END
                OneHandMode.CENTER -> Gravity.CENTER_HORIZONTAL
            }
        }
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
        addView(key("#+=", 0.8f, 12f, "符号键盘") { showKeyboardPage(KeyboardPage.SYMBOL) })
        val languageTarget = if (pinyin) "切英" else "切中"
        val languageDescription = if (pinyin) "切换到英文输入" else "切换到中文拼音"
        addView(key(languageTarget, 0.9f, 14f, languageDescription) { toggleEngine() })
        addView(key(oneHandLabel(), 0.65f, 14f, "切换单手键盘位置") { cycleOneHandMode() })
        voiceKey = key(voiceKeyLabel(), 0.8f, 14f, voiceKeyDescription()) { toggleDictation() }
        addView(voiceKey)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(54),
        )
    }

    private fun renderNumberKeyboard() {
        KeyboardUiModel.numberRows(currentInputEditorInfo?.inputType ?: InputType.TYPE_CLASS_TEXT)
            .forEach { keyboardRows.addView(literalKeyRow(it)) }
    }

    private fun renderSymbolKeyboard() {
        val pages = if (chineseSymbols) KeyboardUiModel.chineseSymbolPages else KeyboardUiModel.englishSymbolPages
        val rows = pages[symbolPage.coerceIn(pages.indices)]
        rows.forEach { keyboardRows.addView(literalKeyRow(it)) }
    }

    private fun renderEmojiKeyboard() {
        KeyboardUiModel.emojiRows.forEach { keyboardRows.addView(literalKeyRow(it, 24f)) }
    }

    private fun t9KeyRow(keys: List<Pair<String, String>>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        keys.forEach { (label, value) ->
            addView(key(label, labelSizeSp = 15f) { feed(value) })
        }
        layoutParams = keyboardRowParams()
    }

    private fun literalKeyRow(values: List<String>, labelSizeSp: Float = 16f) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        values.forEach { value ->
            addView(key(value, labelSizeSp = labelSizeSp, preview = true) { commitLiteral(value) })
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
                addView(key("#+=", 1f, 13f, "符号键盘") { showKeyboardPage(KeyboardPage.SYMBOL) })
                addView(key("空格", 2.2f, 14f) { pressSpace() })
                addView(repeatingDeleteKey(1.1f, 13f))
                addView(key("回车", 1.1f, 13f) { pressEnter() })
            }
            KeyboardPage.SYMBOL -> {
                addView(key("ABC", 0.9f, 13f) { showKeyboardPage(KeyboardPage.TEXT) })
                addView(key("123", 0.9f, 13f) { showKeyboardPage(KeyboardPage.NUMBER) })
                addView(key("😊", 0.75f, 18f, "表情键盘") { showKeyboardPage(KeyboardPage.EMOJI) })
                addView(key("第${symbolPage + 1}页", 0.9f, 12f, "切换符号页") {
                    symbolPage = (symbolPage + 1) % 2
                    renderKeyboard()
                    updateCandidateHeader()
                })
                addView(key(if (chineseSymbols) "英符" else "中符", 0.9f, 13f) {
                    chineseSymbols = !chineseSymbols
                    symbolPage = 0
                    renderKeyboard()
                    updateCandidateHeader()
                })
                addView(repeatingDeleteKey(0.8f, 13f))
                addView(key("回车", 0.9f, 13f) { pressEnter() })
            }
            KeyboardPage.EMOJI -> {
                addView(key("ABC", 0.9f, 13f) { showKeyboardPage(KeyboardPage.TEXT) })
                addView(key("123", 0.9f, 13f) { showKeyboardPage(KeyboardPage.NUMBER) })
                addView(key("#+=", 0.9f, 13f, "符号键盘") { showKeyboardPage(KeyboardPage.SYMBOL) })
                addView(key("剪贴板", 1.2f, 12f) { commitClipboard() })
                addView(repeatingDeleteKey(0.9f, 13f))
                addView(key("回车", 0.9f, 13f) { pressEnter() })
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
        addView(repeatingDeleteKey(0.8f, 18f, "⌫"))
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
            addView(key(
                value,
                longPressValue = KeyboardUiModel.longPressValue(character),
                preview = true,
            ) { feed(value) })
        }
        if (sideWeight > 0f) addView(keySpacer(sideWeight))
        layoutParams = keyboardRowParams()
    }

    private fun keyboardRowParams() =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(keyHeightDp()))

    private fun keyHeightDp(): Int =
        KeyboardUiModel.keyHeightDp(
            resources.configuration.orientation,
            keyboardSize,
            resources.configuration.fontScale,
        )

    private fun candidateHeightDp(): Int =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 44 else 48

    private fun keySpacer(weight: Float) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun key(
        label: String,
        weight: Float = 1f,
        labelSizeSp: Float = 18f,
        description: String = label,
        longPressValue: String? = null,
        preview: Boolean = false,
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
            if (hapticEnabled) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            action?.invoke() ?: feed(label)
        }
        if (longPressValue != null) {
            setOnLongClickListener {
                if (hapticEnabled) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                commitLiteral(longPressValue)
                true
            }
        }
        if (preview) {
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> showKeyPreview(view, label)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dismissKeyPreview()
                }
                false
            }
        }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
            setMargins(dp(2), dp(3), dp(2), dp(3))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun repeatingDeleteKey(
        weight: Float,
        labelSizeSp: Float,
        label: String = "删除",
    ): Button {
        var repeated = false
        val repeat = object : Runnable {
            override fun run() {
                repeated = true
                pressBackspace()
                uiHandler.postDelayed(this, 65L)
            }
        }
        return key(label, weight, labelSizeSp, "删除，长按连续删除") {
            if (!repeated) pressBackspace()
            repeated = false
        }.apply {
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        repeated = false
                        showKeyPreview(view, label)
                        uiHandler.postDelayed(repeat, 380L)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        uiHandler.removeCallbacks(repeat)
                        dismissKeyPreview()
                    }
                }
                false
            }
        }
    }

    private fun showKeyPreview(anchor: View, label: String) {
        if (label.length > 3 || label.contains('\n')) return
        dismissKeyPreview()
        keyPreview = PopupWindow(
            TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                setTextColor(keyText())
                background = ColorDrawable(keyBackground())
                elevation = dp(6).toFloat()
            },
            maxOf(anchor.width, dp(52)),
            dp(58),
            false,
        ).apply {
            isClippingEnabled = false
            elevation = dp(8).toFloat()
            showAsDropDown(anchor, (anchor.width - width) / 2, -anchor.height - height - dp(4))
        }
    }

    private fun dismissKeyPreview() {
        keyPreview?.dismiss()
        keyPreview = null
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
        var receivedCandidates = false
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
                    receivedCandidates = true
                    showCandidates(action.getJSONArray("ShowCandidates"))
                }
            }
        }
        if (!receivedCandidates && !hasComposition) {
            lastCandidates = JSONArray()
            candidatePanelExpanded = false
        }
        updateCandidateHeader()
        spaceKey?.text = spaceKeyLabel()
        spaceKey?.contentDescription = spaceKeyLabel()
    }

    private fun showCandidates(values: JSONArray) {
        lastCandidates = values
        showCandidatePage(values, 0)
    }

    private fun showCandidatePage(values: JSONArray, page: Int) {
        val pageSize = CANDIDATE_STRIP_SIZE
        val safePage = page.coerceIn(0, ((values.length() - 1).coerceAtLeast(0)) / pageSize)
        val start = safePage * pageSize
        val end = minOf(values.length(), start + pageSize)
        for (index in start until end) {
            candidates.addView(candidateView(values.getJSONObject(index), index == start))
        }
        candidatePanelPage = safePage
    }

    private fun candidateView(value: org.json.JSONObject, highlighted: Boolean) = TextView(this).apply {
        val display = value.getString("display_text")
        val annotation = if (value.isNull("annotation")) "" else value.optString("annotation", "")
        text = candidateLabel(display, annotation)
        contentDescription = getString(R.string.candidate_description, display)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(candidateText())
        gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(14), 0)
        if (highlighted) {
            setBackgroundColor(keyPressed())
            if (highContrast) setTextColor(Color.BLACK)
        }
        setOnClickListener {
            if (hapticEnabled) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            selectCandidate(value.getString("id"))
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
        )
    }

    private fun selectCandidate(id: String) {
        NativeIme.select(handle, id)
        NativeIme.flush(handle)
        candidatePanelExpanded = false
        renderKeyboard()
        renderActions()
    }

    private fun toggleCandidatePanel() {
        if (lastCandidates.length() <= CANDIDATE_STRIP_SIZE) return
        candidatePanelExpanded = !candidatePanelExpanded
        candidatePanelPage = 0
        candidateExpandKey.text = if (candidatePanelExpanded) "⌃" else "⌄"
        candidateExpandKey.contentDescription = if (candidatePanelExpanded) "收起候选" else "展开全部候选"
        renderKeyboard()
    }

    private fun renderExpandedCandidates() {
        val pageSize = 20
        val maxPage = ((lastCandidates.length() - 1).coerceAtLeast(0)) / pageSize
        candidatePanelPage = candidatePanelPage.coerceIn(0, maxPage)
        val start = candidatePanelPage * pageSize
        val end = minOf(lastCandidates.length(), start + pageSize)
        val rowCount = ((end - start + 4) / 5).coerceAtLeast(1)
        repeat(rowCount) { rowIndex ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = keyboardRowParams()
            }
            repeat(5) { column ->
                val index = start + rowIndex * 5 + column
                if (index < lastCandidates.length()) {
                    val candidate = lastCandidates.getJSONObject(index)
                    val display = candidate.getString("display_text")
                    val annotation = if (candidate.isNull("annotation")) "" else candidate.optString("annotation", "")
                    row.addView(key(
                        if (annotation.isBlank()) display else "$display\n$annotation",
                        labelSizeSp = 13f,
                        description = getString(R.string.candidate_description, display),
                    ) { selectCandidate(candidate.getString("id")) })
                } else {
                    row.addView(keySpacer(1f))
                }
            }
            keyboardRows.addView(row)
        }
        keyboardRows.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(key("‹", 1f, 22f, "上一页候选") {
                if (candidatePanelPage > 0) {
                    candidatePanelPage--
                    renderKeyboard()
                }
            })
            addView(key("返回键盘", 2f, 14f) { toggleCandidatePanel() })
            addView(key("${candidatePanelPage + 1}/${maxPage + 1}", 1f, 13f, "候选页码") {})
            addView(key("›", 1f, 22f, "下一页候选") {
                if (candidatePanelPage < maxPage) {
                    candidatePanelPage++
                    renderKeyboard()
                }
            })
            layoutParams = keyboardRowParams()
        })
    }

    private fun updateCandidateHeader() {
        if (!::modeIndicator.isInitialized) return
        val baseMode = when {
            keyboardPage == KeyboardPage.NUMBER -> getString(R.string.mode_numbers)
            keyboardPage == KeyboardPage.SYMBOL && chineseSymbols -> getString(R.string.mode_symbols_chinese)
            keyboardPage == KeyboardPage.SYMBOL -> getString(R.string.mode_symbols_english)
            keyboardPage == KeyboardPage.EMOJI -> getString(R.string.mode_emoji)
            !pinyin -> getString(R.string.mode_english)
            nineKeyPinyin -> getString(R.string.mode_pinyin_nine_key)
            nativeRimeAvailable -> getString(R.string.mode_pinyin_full_keyboard)
            else -> getString(R.string.mode_pinyin_fallback)
        }
        val voiceStatus = when (voiceState) {
            VoiceState.LISTENING -> getString(R.string.voice_listening)
            VoiceState.PROCESSING -> getString(R.string.voice_processing)
            VoiceState.ERROR -> getString(R.string.voice_unavailable)
            VoiceState.IDLE -> null
        }
        modeIndicator.text = voiceStatus?.let {
            getString(R.string.mode_with_status, baseMode, it)
        } ?: baseMode
        modeIndicator.contentDescription = modeIndicator.text
        candidateExpandKey.visibility = if (lastCandidates.length() > CANDIDATE_STRIP_SIZE) View.VISIBLE else View.GONE
        candidateExpandKey.text = if (candidatePanelExpanded) "⌃" else "⌄"
    }

    private fun candidateLabel(display: String, annotation: String): CharSequence {
        if (annotation.isBlank()) return display
        return SpannableString("$display  $annotation").apply {
            val start = display.length + 2
            setSpan(RelativeSizeSpan(0.65f), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(modeText()), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()

    private fun darkMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun keyboardBackground(): Int = when {
        highContrast -> Color.BLACK
        darkMode() -> 0xFF202124.toInt()
        else -> 0xFFF1F3F6.toInt()
    }
    private fun candidateBackground(): Int = when {
        highContrast -> Color.BLACK
        darkMode() -> 0xFF292A2D.toInt()
        else -> Color.WHITE
    }
    private fun keyBackground(): Int = when {
        highContrast -> Color.WHITE
        darkMode() -> 0xFF3C4043.toInt()
        else -> Color.WHITE
    }
    private fun keyPressed(): Int = when {
        highContrast -> Color.YELLOW
        darkMode() -> 0xFF536A9E.toInt()
        else -> 0xFFD8E3FF.toInt()
    }
    private fun keyText(): Int = if (darkMode() && !highContrast) Color.WHITE else Color.BLACK
    private fun candidateText(): Int = if (darkMode() || highContrast) Color.WHITE else Color.BLACK
    private fun modeText(): Int = when {
        highContrast -> Color.YELLOW
        darkMode() -> 0xFFAFC6FF.toInt()
        else -> 0xFF3454D1.toInt()
    }

    private fun oneHandLabel(): String = when (oneHandMode) {
        OneHandMode.CENTER -> "↔"
        OneHandMode.LEFT -> "左手"
        OneHandMode.RIGHT -> "右手"
    }

    private fun cycleOneHandMode() {
        oneHandMode = oneHandMode.next()
        getSharedPreferences("shurufa", MODE_PRIVATE)
            .edit()
            .putString("one_hand_mode", oneHandMode.storedValue)
            .apply()
        renderKeyboard()
        updateCandidateHeader()
    }

    private fun commitClipboard() {
        if (passwordScope) {
            showImeMessage("密码输入框禁止读取剪贴板")
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val text = item?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) {
            showImeMessage("剪贴板为空")
            return
        }
        commitLiteral(text)
    }

    private fun voiceKeyLabel(): String = when (voiceState) {
        VoiceState.IDLE -> "🎙"
        VoiceState.LISTENING -> "停止"
        VoiceState.PROCESSING -> "识别"
        VoiceState.ERROR -> "重试"
    }

    private fun voiceKeyDescription(): String = when (voiceState) {
        VoiceState.IDLE, VoiceState.ERROR -> "开始语音输入"
        VoiceState.LISTENING -> "停止语音输入"
        VoiceState.PROCESSING -> "正在识别语音"
    }

    private fun updateVoiceUi() {
        voiceKey?.text = voiceKeyLabel()
        voiceKey?.contentDescription = voiceKeyDescription()
        updateCandidateHeader()
    }

    private fun toggleDictation() {
        if (acceptingSpeechResults) {
            acceptingSpeechResults = false
            speechRecognizer?.cancel()
            voiceState = VoiceState.IDLE
            updateVoiceUi()
        } else {
            startSystemDictation()
        }
    }

    private fun showImeMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun startSystemDictation() {
        val allowNetwork = getSharedPreferences("shurufa", MODE_PRIVATE)
            .getBoolean("online_system_speech", false)
        val onDeviceAvailable = Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        if (passwordScope) {
            showImeMessage("密码输入框禁止语音输入")
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showImeMessage("请先在 Shurufa 设置中授予麦克风权限")
            return
        }
        if (!onDeviceAvailable && !allowNetwork) {
            showImeMessage("没有端侧语音识别器；可在设置中允许系统语音服务联网")
            return
        }
        if (!PlatformPolicy.mayStartSpeech(
                passwordScope,
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                onDeviceAvailable,
                allowNetwork,
            )
        ) return
        val recognizer = obtainSpeechRecognizer(allowNetwork) ?: run {
            voiceState = VoiceState.ERROR
            updateVoiceUi()
            showImeMessage("语音识别器不可用")
            return
        }
        if (speechRecognizer !== recognizer) {
            speechRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    voiceState = VoiceState.LISTENING
                    updateVoiceUi()
                }
                override fun onBeginningOfSpeech() {
                    voiceState = VoiceState.LISTENING
                    updateVoiceUi()
                }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    voiceState = VoiceState.PROCESSING
                    updateVoiceUi()
                }
                override fun onError(error: Int) {
                    acceptingSpeechResults = false
                    voiceState = VoiceState.ERROR
                    updateVoiceUi()
                    showImeMessage("语音识别失败（$error）")
                    uiHandler.postDelayed({
                        if (voiceState == VoiceState.ERROR) {
                            voiceState = VoiceState.IDLE
                            updateVoiceUi()
                        }
                    }, 1800L)
                }
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
            voiceState = VoiceState.LISTENING
            updateVoiceUi()
            recognizer.startListening(request)
        } catch (_: SecurityException) {
            acceptingSpeechResults = false
            voiceState = VoiceState.ERROR
            updateVoiceUi()
            showImeMessage("麦克风权限不可用")
        } catch (_: IllegalStateException) {
            acceptingSpeechResults = false
            voiceState = VoiceState.ERROR
            updateVoiceUi()
            showImeMessage("语音识别器正忙")
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
        if (finalResult) {
            acceptingSpeechResults = false
            voiceState = VoiceState.IDLE
            updateVoiceUi()
        } else {
            voiceState = VoiceState.LISTENING
            updateVoiceUi()
        }
    }

    private companion object {
        const val CANDIDATE_STRIP_SIZE = 5
    }
}
