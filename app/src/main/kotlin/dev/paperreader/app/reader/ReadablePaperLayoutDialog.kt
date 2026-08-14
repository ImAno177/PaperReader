package dev.paperreader.app.reader

import android.content.Context
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import dev.paperreader.app.R

internal data class ReadablePaperUserLayout(
    val textZoom: Int,
    val textSpacing: ReadableTextSpacing,
    val sideMargin: ReadableSideMargin,
)

internal class ReadablePaperLayoutPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(READER_PREFERENCES, Context.MODE_PRIVATE)

    fun load(): ReadablePaperUserLayout = ReadablePaperUserLayout(
        textZoom = preferences.getInt(PREFERENCE_TEXT_ZOOM, DEFAULT_TEXT_ZOOM)
            .coerceIn(MINIMUM_TEXT_ZOOM, MAXIMUM_TEXT_ZOOM),
        textSpacing = ReadableTextSpacing.fromStorageKey(preferences.getString(PREFERENCE_TEXT_SPACING, null)),
        sideMargin = ReadableSideMargin.fromStorageKey(preferences.getString(PREFERENCE_SIDE_MARGIN, null)),
    )

    fun save(layout: ReadablePaperUserLayout) {
        preferences.edit()
            .putInt(PREFERENCE_TEXT_ZOOM, layout.textZoom)
            .putString(PREFERENCE_TEXT_SPACING, layout.textSpacing.storageKey)
            .putString(PREFERENCE_SIDE_MARGIN, layout.sideMargin.storageKey)
            .apply()
    }
}

internal fun AppCompatActivity.showReadablePaperLayoutDialog(
    current: ReadablePaperUserLayout,
    onApply: (ReadablePaperUserLayout) -> Unit,
) {
    val content = layoutInflater.inflate(R.layout.dialog_readable_paper_layout, null)
    val decrease = content.findViewById<Button>(R.id.readable_layout_text_decrease)
    val increase = content.findViewById<Button>(R.id.readable_layout_text_increase)
    val textValue = content.findViewById<TextView>(R.id.readable_layout_text_value)
    val spacingGroup = content.findViewById<RadioGroup>(R.id.readable_layout_spacing_group)
    val marginGroup = content.findViewById<RadioGroup>(R.id.readable_layout_margin_group)
    var selected = current

    fun updateControls() {
        textValue.text = getString(R.string.readable_reader_text_size_value, selected.textZoom)
        decrease.isEnabled = selected.textZoom > MINIMUM_TEXT_ZOOM
        increase.isEnabled = selected.textZoom < MAXIMUM_TEXT_ZOOM
        spacingGroup.check(selected.textSpacing.radioButtonId())
        marginGroup.check(selected.sideMargin.radioButtonId())
    }

    decrease.setOnClickListener {
        selected = selected.copy(textZoom = nextReadableTextZoom(selected.textZoom, increase = false))
        updateControls()
    }
    increase.setOnClickListener {
        selected = selected.copy(textZoom = nextReadableTextZoom(selected.textZoom, increase = true))
        updateControls()
    }
    spacingGroup.setOnCheckedChangeListener { _, checkedId ->
        selected = selected.copy(textSpacing = checkedId.toReadableTextSpacing())
    }
    marginGroup.setOnCheckedChangeListener { _, checkedId ->
        selected = selected.copy(sideMargin = checkedId.toReadableSideMargin())
    }
    updateControls()

    val dialog = AlertDialog.Builder(this)
        .setTitle(R.string.readable_reader_layout)
        .setView(content)
        .setNegativeButton(R.string.cancel, null)
        .setNeutralButton(R.string.readable_reader_reset_layout, null)
        .setPositiveButton(R.string.readable_reader_apply_layout, null)
        .create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            selected = DEFAULT_READER_LAYOUT
            updateControls()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            onApply(selected)
            dialog.dismiss()
        }
    }
    dialog.show()
}

private fun ReadableTextSpacing.radioButtonId(): Int = when (this) {
    ReadableTextSpacing.COMPACT -> R.id.readable_layout_spacing_compact
    ReadableTextSpacing.COMFORTABLE -> R.id.readable_layout_spacing_comfortable
    ReadableTextSpacing.RELAXED -> R.id.readable_layout_spacing_relaxed
}

private fun ReadableSideMargin.radioButtonId(): Int = when (this) {
    ReadableSideMargin.NARROW -> R.id.readable_layout_margin_narrow
    ReadableSideMargin.COMFORTABLE -> R.id.readable_layout_margin_comfortable
    ReadableSideMargin.WIDE -> R.id.readable_layout_margin_wide
}

private fun Int.toReadableTextSpacing(): ReadableTextSpacing = when (this) {
    R.id.readable_layout_spacing_compact -> ReadableTextSpacing.COMPACT
    R.id.readable_layout_spacing_relaxed -> ReadableTextSpacing.RELAXED
    else -> ReadableTextSpacing.COMFORTABLE
}

private fun Int.toReadableSideMargin(): ReadableSideMargin = when (this) {
    R.id.readable_layout_margin_narrow -> ReadableSideMargin.NARROW
    R.id.readable_layout_margin_wide -> ReadableSideMargin.WIDE
    else -> ReadableSideMargin.COMFORTABLE
}

internal val DEFAULT_READER_LAYOUT = ReadablePaperUserLayout(
    textZoom = 100,
    textSpacing = ReadableTextSpacing.COMFORTABLE,
    sideMargin = ReadableSideMargin.COMFORTABLE,
)
internal const val MINIMUM_TEXT_ZOOM = 85
internal const val MAXIMUM_TEXT_ZOOM = 200
private const val DEFAULT_TEXT_ZOOM = 100
private const val READER_PREFERENCES = "readable-reader"
private const val PREFERENCE_TEXT_ZOOM = "text-zoom"
private const val PREFERENCE_TEXT_SPACING = "text-spacing"
private const val PREFERENCE_SIDE_MARGIN = "side-margin"
