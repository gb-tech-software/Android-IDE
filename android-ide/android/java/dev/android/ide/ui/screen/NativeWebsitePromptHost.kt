package dev.android.ide.ui.screen

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.BaseAdapter
import android.widget.AbsListView
import android.widget.CheckBox
import android.widget.DatePicker
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.NumberPicker
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.TimePicker
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import dev.android.ide.browser.BrowserWebsitePromptAnswer
import dev.android.ide.browser.BrowserChoiceMode
import dev.android.ide.browser.BrowserWebsitePromptChoice
import dev.android.ide.browser.BrowserWebsitePromptKind
import dev.android.ide.browser.BrowserWebsitePromptRequest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

@Composable
internal fun NativeWebsitePromptHostComposable(
    prompt: BrowserWebsitePromptRequest,
    onAnswer: (String, BrowserWebsitePromptAnswer) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val systemFontScale = context.resources.configuration.fontScale.takeIf { it > 0f } ?: 1f
    val appFontScale = (LocalDensity.current.fontScale / systemFontScale).coerceIn(0.5f, 2f)
    val primaryColor = MaterialTheme.colorScheme.primary.toArgb()
    val errorColor = MaterialTheme.colorScheme.error.toArgb()
    val surfaceColor = MaterialTheme.colorScheme.surface.toArgb()
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val currentOnAnswer by rememberUpdatedState(onAnswer)
    val latestRevision by rememberUpdatedState(prompt.revision)
    DisposableEffect(prompt.id, prompt.revision, lifecycleOwner) {
        val renderedRevision = prompt.revision
        val dialog = runCatching {
            NativeWebsitePromptHost.show(context, prompt, primaryColor, errorColor, surfaceColor, onSurfaceColor, appFontScale) { answer ->
                currentOnAnswer(prompt.id, answer)
            }
        }.getOrNull()
        if (dialog == null) currentOnAnswer(prompt.id, BrowserWebsitePromptAnswer(accepted = false))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                dialog?.dismiss()
                currentOnAnswer(prompt.id, BrowserWebsitePromptAnswer(accepted = false))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (latestRevision != renderedRevision) {
                // A GeckoView update replaces the visual surface but keeps the same request ID.
                // Suppress the old dialog's dismiss callback so it cannot cancel the new revision.
                dialog?.setOnDismissListener(null)
                dialog?.setOnCancelListener(null)
                dialog?.dismiss()
            } else {
                dialog?.dismiss()
                currentOnAnswer(prompt.id, BrowserWebsitePromptAnswer(accepted = false))
            }
        }
    }
}

/**
 * Hosts GeckoView content prompts with platform Android controls.
 * GeckoView deliberately leaves prompt rendering to the embedding application;
 * keeping one native host prevents HTML controls from becoming unrelated Compose
 * fields and gives every prompt one dismissal/response path.
 */
internal object NativeWebsitePromptHost {
    fun show(
        context: Context,
        prompt: BrowserWebsitePromptRequest,
        primaryColor: Int,
        errorColor: Int,
        surfaceColor: Int,
        onSurfaceColor: Int,
        appFontScale: Float,
        onAnswer: (BrowserWebsitePromptAnswer) -> Unit,
    ): android.app.Dialog {
        val answered = AtomicBoolean(false)
        fun finish(answer: BrowserWebsitePromptAnswer) {
            if (answered.compareAndSet(false, true)) onAnswer(answer)
        }
        val dialog = when (prompt.kind) {
            BrowserWebsitePromptKind.CHOICE -> choiceDialog(context, prompt, ::finish)
            BrowserWebsitePromptKind.COLOR -> colorDialog(context, prompt, primaryColor, ::finish)
            BrowserWebsitePromptKind.DATE_TIME -> dateTimeDialog(context, prompt, errorColor, ::finish)
            else -> standardDialog(context, prompt, ::finish)
        }
        dialog.setOnDismissListener { finish(BrowserWebsitePromptAnswer(accepted = false)) }
        dialog.show()
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
            cornerRadius = dp(context, 24).toFloat()
            setColor(surfaceColor)
        })
        styleNativeText(dialog.window?.decorView, appFontScale, onSurfaceColor, primaryColor, errorColor)
        dialog.findViewById<TextView>(android.R.id.alertTitle)?.setTextColor(onSurfaceColor)
        dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(onSurfaceColor)
        (dialog as? AlertDialog)?.apply {
            getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(primaryColor)
            getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(primaryColor)
            getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(primaryColor)
        }
        if (prompt.kind in setOf(
                BrowserWebsitePromptKind.CHOICE,
                BrowserWebsitePromptKind.COLOR,
                BrowserWebsitePromptKind.DATE_TIME,
            )
        ) styleBrowserPickerDialog(dialog, context, prompt)
        return dialog
    }

    private fun styleBrowserPickerDialog(
        dialog: android.app.Dialog,
        context: Context,
        prompt: BrowserWebsitePromptRequest,
    ) {
        val metrics = context.resources.displayMetrics
        val widthFraction = when {
            prompt.kind == BrowserWebsitePromptKind.CHOICE -> .92f
            prompt.kind == BrowserWebsitePromptKind.COLOR -> .90f
            prompt.kind == BrowserWebsitePromptKind.DATE_TIME && prompt.inputType == "time" -> .90f
            prompt.kind == BrowserWebsitePromptKind.DATE_TIME && prompt.inputType == "datetime-local" -> .82f
            prompt.kind == BrowserWebsitePromptKind.DATE_TIME && prompt.inputType == "date" -> .72f
            else -> .76f
        }
        val height = if (prompt.kind == BrowserWebsitePromptKind.CHOICE && prompt.choices.size > 6) {
            (metrics.heightPixels * .90f).roundToInt()
        } else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        dialog.window?.apply {
            setGravity(android.view.Gravity.CENTER)
            setLayout((metrics.widthPixels * widthFraction).roundToInt(), height)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.32f)
        }
    }

    private fun styleNativeText(view: View?, scale: Float, onSurfaceColor: Int, primaryColor: Int, errorColor: Int) {
        if (view == null) return
        if (view is TextView) {
            if (scale != 1f && view.textSize > 0f) {
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.textSize * scale)
            }
            view.setTextColor(
                when (view.tag) {
                    PROMPT_ERROR_TAG -> errorColor
                    PROMPT_PRIMARY_TAG -> primaryColor
                    else -> onSurfaceColor
                },
            )
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                styleNativeText(view.getChildAt(index), scale, onSurfaceColor, primaryColor, errorColor)
            }
        }
    }

    private fun standardDialog(
        context: Context,
        prompt: BrowserWebsitePromptRequest,
        finish: (BrowserWebsitePromptAnswer) -> Unit,
    ): AlertDialog {
        val isJavaScriptDialog = prompt.kind in setOf(
            BrowserWebsitePromptKind.ALERT,
            BrowserWebsitePromptKind.CONFIRM,
            BrowserWebsitePromptKind.TEXT,
        )
        val title = when {
            isJavaScriptDialog && prompt.origin.isNotBlank() -> "${prompt.origin} says"
            isJavaScriptDialog -> "JavaScript"
            prompt.kind == BrowserWebsitePromptKind.BEFORE_UNLOAD -> "Leave site?"
            else -> prompt.title ?: defaultTitle(prompt.kind)
        }
        val builder = AlertDialog.Builder(context)
            .setTitle(title)
        val content = standardContent(context, prompt)
        builder.setView(content)
        if (prompt.kind != BrowserWebsitePromptKind.ALERT) {
            builder.setNegativeButton(dismissLabel(prompt.kind), null)
        }
        builder.setPositiveButton(confirmLabel(prompt.kind)) { _, _ ->
            finish(
                BrowserWebsitePromptAnswer(
                    accepted = true,
                    value = content.findViewWithTag<EditText>(VALUE_TAG)?.text?.toString(),
                    username = content.findViewWithTag<EditText>(USERNAME_TAG)?.text?.toString(),
                    password = content.findViewWithTag<EditText>(PASSWORD_TAG)?.text?.toString(),
                ),
            )
        }
        return builder.create().apply {
            val keyboardInput = when (prompt.kind) {
                BrowserWebsitePromptKind.TEXT -> content.findViewWithTag<EditText>(VALUE_TAG)
                BrowserWebsitePromptKind.AUTH -> content.findViewWithTag<EditText>(if (prompt.passwordOnly) PASSWORD_TAG else USERNAME_TAG)
                else -> null
            }
            if (keyboardInput != null) setOnShowListener {
                keyboardInput.requestFocus()
                window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                keyboardInput.post {
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                        ?.showSoftInput(keyboardInput, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
    }

    private fun standardContent(context: Context, prompt: BrowserWebsitePromptRequest): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 24), dp(context, 24), dp(context, 24))
            if (prompt.kind !in setOf(
                    BrowserWebsitePromptKind.ALERT,
                    BrowserWebsitePromptKind.CONFIRM,
                    BrowserWebsitePromptKind.TEXT,
                    BrowserWebsitePromptKind.BEFORE_UNLOAD,
                    BrowserWebsitePromptKind.REPOST,
                )
            ) prompt.origin.takeIf(String::isNotBlank)?.let { addText(context, it, false) }
            prompt.message?.takeIf(String::isNotBlank)?.let { addText(context, it, false) }
            when (prompt.kind) {
                BrowserWebsitePromptKind.TEXT -> addView(
                    EditText(context).apply {
                        tag = VALUE_TAG
                        setSingleLine(true)
                        hint = "Response"
                        setText(prompt.defaultValue.orEmpty())
                        selectAll()
                    },
                )
                BrowserWebsitePromptKind.AUTH -> {
                    if (!prompt.passwordOnly) addView(
                        EditText(context).apply {
                            tag = USERNAME_TAG
                            setSingleLine(true)
                            hint = "Username"
                            setText(prompt.defaultUsername.orEmpty())
                        },
                    )
                    addView(
                        EditText(context).apply {
                            tag = PASSWORD_TAG
                            setSingleLine(true)
                            hint = "Password"
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                            setText(prompt.defaultPassword.orEmpty())
                        },
                    )
                }
                else -> Unit
            }
        }

    private fun choiceDialog(
        context: Context,
        prompt: BrowserWebsitePromptRequest,
        finish: (BrowserWebsitePromptAnswer) -> Unit,
    ): AlertDialog {
        val mode = prompt.choiceMode ?: if (prompt.multiple) BrowserChoiceMode.MULTIPLE else BrowserChoiceMode.SINGLE
        val selected = prompt.choices.filter { it.selected && !it.separator && !it.group }
            .mapTo(LinkedHashSet()) { it.id }
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val list = ListView(context).apply {
            choiceMode = if (mode == BrowserChoiceMode.MULTIPLE) ListView.CHOICE_MODE_MULTIPLE else ListView.CHOICE_MODE_SINGLE
            val desired = dp(context, prompt.choices.size * 52).coerceAtLeast(dp(context, 112))
            val cap = (context.resources.displayMetrics.heightPixels * .84f).roundToInt()
            layoutParams = LinearLayout.LayoutParams(-1, desired.coerceAtMost(cap))
        }
        val adapter = NativeChoiceAdapter(context, prompt.choices, selected, mode == BrowserChoiceMode.MULTIPLE)
        list.adapter = adapter
        val builder = AlertDialog.Builder(context).setView(content)
        if (mode != BrowserChoiceMode.MENU) {
            prompt.title?.let { builder.setTitle(it) }
            builder.setNegativeButton("Cancel", null)
                .setPositiveButton(if (mode == BrowserChoiceMode.MULTIPLE) "OK" else "OK") { _, _ ->
                    finish(BrowserWebsitePromptAnswer(accepted = true, selectedIds = selected.toList()))
                }
        }
        val dialog = builder.create()
        list.setOnItemClickListener { _, _, position, _ ->
            val choice = prompt.choices[position]
            if (choice.separator || choice.group || choice.disabled) return@setOnItemClickListener
            if (mode == BrowserChoiceMode.MENU) {
                selected.clear()
                selected.add(choice.id)
                finish(BrowserWebsitePromptAnswer(accepted = true, selectedIds = selected.toList()))
                dialog.dismiss()
            } else if (mode == BrowserChoiceMode.MULTIPLE) {
                if (!selected.add(choice.id)) selected.remove(choice.id)
            } else {
                selected.clear()
                selected.add(choice.id)
            }
            adapter.notifyDataSetChanged()
        }
        content.addView(list)
        return dialog
    }

    /** Native ListView rows retain hierarchy metadata without converting choices to text-only fields. */
    private class NativeChoiceAdapter(
        private val context: Context,
        private val choices: List<BrowserWebsitePromptChoice>,
        private val selected: Set<String>,
        private val multiple: Boolean,
    ) : BaseAdapter() {
        override fun getCount(): Int = choices.size
        override fun getItem(position: Int): BrowserWebsitePromptChoice = choices[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, recycled: View?, parent: ViewGroup?): View {
            val choice = getItem(position)
            if (choice.separator) return View(context).apply {
                setBackgroundColor(0x33000000)
                layoutParams = AbsListView.LayoutParams(-1, dp(context, 1))
            }
            if (choice.group) return TextView(context).apply {
                text = choice.label
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(context, choice.depth * 16 + 8), dp(context, 12), dp(context, 8), dp(context, 8))
                isEnabled = false
            }
            val control = if (multiple) CheckBox(context) else RadioButton(context)
            return control.apply {
                text = choice.label
                isEnabled = !choice.disabled
                isChecked = choice.id in selected
                isClickable = false
                isFocusable = false
                setPadding(dp(context, choice.depth * 16 + 8), dp(context, 8), dp(context, 8), dp(context, 8))
            }
        }
    }

    private fun colorDialog(
        context: Context,
        prompt: BrowserWebsitePromptRequest,
        primaryColor: Int,
        finish: (BrowserWebsitePromptAnswer) -> Unit,
    ): AlertDialog {
        // Android has no compatible complete system color-picker API at this minSdk.
        // Keep this browser-native flow local: Suggestions first, then an HSV editor.
        val initial = parseColor(prompt.defaultValue)
        val hsv = FloatArray(3).also { Color.colorToHSV(initial, it) }
        val preview = View(context)
        val chosenColorLabel = TextView(context).apply { text = "Chosen color"; textSize = 16f }
        val modeToggle = Button(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(primaryColor)
            tag = PROMPT_PRIMARY_TAG
            isAllCaps = false
        }
        val modeContent = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val swatches = mutableListOf<Pair<Int, View>>()
        val hsvBars = mutableListOf<Pair<Int, SeekBar>>()
        fun colorNow(): Int = Color.HSVToColor(hsv)
        fun refreshPreview() {
            val color = colorNow()
            preview.background = GradientDrawable().apply {
                cornerRadius = dp(context, 4).toFloat()
                setColor(color)
                setStroke(dp(context, 1), 0xff777777.toInt())
            }
            swatches.forEach { (swatchColor, view) ->
                view.background = GradientDrawable().apply {
                    cornerRadius = dp(context, 3).toFloat()
                    setColor(swatchColor)
                    setStroke(dp(context, if (swatchColor == color) 3 else 1), if (swatchColor == color) Color.BLACK else 0xff777777.toInt())
                }
            }
            hsvBars.forEach { (index, bar) ->
                val colors = when (index) {
                    0 -> intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED)
                    1 -> intArrayOf(
                        Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2])),
                        Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2])),
                    )
                    else -> intArrayOf(
                        Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 0f)),
                        Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)),
                    )
                }
                bar.progressDrawable = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors)
                bar.progress = if (index == 0) hsv[index].roundToInt().coerceIn(0, 359)
                else (hsv[index] * 100).roundToInt().coerceIn(0, 100)
            }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 4))
            addView(modeContent)
            val chosenRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(modeToggle, LinearLayout.LayoutParams(0, -2, 1f))
                addView(chosenColorLabel)
                addView(preview, LinearLayout.LayoutParams(dp(context, 40), dp(context, 40)).apply {
                    leftMargin = dp(context, 12)
                    rightMargin = dp(context, 4)
                })
            }
            addView(chosenRow)
        }
        val supplied = prompt.predefinedValues.mapNotNull(::parseColorOrNull)
        val palette = (listOf("#ff0000", "#00ffff", "#0000ff", "#00ff00", "#ff00ff", "#ffff00", "#000000", "#ffffff")
            .mapNotNull(::parseColorOrNull) + supplied).distinct().take(12)
        var customMode = false
        fun showSuggestions() {
            swatches.clear()
            hsvBars.clear()
            modeContent.removeAllViews()
            modeToggle.text = "Custom"
            val grid = GridLayout(context).apply { columnCount = 4 }
            palette.forEach { color ->
                val swatch = View(context).apply {
                    contentDescription = cssColor(color)
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = dp(context, 42)
                        height = dp(context, 42)
                        setMargins(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
                    }
                    setOnClickListener {
                        Color.colorToHSV(color, hsv)
                        refreshPreview()
                    }
                }
                swatches += color to swatch
                grid.addView(swatch)
            }
            modeContent.addView(grid)
            refreshPreview()
        }
        fun showCustom() {
            swatches.clear()
            hsvBars.clear()
            modeContent.removeAllViews()
            modeToggle.text = "Suggestions"
            listOf("Hue", "Saturation", "Value").forEachIndexed { index, label ->
                modeContent.addView(TextView(context).apply { text = label })
                val bar = SeekBar(context).apply {
                    max = if (index == 0) 359 else 100
                    progress = if (index == 0) hsv[index].roundToInt().coerceIn(0, 359)
                    else (hsv[index] * 100).roundToInt().coerceIn(0, 100)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                            if (fromUser) {
                                hsv[index] = if (index == 0) value.toFloat() else value / 100f
                                refreshPreview()
                            }
                        }
                        override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                        override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                    })
                }
                hsvBars += index to bar
                modeContent.addView(bar)
            }
            refreshPreview()
        }
        modeToggle.setOnClickListener {
            customMode = !customMode
            if (customMode) showCustom() else showSuggestions()
        }
        showSuggestions()
        return AlertDialog.Builder(context)
            .setTitle("Select color")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Set") { _, _ -> finish(BrowserWebsitePromptAnswer(accepted = true, value = cssColor(colorNow()))) }
            .create()
    }

    private fun dateTimeDialog(
        context: Context,
        prompt: BrowserWebsitePromptRequest,
        errorColor: Int,
        finish: (BrowserWebsitePromptAnswer) -> Unit,
    ): AlertDialog {
        val kind = prompt.inputType.orEmpty()
        val initial = parseDateTime(kind, prompt.defaultValue)
        var selectedDate = initial.date
        var selectedTime = initial.time
        val is24Hour = android.text.format.DateFormat.is24HourFormat(context)
        val nativeControls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val datePicker = if (kind == "date") DatePicker(context) else null
        val timePicker = if (kind == "time") TimePicker(context) else null
        val errorText = TextView(context).apply {
            setTextColor(errorColor)
            tag = PROMPT_ERROR_TAG
            visibility = View.GONE
        }
        val contentBody = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 4))
            addView(nativeControls)
            addView(errorText)
        }
        val scroll = ScrollView(context).apply {
            addView(contentBody)
        }

        fun numberPicker(widthDp: Int = 84): NumberPicker = NumberPicker(context).apply {
            wrapSelectorWheel = true
            layoutParams = LinearLayout.LayoutParams(dp(context, widthDp), dp(context, 106))
        }
        fun pickerRow(vararg pickers: NumberPicker): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            pickers.forEach { addView(it) }
        }
        fun yearRange(): IntRange {
            val minYearValue = if (kind == "week") prompt.minValue?.let(::parseWeekStart)?.get(WeekFields.ISO.weekBasedYear())
            else parseDateConstraint(kind, prompt.minValue)?.year
            val maxYearValue = if (kind == "week") prompt.maxValue?.let(::parseWeekStart)?.get(WeekFields.ISO.weekBasedYear())
            else parseDateConstraint(kind, prompt.maxValue)?.year
            val minYear = (minYearValue ?: 1).coerceAtLeast(1)
            val maxYear = (maxYearValue ?: 9999).coerceAtMost(9999)
            return if (minYear <= maxYear) minYear..maxYear else initial.date.year..initial.date.year
        }
        fun displayMonths(): Array<String> = (1..12).map {
            java.time.Month.of(it).getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
        }.toTypedArray()

        when (kind) {
            "date" -> datePicker?.apply {
                val min = parseDateConstraint(kind, prompt.minValue)
                val max = parseDateConstraint(kind, prompt.maxValue)
                if (min == null || max == null || min <= max) {
                    minDate = dateMillis(LocalDate.of(1, 1, 1))
                    maxDate = dateMillis(LocalDate.of(9999, 12, 31))
                    minDate = dateMillis(min ?: LocalDate.of(1, 1, 1))
                    maxDate = dateMillis(max ?: LocalDate.of(9999, 12, 31))
                }
                val boundedInitial = min?.let { if (initial.date < it) it else initial.date } ?: initial.date
                val bounded = max?.let { if (boundedInitial > it) it else boundedInitial } ?: boundedInitial
                init(bounded.year, bounded.monthValue - 1, bounded.dayOfMonth) { _, year, month, day ->
                    selectedDate = LocalDate.of(year, month + 1, day)
                }
                selectedDate = bounded
                nativeControls.addView(this)
            }
            "time" -> timePicker?.apply {
                setIs24HourView(is24Hour)
                hour = initial.time.hour
                minute = initial.time.minute
                nativeControls.addView(this)
            }
            "month" -> {
                val monthPicker = numberPicker(92).apply {
                    minValue = 1
                    maxValue = 12
                    displayedValues = displayMonths()
                    value = initial.date.monthValue
                }
                val years = yearRange()
                val yearPicker = numberPicker(92).apply {
                    minValue = years.first
                    maxValue = years.last
                    value = initial.date.year.coerceIn(years.first, years.last)
                }
                val minMonth = prompt.minValue?.let { runCatching { YearMonth.parse(it.trim()) }.getOrNull() }
                val maxMonth = prompt.maxValue?.let { runCatching { YearMonth.parse(it.trim()) }.getOrNull() }
                fun updateMonth() {
                    val year = yearPicker.value
                    val boundedLower = minMonth?.takeIf { it.year == year }?.monthValue ?: 1
                    val boundedUpper = maxMonth?.takeIf { it.year == year }?.monthValue ?: 12
                    val lower = if (boundedLower <= boundedUpper) boundedLower else 1
                    val upper = if (boundedLower <= boundedUpper) boundedUpper else 12
                    val previous = monthPicker.value
                    monthPicker.displayedValues = null
                    monthPicker.minValue = 1
                    monthPicker.maxValue = 12
                    monthPicker.minValue = lower
                    monthPicker.maxValue = upper
                    monthPicker.displayedValues = (lower..upper).map {
                        java.time.Month.of(it).getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
                    }.toTypedArray()
                    monthPicker.value = previous.coerceIn(lower, upper)
                    selectedDate = YearMonth.of(year, monthPicker.value).atDay(1)
                }
                monthPicker.setOnValueChangedListener { _, _, _ -> updateMonth() }
                yearPicker.setOnValueChangedListener { _, _, _ -> updateMonth() }
                updateMonth()
                nativeControls.addView(pickerRow(monthPicker, yearPicker))
            }
            "week" -> {
                val weekPicker = numberPicker(92).apply {
                    minValue = 1
                    maxValue = weeksInIsoYear(initial.date.get(WeekFields.ISO.weekBasedYear()))
                    value = initial.date.get(WeekFields.ISO.weekOfWeekBasedYear()).coerceIn(minValue, maxValue)
                }
                val years = yearRange()
                val yearPicker = numberPicker(92).apply {
                    minValue = years.first
                    maxValue = years.last
                    value = initial.date.get(WeekFields.ISO.weekBasedYear()).coerceIn(years.first, years.last)
                }
                val minimumWeek = prompt.minValue?.let(::parseWeekStart)
                val maximumWeek = prompt.maxValue?.let(::parseWeekStart)
                fun updateWeek() {
                    val year = yearPicker.value
                    val boundedLower = minimumWeek?.takeIf { it.get(WeekFields.ISO.weekBasedYear()) == year }
                        ?.get(WeekFields.ISO.weekOfWeekBasedYear()) ?: 1
                    val boundedUpper = maximumWeek?.takeIf { it.get(WeekFields.ISO.weekBasedYear()) == year }
                        ?.get(WeekFields.ISO.weekOfWeekBasedYear()) ?: weeksInIsoYear(year)
                    val lower = if (boundedLower <= boundedUpper) boundedLower else 1
                    val upper = if (boundedLower <= boundedUpper) boundedUpper else weeksInIsoYear(year)
                    val previous = weekPicker.value
                    weekPicker.minValue = 1
                    weekPicker.maxValue = 53
                    weekPicker.minValue = lower
                    weekPicker.maxValue = upper
                    weekPicker.value = previous.coerceIn(lower, upper)
                    selectedDate = weekStart(year, weekPicker.value)
                }
                weekPicker.setOnValueChangedListener { _, _, _ -> updateWeek() }
                yearPicker.setOnValueChangedListener { _, _, _ -> updateWeek() }
                updateWeek()
                nativeControls.addView(pickerRow(weekPicker, yearPicker))
            }
            "datetime-local" -> {
                val years = yearRange()
                val monthPicker = numberPicker(72).apply {
                    minValue = 1
                    maxValue = 12
                    displayedValues = displayMonths()
                    value = initial.date.monthValue
                }
                val dayPicker = numberPicker(60).apply {
                    minValue = 1
                    maxValue = initial.date.lengthOfMonth()
                    value = initial.date.dayOfMonth
                }
                val yearPicker = numberPicker(78).apply {
                    minValue = years.first
                    maxValue = years.last
                    value = initial.date.year.coerceIn(years.first, years.last)
                }
                fun updateDateSpinners() {
                    val year = yearPicker.value
                    val month = monthPicker.value
                    val days = YearMonth.of(year, month).lengthOfMonth()
                    dayPicker.maxValue = days
                    if (dayPicker.value > days) dayPicker.value = days
                    selectedDate = LocalDate.of(year, month, dayPicker.value)
                }
                monthPicker.setOnValueChangedListener { _, _, _ -> updateDateSpinners() }
                dayPicker.setOnValueChangedListener { _, _, _ -> updateDateSpinners() }
                yearPicker.setOnValueChangedListener { _, _, _ -> updateDateSpinners() }
                selectedDate = LocalDate.of(yearPicker.value, monthPicker.value, dayPicker.value)

                val hourPicker = numberPicker(64).apply {
                    minValue = if (is24Hour) 0 else 1
                    maxValue = if (is24Hour) 23 else 12
                    displayedValues = (minValue..maxValue).map { if (is24Hour) String.format(Locale.US, "%02d", it) else it.toString() }.toTypedArray()
                    value = if (is24Hour) initial.time.hour else displayHour(initial.time.hour)
                }
                val minutePicker = numberPicker(64).apply {
                    minValue = 0
                    maxValue = 59
                    displayedValues = (0..59).map { String.format(Locale.US, "%02d", it) }.toTypedArray()
                    value = initial.time.minute
                }
                val amPmPicker = if (is24Hour) null else numberPicker(66).apply {
                    minValue = 0
                    maxValue = 1
                    displayedValues = arrayOf("AM", "PM")
                    value = if (initial.time.hour >= 12) 1 else 0
                }
                fun updateTimeSpinners() {
                    val hour = if (is24Hour) hourPicker.value else (hourPicker.value % 12) + if (amPmPicker?.value == 1) 12 else 0
                    selectedTime = LocalTime.of(hour, minutePicker.value)
                }
                hourPicker.setOnValueChangedListener { _, _, _ -> updateTimeSpinners() }
                minutePicker.setOnValueChangedListener { _, _, _ -> updateTimeSpinners() }
                amPmPicker?.setOnValueChangedListener { _, _, _ -> updateTimeSpinners() }
                updateTimeSpinners()
                nativeControls.addView(pickerRow(monthPicker, dayPicker, yearPicker))
                nativeControls.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER
                    addView(hourPicker)
                    addView(TextView(context).apply { text = ":"; textSize = 20f; gravity = android.view.Gravity.CENTER })
                    addView(minutePicker)
                    if (amPmPicker != null) addView(amPmPicker)
                })
            }
            else -> Unit
        }

        val dialogTitle = when (kind) {
            "month" -> "Set month"
            "week" -> "Set week"
            "datetime-local" -> "Set date and time"
            else -> null
        }
        val builder = AlertDialog.Builder(context)
        if (dialogTitle != null) builder.setTitle(dialogTitle)
        val dialog = builder
            .setView(scroll)
            .setNeutralButton("Clear") { _, _ -> finish(BrowserWebsitePromptAnswer(accepted = true, value = "")) }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Set", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                errorText.visibility = View.GONE
                val date = datePicker?.let { LocalDate.of(it.year, it.month + 1, it.dayOfMonth) } ?: selectedDate
                val time = timePicker?.let { LocalTime.of(it.hour, it.minute) } ?: selectedTime

                if (!withinConstraints(kind, date, time, prompt)) {
                    errorText.text = "Enter a valid value within the allowed range."
                    errorText.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                finish(BrowserWebsitePromptAnswer(accepted = true, value = formatDateTime(kind, date, time)))
                dialog.dismiss()
            }
        }
        return dialog
    }

    private data class DateTimeValue(val date: LocalDate, val time: LocalTime)

    private fun parseDateConstraint(kind: String, value: String?): LocalDate? {
        val raw = value?.trim().orEmpty()
        return when (kind) {
            "date" -> runCatching { LocalDate.parse(raw) }.getOrNull()
            "month" -> runCatching { YearMonth.parse(raw).atDay(1) }.getOrNull()
            "week" -> parseWeekStart(raw)
            "time" -> null
            else -> parseLocalDateTime(raw)?.toLocalDate()
        }
    }

    private fun dateMillis(date: LocalDate): Long = date
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    private fun parseDateTime(kind: String, value: String?): DateTimeValue {
        val fallback = DateTimeValue(LocalDate.now(), LocalTime.now().withSecond(0).withNano(0))
        val raw = value.orEmpty()
        return when (kind) {
            "date" -> runCatching { DateTimeValue(LocalDate.parse(raw), fallback.time) }.getOrDefault(fallback)
            "month" -> runCatching { DateTimeValue(YearMonth.parse(raw).atDay(1), fallback.time) }.getOrDefault(fallback)
            "week" -> parseWeekStart(raw)?.let { DateTimeValue(it, fallback.time) } ?: fallback
            "time" -> parseLocalTime(raw)?.let { DateTimeValue(fallback.date, it) } ?: fallback
            else -> parseLocalDateTime(raw)?.let { DateTimeValue(it.toLocalDate(), it.toLocalTime().withSecond(0).withNano(0)) } ?: fallback
        }
    }

    private fun formatDateTime(kind: String, date: LocalDate, time: LocalTime): String = when (kind) {
        // Month and week remain semantic HTML values even though Android has no week picker.
        "date" -> formatIsoDate(date)
        "month" -> String.format(Locale.US, "%04d-%02d", date.year, date.monthValue)
        "week" -> String.format(Locale.US, "%04d-W%02d", date.get(WeekFields.ISO.weekBasedYear()), date.get(WeekFields.ISO.weekOfWeekBasedYear()))
        "time" -> String.format(Locale.US, "%02d:%02d", time.hour, time.minute)
        else -> String.format(Locale.US, "%sT%02d:%02d", formatIsoDate(date), time.hour, time.minute)
    }

    private fun formatIsoDate(date: LocalDate): String = String.format(
        Locale.US,
        "%04d-%02d-%02d",
        date.year,
        date.monthValue,
        date.dayOfMonth,
    )

    private fun parseWeekStart(value: String): LocalDate? = runCatching {
        val match = Regex("^(\\d{4})-W(\\d{2})$").matchEntire(value.trim()) ?: return null
        val year = match.groupValues[1].toInt()
        val week = match.groupValues[2].toInt()
        val date = LocalDate.of(year, 1, 4)
            .with(WeekFields.ISO.weekOfWeekBasedYear(), week.toLong())
            .with(WeekFields.ISO.dayOfWeek(), 1)
        if (date.get(WeekFields.ISO.weekBasedYear()) != year || date.get(WeekFields.ISO.weekOfWeekBasedYear()) != week) return null
        date
    }.getOrNull()

    private fun weekStart(year: Int, week: Int): LocalDate = LocalDate.of(year, 1, 4)
        .with(WeekFields.ISO.weekOfWeekBasedYear(), week.toLong())
        .with(WeekFields.ISO.dayOfWeek(), 1)

    private fun weeksInIsoYear(year: Int): Int = LocalDate.of(year, 12, 28)
        .get(WeekFields.ISO.weekOfWeekBasedYear())

    private fun parseLocalTime(value: String): LocalTime? = runCatching {
        val raw = value.trim()
        LocalTime.parse(if (raw.length == 5) "$raw:00" else raw).withSecond(0).withNano(0)
    }.getOrNull()

    private fun parseLocalDateTime(value: String): LocalDateTime? = runCatching {
        val raw = value.trim()
        LocalDateTime.parse(if (raw.length == 16) "$raw:00" else raw)
    }.getOrNull()

    private fun monthAllowed(month: YearMonth, minimum: String?, maximum: String?): Boolean {
        val min = minimum?.let { runCatching { YearMonth.parse(it.trim()) }.getOrNull() }
        val max = maximum?.let { runCatching { YearMonth.parse(it.trim()) }.getOrNull() }
        return (min == null || month >= min) && (max == null || month <= max)
    }

    private fun withinConstraints(
        kind: String,
        date: LocalDate,
        time: LocalTime,
        prompt: BrowserWebsitePromptRequest,
    ): Boolean = when (kind) {
        "time" -> {
            val min = prompt.minValue?.let(::parseLocalTime)
            val max = prompt.maxValue?.let(::parseLocalTime)
            if (min != null && max != null && min > max) time >= min || time <= max
            else (min == null || time >= min) && (max == null || time <= max)
        }
        "month" -> monthAllowed(YearMonth.from(date), prompt.minValue, prompt.maxValue)
        "week" -> {
            val monday = date.with(WeekFields.ISO.dayOfWeek(), 1)
            val min = prompt.minValue?.let(::parseWeekStart)
            val max = prompt.maxValue?.let(::parseWeekStart)?.plusDays(6)
            (min == null || monday >= min) && (max == null || monday <= max)
        }
        "datetime-local" -> {
            val candidate = LocalDateTime.of(date, time)
            val min = prompt.minValue?.let(::parseLocalDateTime)
            val max = prompt.maxValue?.let(::parseLocalDateTime)
            (min == null || candidate >= min) && (max == null || candidate <= max)
        }
        else -> {
            val min = prompt.minValue?.let { parseDateConstraint(kind, it) }
            val max = prompt.maxValue?.let { parseDateConstraint(kind, it) }
            (min == null || date >= min) && (max == null || date <= max)
        }
    }

    private fun displayHour(hour: Int): Int = ((hour + 11) % 12) + 1

    private fun parseColor(value: String?): Int = parseColorOrNull(value) ?: Color.BLACK

    /** Normalizes HTML color values to the six-digit CSS form accepted by GeckoView. */
    private fun parseColorOrNull(value: String?): Int? = runCatching {
        val normalized = value.orEmpty().trim()
        val expanded = when {
            Regex("#[0-9a-fA-F]{3}").matches(normalized) -> buildString {
                append('#')
                normalized.drop(1).forEach { append(it).append(it) }
            }
            Regex("#[0-9a-fA-F]{6}").matches(normalized) -> normalized
            else -> return null
        }
        Color.parseColor(expanded)
    }.getOrNull()

    private fun cssColor(channels: IntArray): String = String.format(Locale.US, "#%02x%02x%02x", channels[0], channels[1], channels[2])

    private fun cssColor(color: Int): String = String.format(Locale.US, "#%06x", color and 0x00ffffff)

    private fun LinearLayout.addText(context: Context, text: String, bold: Boolean) {
        addView(TextView(context).apply {
            this.text = text
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
        })
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private fun defaultTitle(kind: BrowserWebsitePromptKind): String = when (kind) {
        BrowserWebsitePromptKind.ALERT -> "Alert"
        BrowserWebsitePromptKind.CONFIRM -> "Confirm"
        BrowserWebsitePromptKind.TEXT -> "Input required"
        BrowserWebsitePromptKind.BEFORE_UNLOAD -> "Leave site?"
        BrowserWebsitePromptKind.REPOST -> "Confirm Form Resubmission"
        BrowserWebsitePromptKind.AUTH -> "Sign in"
        BrowserWebsitePromptKind.POPUP -> "Pop-up blocked"
        BrowserWebsitePromptKind.CHOICE -> "Choose an option"
        BrowserWebsitePromptKind.COLOR -> "Choose a color"
        BrowserWebsitePromptKind.DATE_TIME -> "Enter a value"
        BrowserWebsitePromptKind.FILE -> "Choose a file"
    }

    private fun confirmLabel(kind: BrowserWebsitePromptKind): String = when (kind) {
        BrowserWebsitePromptKind.ALERT -> "OK"
        BrowserWebsitePromptKind.BEFORE_UNLOAD -> "Leave"
        BrowserWebsitePromptKind.REPOST -> "Continue"
        BrowserWebsitePromptKind.AUTH -> "Sign in"
        BrowserWebsitePromptKind.POPUP -> "Allow"
        BrowserWebsitePromptKind.CHOICE -> "Select"
        else -> "OK"
    }

    private fun dismissLabel(kind: BrowserWebsitePromptKind): String = when (kind) {
        BrowserWebsitePromptKind.BEFORE_UNLOAD -> "Cancel"
        BrowserWebsitePromptKind.REPOST -> "Cancel"
        BrowserWebsitePromptKind.POPUP -> "Block"
        else -> "Cancel"
    }

    private const val PROMPT_ERROR_TAG = "website-prompt-error"
    private const val PROMPT_PRIMARY_TAG = "website-prompt-primary"
    private const val VALUE_TAG = "website-prompt-value"
    private const val USERNAME_TAG = "website-prompt-username"
    private const val PASSWORD_TAG = "website-prompt-password"
}
