package com.xeniaone.templeapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.xeniaone.templeapp.repository.OrderRepository
import com.xeniaone.templeapp.util.LanguageStrings
import com.xeniaone.templeapp.util.QrCodeGenerator
import com.xeniaone.templeapp.databinding.ActivityPaymentQrBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds


class PaymentQrActivity : AppCompatActivity() {
    companion object {

        const val EXTRA_CUSTOMER_NAME = "extra_customer_name"
        const val EXTRA_CUSTOMER_MOBILE = "extra_customer_mobile"
        const val EXTRA_TOTAL_AMOUNT = "extra_total_amount"
        const val EXTRA_QR_URL = "extra_qr_url"
        const val EXTRA_ORDER_ID = "extra_order_id"
        const val EXTRA_PSP_REF_NO = "extra_psp_ref_no"
        const val EXTRA_RECEIPT_ITEMS_JSON = "extra_receipt_items_json"
        private const val TAG = "PaymentQrActivity"
        private const val TOTAL_COUNTDOWN_MS = 5 * 60 * 1000L
    }

    private lateinit var binding: ActivityPaymentQrBinding
    private lateinit var orderRepository: OrderRepository
    private var totalAmount: Double = 0.0
    private var qrUrl: String? = null
    private var orderId: Int = 0
    private var pspRefNo: String = ""
    private var receiptItemsJson: String? = null
    private var currentLanguage: String = "en"

    private var countDownTimer: CountDownTimer? = null
    private var isPaymentCompleted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityPaymentQrBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootPaymentQr) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBanner.setPadding(0, systemBars.top, 0, 0)
            binding.llFooter.setPadding(0, 0, 0, systemBars.bottom.coerceAtLeast(16))
            insets
        }

        val app = application as TempleApp
        orderRepository = app.container.orderRepository

        totalAmount = intent.getDoubleExtra(EXTRA_TOTAL_AMOUNT, 0.0)
        qrUrl = intent.getStringExtra(EXTRA_QR_URL)
        orderId = intent.getIntExtra(EXTRA_ORDER_ID, 0)
        pspRefNo = intent.getStringExtra(EXTRA_PSP_REF_NO) ?: ""
        receiptItemsJson = intent.getStringExtra(EXTRA_RECEIPT_ITEMS_JSON)
        currentLanguage = intent.getStringExtra(BillingActivity.EXTRA_LANGUAGE_CODE) ?: "en"


        binding.tvScanHint.text = LanguageStrings.getScanCodeToPay(currentLanguage)
        binding.tvTotalAmountPay.text = String.format(Locale.getDefault(), "Total Amount ₹%.2f /-", totalAmount)

        generatePaymentQr()

        startPaymentCountdownTimer()

        startAutoPaymentStatusPolling()

        binding.btnCheckPayment.setOnClickListener {
            handleManualPaymentCheck()
        }

        binding.btnCheckPayment.setOnLongClickListener {
            navigateToReceiptScreen()
            true
        }

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnCancelPayment.setOnClickListener {
            val intent = Intent(this@PaymentQrActivity, BillingActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }



        binding.cardQr.setOnClickListener {
            navigateToReceiptScreen()
        }
        binding.ivQrCode.setOnClickListener {
            navigateToReceiptScreen()
        }
    }

    private fun generatePaymentQr() {
        try {
            val qrBitmap: Bitmap = QrCodeGenerator.generate(qrUrl!!, 512)
            binding.ivQrCode.setImageBitmap(qrBitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            binding.ivQrCode.setImageResource(R.drawable.img_qr_sample)
        }
    }

    private fun startPaymentCountdownTimer() {
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(TOTAL_COUNTDOWN_MS, 1000L) {
            @SuppressLint("SetTextI18n")
            override fun onTick(millisUntilFinished: Long) {
                val minutes = (millisUntilFinished / 1000) / 60
                val seconds = (millisUntilFinished / 1000) % 60
                binding.tvTimer.text = "QR expires in %02d:%02d".format(minutes, seconds)
            }

            @SuppressLint("SetTextI18n")
            override fun onFinish() {
                binding.tvTimer.text = "QR Expired"
                binding.flCheckPaymentContainer.visibility = View.VISIBLE
                binding.btnCancelPayment.visibility = View.VISIBLE
                if (!isPaymentCompleted && !isFinishing) {
//                    showPaymentExpiredDialog()
                }
            }
        }.start()
    }

    private fun startAutoPaymentStatusPolling() {
        if (orderId <= 0) return
        lifecycleScope.launch {
            delay(3000.milliseconds)
            while (!isPaymentCompleted && !isDestroyed && !isFinishing) {
                val paid = checkStatusFromApi(isManual = false)
                if (paid) {
                    break
                }
                delay(4000.milliseconds)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun handleManualPaymentCheck() {
        binding.btnCheckPayment.text = ""
        binding.progressBarChecking.visibility = View.VISIBLE
        binding.btnCheckPayment.isEnabled = false

        lifecycleScope.launch {
            val paid = checkStatusFromApi(isManual = true)
            binding.progressBarChecking.visibility = View.GONE
            binding.btnCheckPayment.text = "Check Payment Status"
            binding.btnCheckPayment.isEnabled = true

            if (!paid && !isPaymentCompleted) {
                Toast.makeText(
                    this@PaymentQrActivity,
                    "Payment is still pending. Please complete UPI payment and verify again.",
                    Toast.LENGTH_SHORT
                ).show()


            }
        }
    }

    private var isPaymentFailed = false

    private suspend fun checkStatusFromApi(isManual: Boolean): Boolean {
        if (orderId <= 0 || isPaymentCompleted || isPaymentFailed) return false

        try {
            Log.d("KIOSK_PAYMENT_DEBUG", "🔍 Checking SIB payment status: orderId=$orderId, pspRefNo=$pspRefNo (manual=$isManual)")
            val result = orderRepository.checkSibPaymentStatus(orderId)
            val response = result.getOrNull()

            Log.d("KIOSK_PAYMENT_DEBUG", "📊 SIB Status Response: ${response?.status}, Data: ${response?.data}")

            val statusItem = response?.data?.firstOrNull()
            val paymentStatus = statusItem?.status?.uppercase(Locale.US) ?: ""
            val message = statusItem?.message ?: ""

            if (paymentStatus == "PAID" || paymentStatus == "SUCCESS" || paymentStatus == "COMPLETED" || paymentStatus == "S") {
                Log.d("KIOSK_PAYMENT_DEBUG", "✅ Payment Verified Successfully: Status = $paymentStatus")
                isPaymentCompleted = true
                countDownTimer?.cancel()
                navigateToReceiptScreen()
                return true
            } else if (paymentStatus == "F" || paymentStatus == "FAILED" || paymentStatus == "CANCELLED") {
                Log.e("KIOSK_PAYMENT_DEBUG", "❌ Payment Failed: Status = $paymentStatus, Message = $message")
                isPaymentFailed = true
                countDownTimer?.cancel()
                withContext(Dispatchers.Main) {
                    showPaymentFailed(message)
                }
            } else {
                Log.d("KIOSK_PAYMENT_DEBUG", "⏳ Payment still pending: '$paymentStatus'")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error checking payment status: ${e.message}", e)
        }
        return false
    }

    private fun showPaymentFailed(apiMessage: String) {
        val text = if (apiMessage.contains("Invalid PsprefNo", ignoreCase = true)) {
            "We could not verify your payment. If money was deducted, please contact the counter with Order No. $orderId."
        } else {
            "Payment failed. Please try again or contact the counter."
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Payment not confirmed")
            .setMessage(text)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ -> }
            .show()

    }

    private fun navigateToReceiptScreen() {
        if (isFinishing || isDestroyed) return
        isPaymentCompleted = true
        countDownTimer?.cancel()

        val isPineLabs = com.xeniaone.templeapp.pinelabs.PineLabsManager.isPineLabsAvailable(this)
        val prefs = getSharedPreferences("kiosk_settings", Context.MODE_PRIVATE)
        val isPrintOn = if (isPineLabs) true else prefs.getBoolean("pref_print_enabled", true)

        if (isPrintOn) {
            val intent = Intent(this@PaymentQrActivity, PaymentActivity::class.java).apply {
                putExtra(PaymentActivity.EXTRA_CUSTOMER_NAME, intent.getStringExtra(EXTRA_CUSTOMER_NAME) ?: "")
                putExtra(PaymentActivity.EXTRA_CUSTOMER_MOBILE, intent.getStringExtra(EXTRA_CUSTOMER_MOBILE) ?: "")
                putExtra(PaymentActivity.EXTRA_ORDER_ID, orderId.toLong())
                putExtra(PaymentActivity.EXTRA_PSP_REF_NO, pspRefNo)
                putExtra(PaymentActivity.EXTRA_TOTAL_AMOUNT, totalAmount)
                putExtra(PaymentActivity.EXTRA_RECEIPT_ITEMS_JSON, receiptItemsJson)
                putExtra(PaymentActivity.EXTRA_LANGUAGE_CODE, currentLanguage)
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "Payment successful (Print Disabled)", Toast.LENGTH_SHORT).show()
            val intent = Intent(this@PaymentQrActivity, BillingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
        }
        finish()
    }

    private fun showPaymentExpiredDialog() {
        AlertDialog.Builder(this)
            .setTitle("Session Expired")
            .setMessage("The payment QR code has expired after 5 minutes. Please return to billing to place your order again.")
            .setPositiveButton("Return to Billing") { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }
}
