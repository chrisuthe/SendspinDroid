package com.sendspindroid

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.button.MaterialButton

/**
 * Custom preference for sync offset with +/- buttons for fine-tuning.
 *
 * Features:
 * - Displays current value with +/- buttons
 * - Tap buttons to adjust by 10ms increments
 * - Tap value to enter exact number via dialog
 * - Clamped to ±5000ms range
 */
class SyncOffsetPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : Preference(context, attrs, defStyleAttr) {

    companion object {
        private const val STEP_MS = 10
        private const val MIN_OFFSET_MS = -5000
        private const val MAX_OFFSET_MS = 5000
    }

    private var currentValue: Int = 0
    private var valueTextView: TextView? = null

    init {
        layoutResource = R.layout.preference_sync_offset
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        // Load current value from UserSettings
        currentValue = UserSettings.getSyncOffsetMs()

        // Get views
        valueTextView = holder.findViewById(R.id.value_text) as? TextView
        val btnDecrease = holder.findViewById(R.id.btn_decrease) as? MaterialButton
        val btnIncrease = holder.findViewById(R.id.btn_increase) as? MaterialButton

        // Update display
        updateValueDisplay()

        // Decrease button (-10ms)
        btnDecrease?.setOnClickListener {
            adjustValue(-STEP_MS)
        }

        // Increase button (+10ms)
        btnIncrease?.setOnClickListener {
            adjustValue(STEP_MS)
        }

        // Tap value to edit directly
        valueTextView?.setOnClickListener {
            showEditDialog()
        }

        // Disable default preference click behavior (we handle it ourselves)
        holder.itemView.isClickable = false
    }

    private fun adjustValue(delta: Int) {
        val newValue = (currentValue + delta).coerceIn(MIN_OFFSET_MS, MAX_OFFSET_MS)
        if (newValue != currentValue) {
            currentValue = newValue
            saveValue()
            updateValueDisplay()
        }
    }

    private fun saveValue() {
        UserSettings.setSyncOffsetMs(currentValue)
        callChangeListener(currentValue)
    }

    private fun updateValueDisplay() {
        val displayText = if (currentValue >= 0) {
            "+$currentValue ms"
        } else {
            "$currentValue ms"
        }
        valueTextView?.text = displayText
    }

    private fun showEditDialog() {
        val editText = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(currentValue.toString())
            setSelection(text.length)
            hint = "0"
        }

        // Add padding to the EditText
        val paddingPx = (20 * context.resources.displayMetrics.density).toInt()
        editText.setPadding(paddingPx, paddingPx, paddingPx, paddingPx)

        AlertDialog.Builder(context)
            .setTitle(R.string.pref_sync_offset_dialog_title)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val input = editText.text.toString()
                val newValue = input.toIntOrNull()
                if (newValue != null) {
                    currentValue = newValue.coerceIn(MIN_OFFSET_MS, MAX_OFFSET_MS)
                    saveValue()
                    updateValueDisplay()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        // Request focus and show keyboard
        editText.requestFocus()
    }

    /**
     * Refresh the displayed value from UserSettings.
     * Call this if the value may have changed externally.
     */
    fun refreshValue() {
        currentValue = UserSettings.getSyncOffsetMs()
        updateValueDisplay()
    }
}
