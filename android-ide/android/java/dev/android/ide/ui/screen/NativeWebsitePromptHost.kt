package dev.android.ide.ui.screen

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.DatePicker
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.android.ide.browser.BrowserWebsitePromptAnswer
import dev.android.ide.browser.BrowserWebsitePromptChoice
import dev.android.ide.browser.BrowserWebsitePromptKind
import dev.android.ide.browser.BrowserWebsitePromptRequest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
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
    val currentOnAnswer by rememberUpdatedState(onAnswer)
    DisposableEffect(prompt.id, lifecycleOwner) {
        val dialog = runCatching {
            NativeWebsitePromptHost.show(context, prompt) { answer ->
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
            dialog?.dismiss()
            currentOnAnswer(prompt.id, BrowserWebsitePromptAnswer(accepted = false))
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
        onAnswer: (BrowserWebsitePromptAnswer) -> Unit,
    ): android.app.Dialog {
        val answered = AtomicBoolean(false)
        fun finish(answer: BrowserWebsitePromptAnswer) {
            if (answered.compareAndSet(false, true)) onAnswer(answer)
        }
        val dialog = when (prompt.kind) {
            BrowserWebsitePromptKind.CHOICE -> choiceDialog(context, prompt, ::finish)
            BrowserWebsitePromptKind.COLOR -> colorDialog(context, prompt, ::finish)
            BrowserWebsitePromptKind.DATE_TIME -> dateTimeDialog(context, prompt, ::finish)
            else -> standardDialog(context, prompt, ::finish)
        }
        dialog.setOnDismissListener { finish(BrowserWebsitePromptAnswer(accepted = false)) }
        dialog.show()
        return dialog
    }

    private fun standardDialog(
        context: Context,
        prompt: BrowserWebsitePromptRequest,
        finish: (BrowserWebsitePromptAnswer) -> Unit,
    ): AlertDialog {
        val builder = AlertDialog.Builder(context)
            .setTitle(prompt.title ?: defaultTitle(prompt.kind))
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
        return builder.create()
    }

    private fun standardContent(context: Context, prompt: BrowserWebsitePromptRequest): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 24), dp(context, 24), dp(context, 24))
            prompt.origin.takeIf(String::isNotBlank)?.let { addText(context, it, false) }
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
        val selected = prompt.choices.filter { it.selected && !it.separator && !it.group }
            .mapTo(LinkedHashSet()) { it.id }
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        prompt.origin.takeIf(String::isNotBlank)?.let { content.addText(context, it, false) }
        prompt.message?.takeIf(String::isNotBlank)?.let { content.addText(context, it, false) }
        val list = ListView(context).apply {
            choiceMode = if (prompt.multiple) ListView.CHOICE_MODE_MULTIPLE else ListView.CHOICE_MODE_SINGLE
            layoutParams = LinearLayout.LayoutParams(-1, (context.resources.displayMetrics.heightPixels * .55f).roundToInt())
        }
        val adapter = NativeChoiceAdapter(context, prompt.choices, selected, prompt.multiple)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val choice = prompt.choices[position]
            if (choice.separator || choice.group || choice.disabled) return@setOnItemClickListener
            if (prompt.multiple) {
                if (!selected.add(choice.id)) selected.remove(choice.id)
            } else {
                selected.clear()
                selected.add(choice.id)
            }
            adapter.notifyDataSetChanged()
        }
        content.addView(list)
        return AlertDialog.Builder(context)
            .setTitle(prompt.title ?: defaultTitle(prompt.kind))
            .setView(content)
            .setNegativeButton(dismissLabel(prompt.kind), null)
            .setPositiveButton(confirmLabel(prompt.kind)) { _, _ ->
                finish(BrowserWebsitePromptAnswer(accepted = true, selectedIds = selected.toList()))
            }
            .create()
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
                layoutParams = ListView.LayoutParams(-1, dp(context, 1))
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
        finish: (BrowserWebsitePromptAnswer) -> Unit,
    ): AlertDialog {
        // The minimum SDK has no complete system color-picker contract. These native
        // platform controls provide the browser-style color interaction without a
        // text-field fallback or a large third-party dependency.
        val initial = parseColor(prompt.defaultValue)
        val channels = intArrayOf(Color.red(initial), Color.green(initial), Color.blue(initial))
        val preview = View(context).apply {
            setBackgroundColor(Color.rgb(channels[0], channels[1], channels[2]))
            minimumHeight = dp(context, 56)
        }
        val hex = TextView(context).apply { text = cssColor(channels) }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 24), dp(context, 24), dp(context, 24))
            prompt.origin.takeIf(String::isNotBlank)?.let { addText(context, it, false) }
            prompt.message?.takeIf(String::isNotBlank)?.let { addText(context, it, false) }
            addView(preview, LinearLayout.LayoutParams(-1, dp(context, 56)))
            addView(hex)
        }
        listOf("Red", "Green", "Blue").forEachIndexed { index, label ->
            content.addView(TextView(context).apply { text = label })
            content.addView(SeekBar(context).apply {
                max = 255
                progress = channels[index]
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                        channels[index] = value
                        preview.setBackgroundColor(Color.rgb(channels[0], channels[1], channels[2]))
                        hex.text = cssColor(channels)
                    }
                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                })
            })
        }
        prompt.predefinedValues.filter { parseColorOrNull(it) != null }.take(8).let { values ->
            if (values.isNotEmpty()) {
                content.addText(context, "Suggested colors", true)
                val suggestions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                values.forEach { value ->
                    suggestions.addView(Button(context).apply {
                        text = value
                        setOnClickListener {
                            val color = parseColor(value)
                            channels[0] = Color.red(color); channels[1] = Color.green(color); channels[2] = Color.blue(color)
                            preview.setBackgroundColor(color); hex.text = cssColor(channels)
                        }
                    })
                }
                content.addView(suggestions)
            }
        }
        return AlertDialog.Builder(context)
            .setTitle(prompt.title ?: "Choose a color")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use color") { _, _ -> finish(BrowserWebsitePromptAnswer(true, value = cssColor(channels))) }
            .create()
    }

    private fun dateTimeDialog(
        context: Context,
        prompt: BrowserWebsitePromptRequest,
        finish: (BrowserWebsitePromptAnswer) -> Unit,
    ): AlertDialog {
        val kind = prompt.inputType.orEmpty()
        val initial = parseDateTime(kind, prompt.defaultValue)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 24), dp(context, 24), dp(context, 24))
            prompt.origin.takeIf(String::isNotBlank)?.let { addText(context, it, false) }
            prompt.message?.takeIf(String::isNotBlank)?.let { addText(context, it, false) }
        }
        val datePicker = if (kind != "time") DatePicker(context) else null
        val timePicker = if (kind == "time" || kind == "datetime-local") TimePicker(context) else null
        datePicker?.apply {
            init(initial.date.year, initial.date.monthValue - 1, initial.date.dayOfMonth, null)
            prompt.minValue?.let { parseDateConstraint(kind, it) }?.let { min -> minDate = dateMillis(min) }
            prompt.maxValue?.let { parseDateConstraint(kind, it) }?.let { max -> maxDate = dateMillis(max) }
            content.addView(this)
        }
        timePicker?.apply {
            setIs24HourView(android.text.format.DateFormat.is24HourFormat(context))
            hour = initial.time.hour
            minute = initial.time.minute
            content.addView(this)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(prompt.title ?: "Enter a value")
            .setView(content)
            .setNeutralButton("Clear") { _, _ -> finish(BrowserWebsitePromptAnswer(true, value = "")) }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use value") { _, _ ->
                val date = datePicker?.let { LocalDate.of(it.year, it.month + 1, it.dayOfMonth) } ?: initial.date
                val time = timePicker?.let { LocalTime.of(it.hour, it.minute) } ?: initial.time
                finish(BrowserWebsitePromptAnswer(true, value = formatDateTime(kind, date, time)))
            }
            .create()
        return dialog
    }

    private data class DateTimeValue(val date: LocalDate, val time: LocalTime)

    private fun parseDateConstraint(kind: String, value: String): LocalDate? = runCatching {
        when (kind) {
            "date" -> LocalDate.parse(value)
            "month" -> LocalDate.parse("$value-01")
            "week" -> parseDateTime(kind, value).date
            else -> LocalDateTime.parse(if (value.length == 16) "$value:00" else value).toLocalDate()
        }
    }.getOrNull()

    private fun dateMillis(date: LocalDate): Long = date
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    private fun parseDateTime(kind: String, value: String?): DateTimeValue {
        val fallback = DateTimeValue(LocalDate.now(), LocalTime.now().withSecond(0).withNano(0))
        val raw = value.orEmpty()
        return runCatching {
            when (kind) {
                "date" -> DateTimeValue(LocalDate.parse(raw), fallback.time)
                "month" -> DateTimeValue(LocalDate.parse("$raw-01"), fallback.time)
                "week" -> {
                    val parts = raw.split("-W")
                    val year = parts[0].toInt()
                    val week = parts[1].toInt()
                    val date = LocalDate.of(year, 1, 4)
                        .with(WeekFields.ISO.weekOfWeekBasedYear(), week.toLong())
                        .with(WeekFields.ISO.dayOfWeek(), 1)
                    DateTimeValue(date, fallback.time)
                }
                "time" -> DateTimeValue(fallback.date, LocalTime.parse(if (raw.length == 5) "$raw:00" else raw).withSecond(0).withNano(0))
                else -> {
                    val parsed = LocalDateTime.parse(if (raw.length == 16) "$raw:00" else raw)
                    DateTimeValue(parsed.toLocalDate(), parsed.toLocalTime().withSecond(0).withNano(0))
                }
            }
        }.getOrDefault(fallback)
    }

    private fun formatDateTime(kind: String, date: LocalDate, time: LocalTime): String = when (kind) {
        // HTML month/week controls have no direct Android picker, so a native date
        // selection is converted back to the exact Gecko/HTML string here.
        "date" -> date.toString()
        "month" -> String.format(Locale.US, "%04d-%02d", date.year, date.monthValue)
        "week" -> String.format(Locale.US, "%04d-W%02d", date.get(WeekFields.ISO.weekBasedYear()), date.get(WeekFields.ISO.weekOfWeekBasedYear()))
        "time" -> String.format(Locale.US, "%02d:%02d", time.hour, time.minute)
        else -> String.format(Locale.US, "%sT%02d:%02d", date, time.hour, time.minute)
    }

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
        BrowserWebsitePromptKind.BEFORE_UNLOAD -> "Leave this page?"
        BrowserWebsitePromptKind.REPOST -> "Resubmit form?"
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
        BrowserWebsitePromptKind.BEFORE_UNLOAD -> "Stay"
        BrowserWebsitePromptKind.REPOST -> "Cancel"
        BrowserWebsitePromptKind.POPUP -> "Block"
        else -> "Cancel"
    }

    private const val VALUE_TAG = "website-prompt-value"
    private const val USERNAME_TAG = "website-prompt-username"
    private const val PASSWORD_TAG = "website-prompt-password"
}
