package dev.shurufa.ime

import android.inputmethodservice.InputMethodService
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray

class ShurufaInputMethodService : InputMethodService() {
    private var handle = 0L
    private lateinit var candidates: LinearLayout
    private var speechRecognizer: SpeechRecognizer? = null
    private var passwordScope = false

    override fun onCreate() {
        super.onCreate()
        handle = NativeIme.create(filesDir.absolutePath)
    }

    override fun onDestroy() {
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
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
        NativeIme.switchEngine(handle, if (pinyin) "pinyin.reference" else "latin")
        NativeIme.setPrivacy(handle, preferences.getBoolean("learning_enabled", true), false)
        NativeIme.setApplicationId(handle, attribute?.packageName ?: "")
        val variation = (attribute?.inputType ?: 0) and InputType.TYPE_MASK_VARIATION
        passwordScope = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        if (passwordScope) speechRecognizer?.cancel()
        val scope = when {
            passwordScope -> 1
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> 2
            variation == InputType.TYPE_TEXT_VARIATION_URI -> 3
            else -> 0
        }
        NativeIme.setScope(handle, scope)
        NativeIme.command(handle, 3)
    }

    override fun onFinishInput() {
        speechRecognizer?.cancel()
        if (handle != 0L) NativeIme.command(handle, 3)
        super.onFinishInput()
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        candidates = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(candidates)
        listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEach { keys ->
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                keys.forEach { character -> addView(key(character.toString())) }
            })
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(key("中/En") { toggleEngine() })
            addView(key("🎤") { startSystemDictation() })
            addView(key("Space") { command(2) })
            addView(key("⌫") { command(0) })
            addView(key("Enter") { command(1) })
        })
        return root
    }

    private var pinyin = false

    private fun toggleEngine() {
        pinyin = !pinyin
        NativeIme.switchEngine(handle, if (pinyin) "pinyin.reference" else "latin")
        renderActions()
    }

    private fun key(label: String, action: (() -> Unit)? = null) = Button(this).apply {
        text = label
        setOnClickListener { action?.invoke() ?: feed(label) }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun feed(text: String) {
        if (NativeIme.feed(handle, text) == 0) renderActions()
    }

    private fun command(value: Int) {
        if (NativeIme.command(handle, value) == 0) renderActions()
    }

    private fun renderActions() {
        val actions = JSONArray(NativeIme.actions(handle))
        candidates.removeAllViews()
        for (index in 0 until actions.length()) {
            val item = actions.get(index)
            if (item is String) {
                if (item == "CloseComposition") currentInputConnection.finishComposingText()
                continue
            }
            val action = item as org.json.JSONObject
            when {
                action.has("CommitText") -> currentInputConnection.commitText(action.getString("CommitText"), 1)
                action.has("UpdateComposition") -> {
                    val segments = action.getJSONObject("UpdateComposition").getJSONArray("segments")
                    val text = buildString {
                        for (segment in 0 until segments.length()) append(segments.getJSONObject(segment).getString("text"))
                    }
                    currentInputConnection.setComposingText(text, 1)
                }
                action.has("ShowCandidates") -> showCandidates(action.getJSONArray("ShowCandidates"))
            }
        }
    }

    private fun showCandidates(values: JSONArray) {
        for (index in 0 until minOf(values.length(), 5)) {
            val value = values.getJSONObject(index)
            candidates.addView(TextView(this).apply {
                text = value.getString("display_text")
                textSize = 18f
                setPadding(24, 12, 24, 12)
                setOnClickListener {
                    NativeIme.select(handle, value.getString("id"))
                    renderActions()
                }
            })
        }
    }

    private fun startSystemDictation() {
        if (passwordScope) return
        val recognizer = speechRecognizer ?: createSpeechRecognizer().also {
            speechRecognizer = it
            it.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onError(error: Int) = Unit
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
            recognizer.startListening(request)
        } catch (_: SecurityException) {
            // The companion settings activity must obtain RECORD_AUDIO first.
        }
    }

    private fun createSpeechRecognizer(): SpeechRecognizer =
        if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }

    private fun deliverSpeech(results: Bundle?, finalResult: Boolean) {
        val values = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        val text = values.firstOrNull() ?: return
        val confidence = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull() ?: Float.NaN
        NativeIme.speechResult(handle, text, java.util.Locale.getDefault().toLanguageTag(), confidence, finalResult)
        renderActions()
    }
}
