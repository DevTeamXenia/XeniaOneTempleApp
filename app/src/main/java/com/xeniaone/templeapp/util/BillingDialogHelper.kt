package com.xeniaone.templeapp.util

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.xeniaone.templeapp.R
import com.xeniaone.templeapp.model.PrasadamItem
import com.xeniaone.templeapp.pinelabs.PineLabsManager
import java.util.Locale

object BillingDialogHelper {

    private const val TAG = "BillingDialogHelper"

    fun showMobileKeypadDialog(
        activity: Activity,
        etDevoteeMobile: EditText,
        tilDevoteeMobile: TextInputLayout
    ) {
        var currentInput = etDevoteeMobile.text?.toString()?.trim() ?: ""
        var isFreshInput = currentInput.isBlank()

        val res = activity.resources
        val keyHeightPx = res.getDimensionPixelSize(R.dimen.keypad_key_height)
        val keyTextPx = res.getDimension(R.dimen.keypad_key_text_size)
        val keyMarginPx = res.getDimensionPixelSize(R.dimen.keypad_key_margin)
        val displayTextPx = res.getDimension(R.dimen.keypad_display_text_size)
        val displayPadPx = res.getDimensionPixelSize(R.dimen.keypad_display_padding)
        val displayMarginBottomPx = res.getDimensionPixelSize(R.dimen.keypad_display_margin_bottom)
        val rowMarginBottomPx = res.getDimensionPixelSize(R.dimen.keypad_row_margin_bottom)
        val actionHeightPx = res.getDimensionPixelSize(R.dimen.keypad_action_height)
        val actionTextPx = res.getDimension(R.dimen.keypad_action_text_size)
        val actionMarginTopPx = res.getDimensionPixelSize(R.dimen.keypad_action_margin_top)
        val actionGapPx = res.getDimensionPixelSize(R.dimen.keypad_action_button_gap)
        val rootPadPx = res.getDimensionPixelSize(R.dimen.keypad_root_padding)

        val rootLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(rootPadPx, rootPadPx, rootPadPx, rootPadPx)
        }

        val prefs = activity.getSharedPreferences("kiosk_settings", android.content.Context.MODE_PRIVATE)
        val isWhatsAppOn = if (PineLabsManager.isPineLabsAvailable(activity)) false else prefs.getBoolean("pref_whatsapp_enabled", true)
        val noticeText = if (isWhatsAppOn) {
            "Enter Your Mobile Number To Receive Receipt In WhatsApp"
        } else {
            "Enter Mobile Number"
        }

        val tvNotice = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(activity.getColor(R.color.temple_gold_primary))
            text = noticeText
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = displayMarginBottomPx / 2
            }
        }
        rootLayout.addView(tvNotice)

        val tvDisplay = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, displayTextPx)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(activity.getColor(R.color.text_dark))
            text = if (currentInput.isEmpty()) "Enter 10-digit Mobile No" else currentInput
            setBackgroundResource(R.drawable.bg_total_box)
            setPadding(displayPadPx, displayPadPx, displayPadPx, displayPadPx)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = displayMarginBottomPx
            }
        }
        rootLayout.addView(tvDisplay)

        fun updateDisplay() {
            tvDisplay.text = if (currentInput.isEmpty()) "Enter 10-digit Mobile No" else currentInput
        }

        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("C", "0", "⌫")
        )

        rows.forEach { rowKeys ->
            val rowLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                weightSum = 3f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = rowMarginBottomPx
                }
            }

            rowKeys.forEach { key ->
                val btn = AppCompatButton(activity).apply {
                    text = key
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, keyTextPx)
                    typeface = Typeface.DEFAULT_BOLD
                    stateListAnimator = null
                    when (key) {
                        "C" -> {
                            setTextColor(activity.getColor(R.color.delete_red))
                            setBackgroundResource(R.drawable.bg_keypad_key_clear)
                        }
                        "⌫" -> {
                            setTextColor(activity.getColor(R.color.pay_button_orange))
                            setBackgroundResource(R.drawable.bg_keypad_action)
                        }
                        else -> {
                            setTextColor(activity.getColor(R.color.text_dark))
                            setBackgroundResource(R.drawable.bg_keypad_button)
                        }
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        keyHeightPx,
                        1f
                    ).apply {
                        setMargins(keyMarginPx, keyMarginPx, keyMarginPx, keyMarginPx)
                    }

                    setOnClickListener {
                        when (key) {
                            "C" -> {
                                currentInput = ""
                                isFreshInput = false
                                updateDisplay()
                            }
                            "⌫" -> {
                                if (currentInput.isNotEmpty()) {
                                    currentInput = currentInput.dropLast(1)
                                    isFreshInput = false
                                    updateDisplay()
                                }
                            }
                            else -> {
                                if (currentInput.length < 10) {
                                    if (isFreshInput) {
                                        currentInput = key
                                        isFreshInput = false
                                    } else {
                                        currentInput += key
                                    }
                                    updateDisplay()
                                }
                            }
                        }
                    }
                }
                rowLayout.addView(btn)
            }
            rootLayout.addView(rowLayout)
        }

        val actionRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = actionMarginTopPx
            }
        }

        val btnCancel = AppCompatButton(activity).apply {
            text = "Cancel"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, actionTextPx)
            typeface = Typeface.DEFAULT_BOLD
            stateListAnimator = null
            setTextColor(activity.getColor(R.color.text_muted))
            setBackgroundResource(R.drawable.bg_payment_card_selector)
            layoutParams = LinearLayout.LayoutParams(
                0,
                actionHeightPx,
                1f
            ).apply {
                setMargins(0, 0, actionGapPx, 0)
            }
        }

        val btnOk = AppCompatButton(activity).apply {
            text = "OK"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, actionTextPx)
            typeface = Typeface.DEFAULT_BOLD
            stateListAnimator = null
            setTextColor(activity.getColor(R.color.white))
            setBackgroundResource(R.drawable.bg_pay_button)
            layoutParams = LinearLayout.LayoutParams(
                0,
                actionHeightPx,
                1f
            ).apply {
                setMargins(actionGapPx, 0, 0, 0)
            }
        }

        actionRow.addView(btnCancel)
        actionRow.addView(btnOk)
        rootLayout.addView(actionRow)

        val scrollView = ScrollView(activity).apply {
            isFillViewport = true
            addView(rootLayout)
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Mobile Number / ഫോൺ നമ്പർ")
            .setView(scrollView)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnOk.setOnClickListener {
            etDevoteeMobile.setText(currentInput)
            tilDevoteeMobile.error = null
            dialog.dismiss()
        }

        dialog.show()
    }

    fun showPaymentOptionDialog(
        activity: Activity,
        total: Double,
        onCardClick: () -> Unit,
        onUpiClick: () -> Unit,
        onCashClick: () -> Unit
    ) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_pinelabs_payment_mode, null)
        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvDialogTotalAmount = dialogView.findViewById<TextView>(R.id.tvDialogTotalAmount)
        val btnCloseDialog = dialogView.findViewById<View>(R.id.btnCloseDialog)
        val btnOptionCard = dialogView.findViewById<View>(R.id.btnOptionCard)
        val btnOptionUpi = dialogView.findViewById<View>(R.id.btnOptionUpi)
        val btnOptionCash = dialogView.findViewById<View>(R.id.btnOptionCash)
        val btnOptionSibQr = dialogView.findViewById<View>(R.id.btnOptionSibQr)

        btnOptionSibQr?.visibility = View.GONE

        tvDialogTotalAmount.text = "₹%.2f".format(Locale.US, total)

        btnCloseDialog.setOnClickListener { dialog.dismiss() }

        btnOptionCard.setOnClickListener {
            dialog.dismiss()
            onCardClick()
        }

        btnOptionUpi.setOnClickListener {
            dialog.dismiss()
            onUpiClick()
        }

        btnOptionCash.setOnClickListener {
            dialog.dismiss()
            onCashClick()
        }

        dialog.show()
    }

    fun showCashReferenceDialog(
        activity: Activity,
        authManager: AuthManager,
        onContinue: (refId: String, adminPassword: String, onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit
    ) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_cash_reference, null)
        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        val etCashRefId = dialogView.findViewById<EditText>(R.id.etCashRefId)
        val etAdminPassword = dialogView.findViewById<EditText>(R.id.etAdminPassword)
        val btnCashContinue = dialogView.findViewById<View>(R.id.btnCashContinue)

        etCashRefId.setText("")
        etCashRefId.hint = "Enter Payment Reference ID"
        etAdminPassword?.setText("")
        etAdminPassword?.hint = "Enter Admin Password"
        etCashRefId.requestFocus()

        dialog.setOnShowListener {
            etCashRefId.post {
                etCashRefId.requestFocus()
                val imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(etCashRefId, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }

        btnCashContinue.setOnClickListener {
            val refId = etCashRefId.text?.toString()?.trim() ?: ""
            val adminPassword = etAdminPassword?.text?.toString()?.trim() ?: ""

            Log.d(TAG, "💵 Cash Dialog Submit: refId='$refId', passwordProvided=${adminPassword.isNotEmpty()}")

            if (refId.isEmpty()) {
                Log.w(TAG, "⚠️ Cash Dialog Error: Payment Reference ID is empty")
                android.widget.Toast.makeText(activity, "Please enter Payment Reference ID", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (adminPassword.isEmpty()) {
                Log.w(TAG, "⚠️ Cash Dialog Error: Admin Password is empty")
                android.widget.Toast.makeText(activity, "Please enter Admin Password", android.widget.Toast.LENGTH_SHORT).show()
                etAdminPassword?.error = "Password Required"
                etAdminPassword?.requestFocus()
                return@setOnClickListener
            }

            val expectedAdminPwd = authManager.adminPassword
            if (expectedAdminPwd.isNotBlank() && adminPassword != expectedAdminPwd) {
                Log.w(TAG, "❌ Cash Dialog Error: Incorrect Admin Password entered")
                android.widget.Toast.makeText(activity, "Incorrect Admin Password", android.widget.Toast.LENGTH_SHORT).show()
                etAdminPassword?.error = "Incorrect Password"
                etAdminPassword?.requestFocus()
                return@setOnClickListener
            }

            Log.i(TAG, "✅ Cash Dialog Validated Successfully for refId='$refId'")
            onContinue(
                refId,
                adminPassword,
                { dialog.dismiss() },
                { errorMsg ->
                    android.widget.Toast.makeText(activity, errorMsg, android.widget.Toast.LENGTH_SHORT).show()
                    etAdminPassword?.error = errorMsg
                    etAdminPassword?.requestFocus()
                }
            )
        }

        dialog.show()
    }

    fun showPineLabsTerminalInfoDialog(activity: Activity, authManager: AuthManager) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_pinelabs_terminal_info, null)
        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvInfoDeviceModel = dialogView.findViewById<TextView>(R.id.tvInfoDeviceModel)
        val tvInfoAppId = dialogView.findViewById<TextView>(R.id.tvInfoAppId)
        val btnClose = dialogView.findViewById<View>(R.id.btnCloseInfoDialog)
        val btnDismiss = dialogView.findViewById<View>(R.id.btnDismissInfo)

        tvInfoDeviceModel.text = "Model: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        tvInfoAppId.text = authManager.pineLabsApplicationId.take(14) + "..."

        btnClose.setOnClickListener { dialog.dismiss() }
        btnDismiss.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    fun showModernMessageDialog(activity: Activity, title: String, message: String) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_modern_alert, null)
        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvTitle = dialogView.findViewById<TextView>(R.id.tvAlertTitle)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvAlertMessage)
        val btnAction = dialogView.findViewById<View>(R.id.btnAlertAction)

        tvTitle.text = title
        tvMessage.text = message

        btnAction.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}
