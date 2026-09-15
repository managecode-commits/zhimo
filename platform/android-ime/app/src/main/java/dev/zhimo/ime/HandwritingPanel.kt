// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.FrameLayout
import android.widget.ScrollView
import android.view.View
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList

/** Owns provider lifetime and rejects callbacks after its editor/view is detached. */
class HandwritingPanel(
    context: Context,
    private val foreground: Int,
    private val surfaceColor: Int,
    private val textScale: Float,
    private val candidateRow: LinearLayout,
    private val commit: (String) -> Boolean,
    private val providerFactory: (String) -> HandwritingProvider = { language ->
        val single = when {
            language.startsWith("digits") || language.startsWith("letters") -> ImageHandwritingProvider(context)
            language.startsWith("zh-CN") && !context.getSharedPreferences("zhimo", 0).getBoolean("handwriting_image_experimental", true) -> BundledHandwritingProvider(context)
            else -> FusedHandwritingProvider(ImageHandwritingProvider(context), BundledHandwritingProvider(context))
        }
        if (language.endsWith("-line")) LineHandwritingProvider(single) else single
    },
    private val onLiteral: (String) -> Unit = {},
    private val onDelete: () -> Unit = {},
    initialLineMode: Boolean = context.getSharedPreferences("zhimo", 0).getBoolean("handwriting_image_experimental", true),
) : LinearLayout(context) {
    private val session = HandwritingSession()
    private val handler = Handler(Looper.getMainLooper())
    private var provider: HandwritingProvider? = null
    private var generation = 0L
    private var ready = false
    private var busy = false
    private var pendingRecognition = false
    private var expanded = false
    private var correctionDraft: MutableList<String>? = null
    private var correctionSource: String? = null
    private var largePad = false
    private var lineMode = initialLineMode
    private val preferences = context.getSharedPreferences("zhimo", 0)
    private var characterMode = if (!preferences.getBoolean("handwriting_image_experimental", true)) HandwritingCharacterMode.CHINESE
        else HandwritingCharacterMode.entries.firstOrNull { it.name == preferences.getString("handwriting_character_mode", "MIXED") }
            ?: HandwritingCharacterMode.MIXED
    private var manualBoundaries: List<Float>? = null
    private lateinit var boundaryButton: Button
    private val status = TextView(context).apply { textSize = 14f; setTextColor(this@HandwritingPanel.foreground) }
    private val language = TextView(context).apply { text = "中文单字"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(this@HandwritingPanel.foreground) }
    private val canvas: HandwritingCanvas = HandwritingCanvas(context, foreground, beforeStroke = { session.active }) { stroke ->
        handler.removeCallbacks(recognize)
        pendingRecognition = false
        session.invalidate(); hideGrid(); candidateRow.removeAllViews(); status.text = "书写中…"
        if (stroke != null && session.add(stroke)) {
            handler.postDelayed(recognize, 400)
        }
    }
    private val recognize = Runnable { recognizeNow() }
    private val area = FrameLayout(context)
    private val grid = ScrollView(context).apply { visibility = View.GONE }
    init {
        updateCanvasMode()
        language.contentDescription = "切换手写识别范围"
        language.setOnClickListener {
            if (canvas.isWriting || canvas.editingBoundaries) { status.text = "请先完成当前笔画或分字"; return@setOnClickListener }
            characterMode = HandwritingCharacterMode.entries[(characterMode.ordinal + 1) % HandwritingCharacterMode.entries.size]
            preferences.edit().putString("handwriting_character_mode", characterMode.name).apply()
            handler.removeCallbacks(recognize); pendingRecognition = false
            session.invalidate(); hideGrid(); candidateRow.removeAllViews()
            loadProvider() // Keeps ink/boundaries. Generation guards discard results from the previous alphabet.
        }
        canvas.onBoundariesChanged = { cuts ->
            manualBoundaries = cuts; session.invalidate(); candidateRow.removeAllViews()
        }
        orientation = VERTICAL; setBackgroundColor(surfaceColor)
        val controls = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(language, LayoutParams(0, dp(48), 1f))
            addView(styledButton("撤一笔", 15f).apply { setOnClickListener { undo() } }, LayoutParams(0, dp(48), 1f))
            addView(styledButton("清空", 15f).apply { setOnClickListener { clear() } }, LayoutParams(0, dp(48), 1f))
            addView(styledButton("放大", 15f).apply {
                contentDescription = "调整手写区域大小"
                setOnClickListener {
                    if (session.snapshot(canvas.width.toFloat(), canvas.height.toFloat()) != null) {
                        status.text = "请先选字或清空，再调整画板"
                    } else {
                        largePad = !largePad; text = if (largePad) "缩小" else "放大"
                        area.layoutParams.height = areaHeight(); area.requestLayout()
                    }
                }
            }, LayoutParams(0, dp(48), 1f))
        }
        addView(controls)
        status.minHeight = dp(32)
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        area.addView(canvas, FrameLayout.LayoutParams(-1, -1))
        area.addView(grid, FrameLayout.LayoutParams(-1, -1))
        if (resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            (canvas.layoutParams as FrameLayout.LayoutParams).marginEnd = dp(52)
            (grid.layoutParams as FrameLayout.LayoutParams).marginEnd = dp(52)
            area.addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(styledButton("⌫", 24f).apply {
                    contentDescription = "手写删除：有笔迹撤笔，无笔迹删字"
                    setOnClickListener { backspace() }
                }, LayoutParams(-1, 0, 1f))
                listOf("，", "。", "？", "！").forEach { value ->
                    addView(styledButton(value, 25f).apply {
                        contentDescription = "手写标点：$value"
                        setOnClickListener {
                            if (hasUncommitted())
                                status.text = "请先选择汉字，再输入标点"
                            else onLiteral(value)
                        }
                    }, LayoutParams(-1, 0, 1f))
                }
            }, FrameLayout.LayoutParams(dp(48), -1, Gravity.END))
        }
        addView(area, LayoutParams(-1, areaHeight()))
        addView(LinearLayout(context).apply {
            addView(styledButton(if (lineMode) "自由连写" else "单字", 15f).apply {
                contentDescription = "切换单字或自由连写"
                setOnClickListener {
                    if (!canLeave()) return@setOnClickListener
                    lineMode = !lineMode; text = if (lineMode) "自由连写" else "单字"
                    manualBoundaries = null; canvas.boundaries = emptyList()
                    updateCanvasMode(); loadProvider()
                }
            }, LayoutParams(0, dp(48), 1f))
            boundaryButton = styledButton("调整分字", 15f).apply {
                setOnClickListener {
                    if (!lineMode || canvas.isWriting || !hasUncommitted()) { status.text = "请先横向书写，再调整字界"; return@setOnClickListener }
                    handler.removeCallbacks(recognize); session.invalidate(); hideGrid(); candidateRow.removeAllViews()
                    if (!canvas.editingBoundaries) {
                        // Derive from current ink, not an older request still being recognized.
                        manualBoundaries = manualBoundaries ?: session.snapshot(canvas.width.toFloat(), canvas.height.toFloat())?.let {
                            runCatching { HandwritingSegmentation.plans(it).first().cuts }.getOrDefault(emptyList())
                        }.orEmpty()
                        canvas.boundaries = manualBoundaries.orEmpty(); canvas.editingBoundaries = true
                        text = "完成分字"; status.text = "点空隙加线，拖动移线，点线删除；完成后识别"
                    } else {
                        canvas.editingBoundaries = false; text = "调整分字"; recognizeNow()
                    }
                }
            }
            addView(boundaryButton, LayoutParams(0, dp(48), 1f))
            addView(styledButton("自动分字", 15f).apply {
                setOnClickListener {
                    if (canvas.isWriting) return@setOnClickListener
                    manualBoundaries = null; canvas.boundaries = emptyList(); canvas.editingBoundaries = false
                    boundaryButton.text = "调整分字"; session.invalidate(); hideGrid(); candidateRow.removeAllViews()
                    handler.removeCallbacks(recognize); recognizeNow()
                }
            }, LayoutParams(0, dp(48), 1f))
            addView(styledButton("整句上屏", 15f).apply {
                setOnClickListener { confirmFirstOr { status.text = "请先书写" } }
            }, LayoutParams(0, dp(48), 1f))
        })
        status.setOnClickListener { if (!ready && !busy) loadProvider() }
        loadProvider()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun areaHeight(): Int {
        val normal = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 120 else 240
        return dp(if (largePad) (resources.configuration.screenHeightDp - 220).coerceIn(normal, 480) else normal)
    }
    private fun styledButton(label: String, size: Float) = Button(context).apply {
        text = label; textSize = size; setTextColor(this@HandwritingPanel.foreground); isAllCaps = false; maxLines = 1
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(dp(3), 0, dp(3), 0); backgroundTintList = null
        stateListAnimator = null; elevation = 0f
        val shape = GradientDrawable().apply { setColor(surfaceColor); cornerRadius = dp(8).toFloat() }
        background = RippleDrawable(ColorStateList.valueOf(0x307f7f7f), shape, null)
    }
    private fun loadProvider() {
        generation++; provider?.close(); provider = null; busy = false; ready = false
        status.text = "正在准备离线模型…"
        val token = generation
        val selected = runCatching { providerFactory(characterMode.providerLanguage + if (lineMode) "-line" else "") }.getOrNull()
        provider = selected
        language.text = "${characterMode.label} ↻"
        if (selected == null) { status.text = "模型不可用，点此重试"; return }
        selected.available { ok ->
            if (session.active && token == generation) {
                ready = ok
                status.text = if (ok) readyHint() else "模型不可用，点此重试"
                if (ok) recognizeNow()
            }
        }
    }
    private fun recognizeNow() {
        if (!ready || !session.active || canvas.isWriting || canvas.editingBoundaries) return
        if (busy) { pendingRecognition = true; return }
        val request = session.snapshot(canvas.width.toFloat(), canvas.height.toFloat())
            ?.copy(boundaries = if (lineMode) manualBoundaries else null, characterMode = characterMode) ?: return
        val token = generation
        busy = true; status.text = "正在识别…"
        provider?.recognize(request) { result ->
            if (!session.active || token != generation) return@recognize
            busy = false
            if (pendingRecognition) {
                pendingRecognition = false
                handler.post(recognize)
            }
            if (request.revision != session.revision) {
                // A new stroke may still be in progress. Only its UP event schedules the next request.
                return@recognize
            }
            result.fold(onSuccess = { values ->
                if (session.accept(request.revision, if (lineMode) values else HandwritingShapeRanking.rank(request, values))) {
                    hideGrid()
                    showCandidates(request.revision)
                }
            }, onFailure = { status.text = if (lineMode) "识别未完成，笔迹已保留；可调整分字后重试" else "识别失败，请补笔或清空重试" })
        }
    }
    private fun showCandidates(revision: Long) {
        if (!session.active || revision != session.revision) return
        candidateRow.removeAllViews()
        val showCorrection = session.choices.firstOrNull()?.let { it.codePointCount(0, it.length) > 1 } == true &&
            correctionChoices(revision).isNotEmpty()
        val viewport = (candidateRow.parent as? View)?.width?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels - dp(52)
        val previewCount = if (showCorrection) ((viewport - dp(108)) / dp(48)).coerceIn(3, 5) else 5
        val cell = minOf(candidateWidth(), ((viewport - dp(if (showCorrection) 108 else 48)) / previewCount).coerceAtLeast(dp(48)))
        session.choices.take(previewCount).forEachIndexed { index, value ->
            candidateRow.addView(candidateKey(value, index, revision), LayoutParams(cell, -1))
        }
        if (session.choices.size > previewCount) candidateRow.addView(styledButton(if (expanded) "收起" else "更多", 14f).apply {
            contentDescription = "更多手写候选"
            setOnClickListener {
                if (session.active && revision == session.revision) {
                    if (expanded) hideGrid() else showGrid(revision)
                    showCandidates(revision)
                }
            }
        }, LayoutParams(dp(48), -1))
        if (showCorrection) candidateRow.addView(styledButton("逐字改", 14f).apply {
            contentDescription = "逐字纠正手写候选"
            setOnClickListener { startCorrection(revision) }
        }, LayoutParams(dp(60), -1))
        (candidateRow.parent as? android.widget.HorizontalScrollView)?.scrollTo(0, 0)
        status.text = when {
            session.choices.isEmpty() -> "未识别，请补笔或清空重写"
            correctionDraft != null -> "点字位再选替换字 · 确认后才上屏"
            expanded -> "上下滑动选字 · 单字与连写结果共同候选"
            lineMode -> "单字与连写共同候选 · 长按纠正，字数不对可调整分字"
            else -> "点选上屏 · 没有合适的字可补笔"
        }
    }
    private fun candidateWidth() = dp(maxOf(if (lineMode) 116 else 48, ((if (lineMode) 84 else 32) * textScale * resources.configuration.fontScale + 12).toInt()))
    private fun candidateKey(value: String, index: Int, revision: Long) = styledButton(value, KeyboardTypography.CANDIDATE * textScale).apply {
        setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.END
        contentDescription = "手写候选：$value"
        setOnClickListener {
            val chosen = session.candidate(revision, index) ?: return@setOnClickListener
            if (commit(chosen)) clear() else status.text = "未能输入，请重新选择候选"
        }
        setOnLongClickListener { startCorrection(revision, value) }
    }
    private fun correctionChoices(revision: Long): List<List<String>> {
        val line = provider as? LineHandwritingProvider ?: return emptyList()
        if (!session.active || session.revision != revision || line.characterRevision != revision) return emptyList()
        val parts = (correctionSource ?: session.choices.firstOrNull())?.let { line.choicesFor(it) }
            ?: line.characterChoices
        return if (parts.any { it.isEmpty() }) emptyList() else parts
    }
    private fun startCorrection(revision: Long, value: String? = session.choices.firstOrNull()): Boolean {
        if (correctionChoices(revision).isEmpty() || value == null) return false
        val choices = (provider as? LineHandwritingProvider)?.choicesFor(value).orEmpty()
        if (choices.isEmpty()) return false
        val letters = value.codePoints().toArray().map { String(Character.toChars(it)) }
        if (letters.size != choices.size) { status.text = "该候选字数不同，请先调整分字再逐字改"; return true }
        correctionDraft = letters.toMutableList()
        correctionSource = value
        showCorrection(revision, 0)
        return true
    }
    private fun showCorrection(revision: Long, position: Int) {
        val choices = correctionChoices(revision)
        val draft = correctionDraft ?: return
        if (position !in choices.indices) return
        expanded = true; canvas.visibility = View.INVISIBLE; grid.visibility = View.VISIBLE
        val rows = LinearLayout(context).apply { orientation = VERTICAL }
        val positions = LinearLayout(context)
        draft.forEachIndexed { index, letter ->
            positions.addView(styledButton("${index + 1}:$letter", 20f).apply {
                contentDescription = "纠正第${index + 1}字：$letter"
                isSelected = index == position
                setTypeface(typeface, if (index == position) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setOnClickListener { showCorrection(revision, index) }
            }, LayoutParams(0, dp(52), 1f))
        }
        rows.addView(positions)
        rows.addView(styledButton("确认上屏：${draft.joinToString("")}", 20f).apply {
            contentDescription = "确认逐字纠正上屏"
            setOnClickListener { commitCorrection(revision) }
        }, LayoutParams(-1, dp(52)))
        rows.addView(styledButton("返回候选", 16f).apply {
            contentDescription = "返回手写候选"
            setOnClickListener { if (session.active && session.revision == revision) { hideGrid(); showCandidates(revision) } }
        }, LayoutParams(-1, dp(48)))
        choices[position].chunked(4).forEach { letters ->
            val row = LinearLayout(context)
            letters.forEach { letter ->
                row.addView(styledButton(letter, KeyboardTypography.CANDIDATE * textScale).apply {
                    contentDescription = "第${position + 1}字替换为：$letter"
                    setOnClickListener {
                        if (correctionChoices(revision).isNotEmpty() && correctionDraft === draft) {
                            draft[position] = letter; showCorrection(revision, position)
                        }
                    }
                }, LayoutParams(0, dp(56), 1f))
            }
            rows.addView(row)
        }
        grid.removeAllViews(); grid.addView(rows); grid.scrollTo(0, 0)
        showCandidates(revision)
        status.text = "正在纠正第${position + 1}字 · 替换不自动上屏"
    }
    private fun commitCorrection(revision: Long) {
        val draft = correctionDraft ?: return
        if (correctionChoices(revision).isEmpty()) return
        if (commit(draft.joinToString(""))) clear() else status.text = "未能输入，纠正结果和笔迹已保留"
    }
    private fun showGrid(revision: Long) {
        correctionDraft = null
        correctionSource = null
        expanded = true; canvas.visibility = View.INVISIBLE; grid.visibility = View.VISIBLE
        val rows = LinearLayout(context).apply { orientation = VERTICAL }
        val availableWidth = grid.width.takeIf { it > 0 } ?: (area.width - dp(52))
        val columns = if (lineMode) {
            if (availableWidth >= dp((220 * textScale * resources.configuration.fontScale).toInt())) 2 else 1
        } else (availableWidth / candidateWidth()).coerceIn(3, 8)
        val rowHeight = dp(maxOf(52, (KeyboardTypography.CANDIDATE * textScale * resources.configuration.fontScale * 1.4f + 12).toInt()))
        session.choices.chunked(columns).forEachIndexed { rowIndex, values ->
            val row = LinearLayout(context)
            values.forEachIndexed { column, value ->
                row.addView(candidateKey(value, rowIndex * columns + column, revision), LayoutParams(0, rowHeight, 1f))
            }
            repeat(columns - values.size) { row.addView(View(context), LayoutParams(0, rowHeight, 1f)) }
            rows.addView(row)
        }
        grid.removeAllViews(); grid.addView(rows); grid.scrollTo(0, 0)
    }
    private fun hideGrid() {
        correctionDraft = null
        correctionSource = null
        expanded = false; grid.visibility = View.GONE; canvas.visibility = View.VISIBLE; grid.removeAllViews()
    }
    fun clear() {
        manualBoundaries = null
        if (::boundaryButton.isInitialized) boundaryButton.text = "调整分字"
        pendingRecognition = false; handler.removeCallbacks(recognize)
        session.clear(); canvas.clear(); hideGrid(); candidateRow.removeAllViews()
        if (ready) status.text = readyHint()
    }
    fun backspace() {
        if (canvas.isWriting || canvas.editingBoundaries) return
        if (hasUncommitted()) undo() else onDelete()
    }
    /** Explicit space/enter/voice action: never discard unfinished ink. */
    fun confirmFirstOr(action: () -> Unit) {
        if (!session.active || canvas.isWriting) return
        if (canvas.editingBoundaries) { status.text = "请先点完成分字，再选词上屏"; return }
        if (correctionDraft != null) { commitCorrection(session.revision); return }
        if (session.snapshot(canvas.width.toFloat(), canvas.height.toFloat()) == null) { action(); return }
        val chosen = session.candidate(session.revision, 0)
        if (chosen == null) { status.text = "请等待识别或补笔后选字"; return }
        if (commit(chosen)) clear() else status.text = "未能输入，请重新选择候选"
    }
    fun undo() {
        if (canvas.isWriting || canvas.editingBoundaries) return
        if (session.snapshot(canvas.width.toFloat(), canvas.height.toFloat()) == null) return
        handler.removeCallbacks(recognize); session.undo(); canvas.undo(); hideGrid(); candidateRow.removeAllViews()
        handler.postDelayed(recognize, 400)
    }
    fun dispose() {
        if (!session.active) return
        session.close(); handler.removeCallbacksAndMessages(null); canvas.clear(); hideGrid()
        provider?.close(); provider = null
    }
    override fun onDetachedFromWindow() { dispose(); super.onDetachedFromWindow() }

    fun hasUncommitted(): Boolean = canvas.isWriting || session.snapshot(canvas.width.toFloat(), canvas.height.toFloat()) != null
    fun canLeave(): Boolean {
        if (!hasUncommitted()) return true
        status.text = "请先上屏或清空手写，再切换"
        return false
    }
    private fun readyHint(): String {
        val range = when (characterMode) {
            HandwritingCharacterMode.MIXED -> "汉字、数字、大小写字母"
            HandwritingCharacterMode.CHINESE -> "汉字"
            HandwritingCharacterMode.DIGITS -> "数字 0–9（离线字形）"
            HandwritingCharacterMode.LETTERS -> "字母 A–Z / a–z（离线字形）"
        }
        return if (lineMode) "$range · 从左到右写1至4字" else "$range · 单字点选上屏"
    }
    private fun updateCanvasMode() {
        canvas.lineMode = lineMode
        canvas.contentDescription = if (lineMode) "连写区域，从左到右书写，不限格" else "手写区域，请一次书写一个汉字"
    }
}
