package dev.shurufa.ime

import android.annotation.SuppressLint
import android.inputmethodservice.InputMethodService
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
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
    private lateinit var topRow: FrameLayout
    private lateinit var candidateStrip: LinearLayout
    private var toolbar: LinearLayout? = null
    private lateinit var candidateExpandKey: Button
    private lateinit var keyboardRows: LinearLayout
    private var spaceKey: Button? = null
    private var voiceKey: Button? = null
    private var handwritingPanel: HandwritingPanel? = null
    private var keyPreview: PopupWindow? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIsOnDevice: Boolean? = null
    private var acceptingSpeechResults = false
    private var passwordScope = false
    private var keyboardTextSize = KeyboardTextSize.STANDARD
    private fun isLandscape(): Boolean = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    override fun onEvaluateFullscreenMode(): Boolean = false
    private var learningAllowed = true
    private var nativeCandidatePage = 0
    private var nativeHasNextPage = false
    private var deleteRepeat: Runnable? = null
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
        handwritingPanel?.dispose()
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
        handwritingPanel?.dispose()
        super.onStartInput(attribute, restarting)
        val preferences = getSharedPreferences("shurufa", MODE_PRIVATE)
        keyboardTextSize = KeyboardTextSize.fromStored(preferences.getString("keyboard_text_size", null))
        highContrast = preferences.getBoolean("high_contrast", false)
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
        val personalizedLearningAllowed =
            ((attribute?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0
        learningAllowed = preferences.getBoolean("learning_enabled", true) && personalizedLearningAllowed
        NativeIme.setPrivacy(
            handle,
            learningAllowed,
            preferences.getBoolean("online_system_speech", false),
        )
        NativeIme.setApplicationId(handle, attribute?.packageName ?: "")
        val variation = (attribute?.inputType ?: 0) and InputType.TYPE_MASK_VARIATION
        passwordScope = PlatformPolicy.isPassword(attribute?.inputType ?: InputType.TYPE_CLASS_TEXT)
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
        NativeIme.switchEngine(handle, selectedEngine())
        if (initializeKeyboardState) {
            NativeIme.command(handle, 3)
            hasComposition = false
        }
    }

    override fun onFinishInput() {
        handwritingPanel?.dispose()
        stopDeleteRepeat()
        acceptingSpeechResults = false
        speechRecognizer?.cancel()
        voiceState = VoiceState.IDLE
        if (handle != 0L) NativeIme.command(handle, 3)
        hasComposition = false
        super.onFinishInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        handwritingPanel?.dispose()
        stopDeleteRepeat()
        acceptingSpeechResults = false
        speechRecognizer?.cancel()
        voiceState = VoiceState.IDLE
        super.onFinishInputView(finishingInput)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Rebuild surfaces as well as labels so returning from settings applies
        // text size and contrast even when the editor itself did not change.
        if (::keyboardRows.isInitialized) setInputView(onCreateInputView())
        if (::candidates.isInitialized) {
            candidates.removeAllViews()
            if (hasComposition && lastCandidates.length() > 0) showCandidatePage(lastCandidates, 0)
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
            // Normal keyboard modes need no title row or reserved blank space.
            visibility = View.GONE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(modeText())
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
        }
        candidates = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            setOnHierarchyChangeListener(object : android.view.ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) { updateTopRow() }
                override fun onChildViewRemoved(parent: View?, child: View?) { updateTopRow() }
            })
        }
        candidateExpandKey = key("⌄", 1f, 18f, "展开全部候选") { toggleCandidatePanel() }.apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.MATCH_PARENT)
        }
        root.addView(modeIndicator, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(26)))
        topRow = FrameLayout(this)
        toolbar = null
        candidateStrip = LinearLayout(this).apply {
            contentDescription = "顶栏候选"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(candidateBackground())
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
            addView(key("工具", 1f, 12f, "键盘工具") { showKeyboardTools() },
                LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.MATCH_PARENT))
        }
        topRow.addView(candidateStrip, FrameLayout.LayoutParams(-1, -1))
        root.addView(topRow, LinearLayout.LayoutParams(-1, dp(candidateHeightDp())))

        keyboardRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(keyboardRows)
        renderKeyboard()
        updateCandidateHeader()
        root.requestApplyInsets()
        return root
    }

    private var pinyin = false

    private fun toggleEngine() {
        if (keyboardPage == KeyboardPage.HANDWRITING && handwritingPanel?.canLeave() == false) return
        val previous = pinyin
        if (!resetCompositionForModeChange()) return
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
        if (page == KeyboardPage.HANDWRITING && passwordScope) {
            showImeMessage("密码输入不启用手写识别")
            return
        }
        if (keyboardPage == page) return
        if (keyboardPage == KeyboardPage.HANDWRITING && handwritingPanel?.canLeave() == false) return
        if (!resetCompositionForModeChange()) return
        keyboardPage = page
        if (page == KeyboardPage.HANDWRITING) {
            acceptingSpeechResults = false
            speechRecognizer?.cancel()
            voiceState = VoiceState.IDLE
        }
        if (page == KeyboardPage.SYMBOL) chineseSymbols = pinyin
        if (page == KeyboardPage.SYMBOL) symbolPage = 0
        candidatePanelExpanded = false
        renderKeyboard()
        renderActions()
    }

    private fun selectedEngine(): String =
        PlatformPolicy.engine(pinyin, nativeRimeAvailable && learningAllowed && !passwordScope, nineKeyPinyin)

    private fun togglePinyinLayout() {
        if (keyboardPage == KeyboardPage.HANDWRITING && handwritingPanel?.canLeave() == false) return
        if (!pinyin) return
        if (!resetCompositionForModeChange()) return
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

    private fun resetCompositionForModeChange(): Boolean {
        // Mode changes and literals finish the current word, never discard it.
        if (hasComposition) {
            if (NativeIme.command(handle, 2) != 0) {
                showImeMessage("当前输入未能提交，请重试或选词")
                return false
            }
            renderActions()
            NativeIme.flush(handle)
        }
        currentInputConnection.finishComposingText()
        if (handle != 0L) NativeIme.command(handle, 3)
        hasComposition = false
        candidatePanelExpanded = false
        lastCandidates = JSONArray()
        return true
    }

    private fun renderKeyboard() {
        stopDeleteRepeat()
        handwritingPanel?.dispose()
        handwritingPanel = null
        keyboardRows.removeAllViews()
        spaceKey = null
        voiceKey = null
        toolbar?.let { topRow.removeView(it) }
        toolbar = textToolbar().also { topRow.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        updateTopRow()
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
            KeyboardPage.HANDWRITING -> {
                if (!passwordScope) {
                    handwritingPanel = HandwritingPanel(this, if (highContrast) Color.WHITE else keyText(), keyboardBackground(), keyboardTextSize.scale, candidates, commit = { text ->
                        if (keyboardPage != KeyboardPage.HANDWRITING || passwordScope) false
                        else currentInputConnection?.commitText(text, 1) == true
                    }, onLiteral = { value -> commitLiteral(value) }, onDelete = { deletePreviousEditorGrapheme() })
                    keyboardRows.addView(handwritingPanel)
                }
            }
        }
        keyboardRows.addView(functionRow())
    }

    private fun applyKeyboardWidth() {
        val fraction = KeyboardUiModel.keyboardWidthFraction(
            oneHandMode,
            resources.configuration.smallestScreenWidthDp,
        )
        keyboardRows.layoutParams = LinearLayout.LayoutParams(
            if (fraction == 1f) LinearLayout.LayoutParams.MATCH_PARENT else
                (((keyboardRows.parent as? View)?.width?.takeIf { it > 0 }
                    ?: resources.configuration.screenWidthDp.let { dp(it) }) - dp(8))
                    .times(fraction).toInt(),
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
        if (pinyin && nineKeyPinyin) {
            keyboardRows.addView(t9Board())
        } else {
            val transform: (Char) -> String = { character ->
                if (!pinyin && uppercase) character.uppercase() else character.toString()
            }
            keyboardRows.addView(keyRow("qwertyuiop", transform = transform))
            keyboardRows.addView(keyRow("asdfghjkl", sideWeight = 0.5f, transform = transform))
            keyboardRows.addView(keyRow("zxcvbnm", sideWeight = 1.5f, transform = transform))
        }
    }

    private fun t9Board() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val height = dp(keyHeightDp() * 3)
        fun column(weight: Float) = LinearLayout(this@ShurufaInputMethodService).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, height, weight)
        }
        val punctuation = column(0.65f)
        (if (isLandscape()) listOf("，", "。", "？") else listOf("，", "。", "？", "！")).forEach { value ->
            punctuation.addView(key(value, labelSizeSp = 24f, preview = true) { commitLiteral(value) }.apply {
                layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }
            })
        }
        addView(punctuation)
        addView(column(3f).apply {
            addView(t9KeyRow(listOf("1\n'" to "'", "2\nABC" to "2", "3\nDEF" to "3")))
            addView(t9KeyRow(listOf("4\nGHI" to "4", "5\nJKL" to "5", "6\nMNO" to "6")))
            addView(t9KeyRow(listOf("7\nPQRS" to "7", "8\nTUV" to "8", "9\nWXYZ" to "9")))
        })
        addView(column(0.8f).apply {
            val buttons = listOf(repeatingDeleteKey(1f, 24f, "⌫"),
                key("清空", labelSizeSp = 19f, description = "清空当前拼音") {
                    // Clear only the active composition; never erase committed editor text.
                    if (hasComposition) { command(3); currentInputConnection.finishComposingText() }
                }, key(enterKeyLabel(), labelSizeSp = 19f) { pressEnter() })
            buttons.forEach { button ->
                button.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }
                addView(button)
            }
        })
        layoutParams = LinearLayout.LayoutParams(-1, height)
    }

    private fun textToolbar() = LinearLayout(this).apply {
        contentDescription = "顶栏工具"
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        if (!pinyin) {
            addView(key(if (uppercase) "⇧ 大写" else "⇧", 0.9f, 12f) {
                if (keyboardPage == KeyboardPage.HANDWRITING && handwritingPanel?.canLeave() == false) return@key
                uppercase = !uppercase
                renderKeyboard()
            })
        } else {
            addView(key(if (nineKeyPinyin) "26键" else "9键", 0.9f, 12f) {
                if (keyboardPage != KeyboardPage.TEXT) showKeyboardPage(KeyboardPage.TEXT)
                if (keyboardPage == KeyboardPage.TEXT) togglePinyinLayout()
            })
        }
        addView(key("123", 0.8f, 12f) { showKeyboardPage(KeyboardPage.NUMBER) })
        addView(key("#+=", 0.8f, 12f, "符号键盘") { showKeyboardPage(KeyboardPage.SYMBOL) })
        addView(key("手写", 0.9f, 15f, "手写输入") { showKeyboardPage(KeyboardPage.HANDWRITING) })
        val languageTarget = if (pinyin) "切英" else "切中"
        val languageDescription = if (pinyin) "切换到英文输入" else "切换到中文拼音"
        addView(key(languageTarget, 0.9f, 14f, languageDescription) { toggleEngine() })
        addView(key(oneHandLabel(), 0.65f, 14f, "切换单手键盘位置") {
            if (keyboardPage != KeyboardPage.HANDWRITING || handwritingPanel?.canLeave() != false) cycleOneHandMode()
        })
        voiceKey = key(voiceKeyLabel(), 0.8f, 14f, voiceKeyDescription()) {
            if (keyboardPage == KeyboardPage.HANDWRITING) handwritingPanel?.confirmFirstOr { toggleDictation() }
            else toggleDictation()
        }
        addView(voiceKey)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(if (isLandscape()) 44 else 54),
        )
    }

    /** The candidate strip and idle tools share one fixed-height row. */
    private fun updateTopRow() {
        if (!::candidateStrip.isInitialized || !::topRow.isInitialized) return
        val hasChoices = candidates.childCount > 0
        candidateStrip.setBackgroundColor(if (keyboardPage == KeyboardPage.HANDWRITING) keyboardBackground() else candidateBackground())
        candidateStrip.visibility = if (hasChoices) View.VISIBLE else View.GONE
        toolbar?.visibility = if (hasChoices) View.GONE else View.VISIBLE
    }

    private fun showKeyboardTools() {
        val popup = android.widget.PopupMenu(this, candidateStrip.getChildAt(candidateStrip.childCount - 1))
        listOf("123", "符号", "手写", if (nineKeyPinyin) "26键" else "9键", "切换中英").forEachIndexed { index, label ->
            popup.menu.add(0, index, index, label)
        }
        popup.menu.findItem(3).isEnabled = pinyin
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> showKeyboardPage(KeyboardPage.NUMBER)
                1 -> showKeyboardPage(KeyboardPage.SYMBOL)
                2 -> showKeyboardPage(KeyboardPage.HANDWRITING)
                3 -> {
                    if (keyboardPage == KeyboardPage.HANDWRITING && handwritingPanel?.canLeave() == false) return@setOnMenuItemClickListener true
                    if (pinyin) {
                        showKeyboardPage(KeyboardPage.TEXT)
                        if (keyboardPage == KeyboardPage.TEXT) togglePinyinLayout()
                    }
                }
                4 -> toggleEngine()
            }
            true
        }
        popup.show()
    }

    private fun renderNumberKeyboard() {
        KeyboardUiModel.numberRows(currentInputEditorInfo?.inputType ?: InputType.TYPE_CLASS_TEXT)
            .forEach { keyboardRows.addView(literalKeyRow(it, KeyboardTypography.NUMBER)) }
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
            val parts = label.split('\n', limit = 2)
            addView(key(if (value == "'") "分词" else parts.last(), labelSizeSp = KeyboardTypography.T9, description = label,
                secondaryLabel = parts.first()) { feed(value) })
        }
        layoutParams = keyboardRowParams()
    }

    private fun literalKeyRow(values: List<String>, labelSizeSp: Float = KeyboardTypography.SYMBOL) = LinearLayout(this).apply {
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
            KeyboardPage.HANDWRITING -> {
                addView(key("符号", 1f, 19f, "符号键盘") { showKeyboardPage(KeyboardPage.SYMBOL) })
                addView(key(if (pinyin) "拼音" else "英文", 1f, 19f) { showKeyboardPage(KeyboardPage.TEXT) })
                addView(key("空格", 2f, 20f, secondaryLabel = "长按语音") { handwritingPanel?.confirmFirstOr { pressSpace() } }.apply {
                    setOnLongClickListener { handwritingPanel?.confirmFirstOr { toggleDictation() }; true }
                })
                addView(key("123", 1f, 21f) { showKeyboardPage(KeyboardPage.NUMBER) })
                if (isLandscape()) addView(repeatingDeleteKey(1f, 22f))
                addView(key(enterKeyLabel(), 1f, 19f) { handwritingPanel?.confirmFirstOr { pressEnter() } })
            }
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
        if (pinyin && nineKeyPinyin) {
            addView(key("符号", 1f, 19f, "符号键盘") { showKeyboardPage(KeyboardPage.SYMBOL) })
            addView(key("中/英", 1f, 18f, "切换到英文输入") { toggleEngine() })
            spaceKey = key(spaceKeyLabel(), 1.7f, 20f) { pressSpace() }
            addView(spaceKey)
            addView(key("123", 1f, 21f) { showKeyboardPage(KeyboardPage.NUMBER) })
            return
        }
        addView(key(if (pinyin) "，" else ",", 0.75f, KeyboardTypography.SYMBOL) { commitLiteral(if (pinyin) "，" else ",") })
        if (pinyin) addView(key("'", 0.65f, 16f) { feed("'") })
        spaceKey = key(spaceKeyLabel(), 2.4f, 20f) { pressSpace() }
        addView(spaceKey)
        addView(key(if (pinyin) "。" else ".", 0.75f, KeyboardTypography.SYMBOL) { commitLiteral(if (pinyin) "。" else ".") })
        addView(key(enterKeyLabel(), 1f, 18f) { pressEnter() })
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
        val bottom = keys == "zxcvbnm"
        if (bottom) addView(key(if (!pinyin && uppercase) "⇧ 大写" else "⇧", sideWeight, 23f,
            if (pinyin) "切换到英文大写" else "切换字母大小写") {
            if (pinyin) { uppercase = true; toggleEngine() } else { uppercase = !uppercase; renderKeyboard() }
        }) else if (sideWeight > 0f) addView(keySpacer(sideWeight))
        keys.forEach { character ->
            val value = transform(character)
            addView(key(
                value.uppercase(), description = value,
                longPressValue = KeyboardUiModel.longPressValue(character),
                preview = true,
            ) { feed(value) })
        }
        if (bottom) addView(repeatingDeleteKey(sideWeight, 23f, "⌫"))
        else if (sideWeight > 0f) addView(keySpacer(sideWeight))
        layoutParams = keyboardRowParams()
    }

    private fun keyboardRowParams() =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(keyHeightDp()))

    private fun keyHeightDp(): Int =
        if (isLandscape()) 54 else KeyboardUiModel.keyHeightDp(
            resources.configuration.orientation,
            keyboardSize,
            resources.configuration.fontScale,
        ).coerceAtLeast(maxOf((60 * keyboardTextSize.scale).toInt(), if (pinyin && nineKeyPinyin && keyboardPage == KeyboardPage.TEXT) 64 else 60))

    private fun candidateHeightDp(): Int =
        if (isLandscape()) 48 else (52 * keyboardTextSize.scale * resources.configuration.fontScale.coerceIn(1f, 1.35f)).toInt()

    private fun keySpacer(weight: Float) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun key(
        label: String,
        weight: Float = 1f,
        labelSizeSp: Float = KeyboardTypography.LETTER,
        description: String = label,
        longPressValue: String? = null,
        preview: Boolean = false,
        secondaryLabel: String? = null,
        action: (() -> Unit)? = null,
    ) = KeyboardKeyView(this).apply {
        text = label
        contentDescription = description
        isAllCaps = false
        gravity = Gravity.CENTER
        includeFontPadding = false
        minWidth = 0
        minHeight = 0
        minimumWidth = 0
        minimumHeight = 0
        this.secondaryLabel = secondaryLabel ?: longPressValue
        hintSizeSp = KeyboardTypography.HINT * keyboardTextSize.scale
        hintColor = if (highContrast) keyText() else if (darkMode()) 0xFFBDC2CC.toInt() else 0xFF70757F.toInt()
        setPadding(0, if (this.secondaryLabel != null) dp(12) else 0, 0, 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSizeSp.coerceAtLeast(KeyboardTypography.FUNCTION) * keyboardTextSize.scale)
        maxLines = 1
        val maximumSp = (labelSizeSp.coerceAtLeast(KeyboardTypography.FUNCTION) * keyboardTextSize.scale).toInt()
        val minimumSp = minOf(if (label.length == 1) 18 else 14, maximumSp)
        if (maximumSp > minimumSp) setAutoSizeTextTypeUniformWithConfiguration(
            minimumSp, maximumSp, 1, TypedValue.COMPLEX_UNIT_SP,
        )
        drawIcon = label in setOf("⌫", "删除", "🎙", "停止", "识别", "重试", "↔", "左手", "右手", "⇧", "⇧ 大写", "⌃", "⌄", "‹", "›")
            || (label == "😊" && description == "表情键盘")
        setTextColor(keyText())
        backgroundTintList = null
        fun surface(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(7).toFloat()
        }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), surface(keyPressed()))
            val function = !preview && secondaryLabel == null && label != "空格" && label != "选词"
            addState(intArrayOf(), surface(if (function && !highContrast && !darkMode()) 0xFFB6BBC5.toInt() else keyBackground()))
        }
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
                        stopDeleteRepeat()
                        deleteRepeat = repeat
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
        if (passwordScope) return
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
        if (hasComposition && !resetCompositionForModeChange()) return
        currentInputConnection.commitText(text, 1)
        candidates.removeAllViews()
        updateCandidateHeader()
        renderKeyboard()
    }

    private fun stopDeleteRepeat() {
        deleteRepeat?.let { uiHandler.removeCallbacks(it) }
        deleteRepeat = null
        dismissKeyPreview()
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
        if (keyboardPage == KeyboardPage.HANDWRITING) { handwritingPanel?.backspace(); return }
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
        val wasExpanded = candidatePanelExpanded
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
                action.has("CandidatePage") -> {
                    val page = action.getJSONObject("CandidatePage")
                    nativeCandidatePage = page.getInt("index")
                    nativeHasNextPage = page.getBoolean("has_next")
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
        if (candidatePanelExpanded || wasExpanded) renderKeyboard()
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
        text = candidateLabel(display, annotation, highlighted)
        contentDescription = getString(R.string.candidate_description, display)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, KeyboardTypography.CANDIDATE * keyboardTextSize.scale)
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
        val nativePaging = selectedEngine() == "rime"
        val availableDp = (keyboardRows.width.takeIf { it > 0 }
            ?: resources.configuration.screenWidthDp.let { dp(it) }) / resources.displayMetrics.density
        val columns = KeyboardTypography.expandedColumns(availableDp, keyboardTextSize)
        val pageSize = columns * 4
        val maxPage = ((lastCandidates.length() - 1).coerceAtLeast(0)) / pageSize
        candidatePanelPage = candidatePanelPage.coerceIn(0, maxPage)
        val start = candidatePanelPage * pageSize
        val end = minOf(lastCandidates.length(), start + pageSize)
        val rowCount = ((end - start + columns - 1) / columns).coerceAtLeast(1)
        repeat(rowCount) { rowIndex ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = keyboardRowParams()
            }
            repeat(columns) { column ->
                val index = start + rowIndex * columns + column
                if (index < end) {
                    val candidate = lastCandidates.getJSONObject(index)
                    val display = candidate.getString("display_text")
                    val annotation = if (candidate.isNull("annotation")) "" else candidate.optString("annotation", "")
                    row.addView(key(
                        display,
                        labelSizeSp = KeyboardTypography.CANDIDATE,
                        description = getString(R.string.candidate_description, display),
                    ) { selectCandidate(candidate.getString("id")) }.apply {
                        maxLines = 2
                        text = candidateLabel(display, annotation, false, onKey = true)
                    })
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
                if (nativePaging && nativeCandidatePage > 0) {
                    command(6)
                } else if (!nativePaging && candidatePanelPage > 0) {
                    candidatePanelPage--
                    renderKeyboard()
                }
            })
            addView(key("返回键盘", 2f, 14f) { toggleCandidatePanel() })
            addView(key(if (nativePaging) "第${nativeCandidatePage + 1}页" else "${candidatePanelPage + 1}/${maxPage + 1}", 1f, 13f, "候选页码") {})
            addView(key("›", 1f, 22f, "下一页候选") {
                if (nativePaging && nativeHasNextPage) {
                    command(7)
                } else if (!nativePaging && candidatePanelPage < maxPage) {
                    candidatePanelPage++
                    renderKeyboard()
                }
            })
            layoutParams = keyboardRowParams()
        })
    }

    private fun updateCandidateHeader() {
        if (!::modeIndicator.isInitialized) return
        val voiceStatus = when (voiceState) {
            VoiceState.LISTENING -> getString(R.string.voice_listening)
            VoiceState.PROCESSING -> getString(R.string.voice_processing)
            VoiceState.ERROR -> getString(R.string.voice_unavailable)
            VoiceState.IDLE -> null
        }
        // Keep recording/processing/error feedback, but never prepend a mode label.
        modeIndicator.text = voiceStatus.orEmpty()
        modeIndicator.visibility = if (voiceStatus == null) View.GONE else View.VISIBLE
        modeIndicator.contentDescription = modeIndicator.text
        candidateExpandKey.visibility = if (lastCandidates.length() > CANDIDATE_STRIP_SIZE) View.VISIBLE else View.GONE
        candidateExpandKey.text = if (candidatePanelExpanded) "⌃" else "⌄"
    }

    private fun candidateLabel(display: String, annotation: String, highlighted: Boolean, onKey: Boolean = false): CharSequence {
        if (annotation.isBlank()) return display
        return SpannableString("$display\n$annotation").apply {
            val start = display.length + 1
            setSpan(RelativeSizeSpan(KeyboardTypography.ANNOTATION / KeyboardTypography.CANDIDATE), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            val color = if (highContrast && (highlighted || onKey)) Color.BLACK else if (highContrast) candidateText() else modeText()
            setSpan(ForegroundColorSpan(color), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
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
        else -> 0xFFD9DBE1.toInt()
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
            showImeMessage("请先在知墨输入法（Zhimo）设置中授予麦克风权限")
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
        val recognizer = runCatching { obtainSpeechRecognizer(allowNetwork) }.getOrNull() ?: run {
            voiceState = VoiceState.ERROR
            updateVoiceUi()
            showImeMessage("语音识别器不可用")
            return
        }
        if (speechRecognizer !== recognizer) {
            speechRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (speechRecognizer !== recognizer || !acceptingSpeechResults) return
                    voiceState = VoiceState.LISTENING
                    updateVoiceUi()
                }
                override fun onBeginningOfSpeech() {
                    if (speechRecognizer !== recognizer || !acceptingSpeechResults) return
                    voiceState = VoiceState.LISTENING
                    updateVoiceUi()
                }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    if (speechRecognizer !== recognizer || !acceptingSpeechResults) return
                    voiceState = VoiceState.PROCESSING
                    updateVoiceUi()
                }
                override fun onError(error: Int) {
                    if (speechRecognizer !== recognizer || !acceptingSpeechResults) return
                    acceptingSpeechResults = false
                    voiceState = VoiceState.ERROR
                    updateVoiceUi()
                    showImeMessage("语音识别失败（$error）")
                    uiHandler.postDelayed({
                        if (speechRecognizer === recognizer && voiceState == VoiceState.ERROR) {
                            voiceState = VoiceState.IDLE
                            updateVoiceUi()
                        }
                    }, 1800L)
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onPartialResults(results: Bundle?) {
                    if (speechRecognizer === recognizer) deliverSpeech(results, false)
                }
                override fun onResults(results: Bundle?) {
                    if (speechRecognizer === recognizer) deliverSpeech(results, true)
                }
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
        // A fresh recognizer gives every request a distinct callback identity.
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
