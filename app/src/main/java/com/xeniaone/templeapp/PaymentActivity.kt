package com.xeniaone.templeapp

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.xeniaone.templeapp.model.ReceiptItem
import com.xeniaone.templeapp.util.QrCodeGenerator
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xeniaone.templeapp.databinding.ActivityPaymentBinding
import com.xeniaone.templeapp.databinding.ItemReceiptRowBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.nyx.printerservice.print.IPrinterService
import net.posprinter.IDeviceConnection
import net.posprinter.IPOSListener
import net.posprinter.POSConnect
import net.posprinter.POSConst
import net.posprinter.POSPrinter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PaymentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPaymentBinding

    private var orderId: Long = 0
    private var pspRefNo: String = ""
    private var totalAmount: Double = 0.0
    private var receiptItems: List<ReceiptItem> = emptyList()
    private var currentLanguage: String = "en"
    private var formattedDate: String = ""

    // Printer Connection state (Kiosk USB & POS)
    private var curConnect: IDeviceConnection? = null
    private var printerService: IPrinterService? = null
    private var isNyxBound = false
    private var serviceConnection: ServiceConnection? = null
    private var isPrintingInProgress = false

    // Kiosk Auto-return Timer
    private var autoReturnTimer: CountDownTimer? = null
    private val autoReturnDurationMs = 15000L

    companion object {
        private const val TAG = "PaymentActivity"
        const val EXTRA_ORDER_ID = "extra_order_id"
        const val EXTRA_PSP_REF_NO = "extra_psp_ref_no"
        const val EXTRA_TOTAL_AMOUNT = "extra_total_amount"
        const val EXTRA_RECEIPT_ITEMS_JSON = "extra_receipt_items_json"
        const val EXTRA_LANGUAGE_CODE = "extra_language_code"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPaymentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Read Intent Extras
        orderId = intent.getLongExtra(EXTRA_ORDER_ID, 0L)
        if (orderId == 0L) {
            orderId = intent.getIntExtra(EXTRA_ORDER_ID, 0).toLong()
        }
        pspRefNo = intent.getStringExtra(EXTRA_PSP_REF_NO) ?: ""
        totalAmount = intent.getDoubleExtra(EXTRA_TOTAL_AMOUNT, 0.0)
        currentLanguage = intent.getStringExtra(EXTRA_LANGUAGE_CODE) ?: "en"

        val itemsJson = intent.getStringExtra(EXTRA_RECEIPT_ITEMS_JSON)
        receiptItems = parseReceiptItems(itemsJson)

        // Generate formatted date matching proposal design: "04 - Sep - 2024  07: 50 AM"
        formattedDate = SimpleDateFormat("dd - MMM - yyyy  hh: mm a", Locale.ENGLISH).format(Date())

        setupUI()
        setupListeners()
        bindNyxPrinterService()
        initKioskPrinter()
        startAutoReturnTimer()
    }

    private fun parseReceiptItems(json: String?): List<ReceiptItem> {
        if (!json.isNullOrBlank()) {
            try {
                val type = object : TypeToken<List<ReceiptItem>>() {}.type
                val items: List<ReceiptItem> = Gson().fromJson(json, type)
                if (items.isNotEmpty()) return items
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing receipt items json: ${e.message}", e)
            }
        }

        // Proposal default fallback items if none passed
        val fallbackTotal = if (totalAmount > 0.0) totalAmount else 520.00
        return listOf(
            ReceiptItem(no = 1, name = "Appam", quantity = 3, rate = 40.00, total = 120.00),
            ReceiptItem(no = 2, name = "Aravana", quantity = 5, rate = 80.00, total = 400.00)
        )
    }

    @SuppressLint("SetTextI18n")
    private fun setupUI() {
        val displayOrderId = if (orderId > 0) orderId.toString() else "27533365"
        val displayPspRef = if (pspRefNo.isNotBlank()) pspRefNo else "5647784112"
        val displayTotal = if (totalAmount > 0.0) totalAmount else receiptItems.sumOf { it.total }

        // Populate Receipt Metadata
        binding.tvReceiptNo.text = displayOrderId
        binding.tvReceiptDate.text = formattedDate
        binding.tvReceiptTotalAmount.text = "Total Amount : ₹%.2f".format(Locale.US, displayTotal)
        binding.tvReceiptUpiRef.text = "UPI Reference No: $displayPspRef"

        // Populate Dynamic Item Rows
        binding.llReceiptItems.removeAllViews()
        val inflater = LayoutInflater.from(this)
        receiptItems.forEach { item ->
            val rowBinding = ItemReceiptRowBinding.inflate(inflater, binding.llReceiptItems, false)
            rowBinding.tvItemNo.text = "%02d".format(item.no)
            rowBinding.tvItemName.text = item.name
            rowBinding.tvItemQty.text = item.quantity.toString()
            rowBinding.tvItemRate.text = "%.2f".format(Locale.US, item.rate)
            rowBinding.tvItemTotal.text = "%.2f".format(Locale.US, item.total)
            binding.llReceiptItems.addView(rowBinding.root)
        }

        // Centered QR Code at footer
        val qrData = "upi://pay?pa=sabarimala@sib&pn=SabarimalaTemple&tr=$displayOrderId&am=$displayTotal&cu=INR"
        val qrBitmap = QrCodeGenerator.generate(qrData, 240)
        binding.ivReceiptQr.setImageBitmap(qrBitmap)
    }

    private fun setupListeners() {
        binding.btnPrintReceipt.setOnClickListener {
            triggerPrint(isManual = true)
        }

        binding.btnNewOrder.setOnClickListener {
            returnToHome()
        }
    }

    private fun startAutoReturnTimer() {
        autoReturnTimer?.cancel()
        autoReturnTimer = object : CountDownTimer(autoReturnDurationMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                binding.btnNewOrder.text = "Done (${seconds}s)"
            }

            override fun onFinish() {
                returnToHome()
            }
        }.start()
    }

    private fun returnToHome() {
        autoReturnTimer?.cancel()
        val intent = Intent(applicationContext, BillingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRINTER INTEGRATION (POSConnect for KIOSK & Nyx PrinterService)
    // ─────────────────────────────────────────────────────────────────────────

    private val connectListener = IPOSListener { code, _ ->
        when (code) {
            POSConnect.CONNECT_SUCCESS -> {
                Log.d(TAG, "✅ KIOSK USB Printer connected successfully!")
                runOnUiThread {
                    binding.pbPrinter.visibility = View.GONE
                    binding.tvPrinterStatus.text = "KIOSK Printer Connected. Printing receipt..."
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    printReceiptOnKioskUsb()
                }
            }
            POSConnect.CONNECT_FAIL,
            POSConnect.CONNECT_INTERRUPT,
            POSConnect.SEND_FAIL -> {
                Log.e(TAG, "❌ KIOSK USB Printer connection failed (code=$code)")
                runOnUiThread {
                    binding.pbPrinter.visibility = View.GONE
                    binding.tvPrinterStatus.text = "Kiosk USB Printer not connected"
                }
            }
            POSConnect.USB_DETACHED -> {
                Log.w(TAG, "⚠️ KIOSK USB Printer detached")
            }
        }
    }

    private fun initKioskPrinter() {
        try {
            POSConnect.init(applicationContext)
            val entries = POSConnect.getUsbDevices(applicationContext)
            if (entries.isNotEmpty()) {
                Log.d(TAG, "🔌 Found ${entries.size} Kiosk USB printer(s): ${entries[0]}")
                connectKioskUsb(entries[0])
            } else {
                Log.d(TAG, "ℹ️ No Kiosk USB printer detected via POSConnect")
                // Fallback to Nyx service after 800ms if bound
                Handler(Looper.getMainLooper()).postDelayed({
                    if (printerService != null) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            printReceiptOnNyx()
                        }
                    } else {
                        binding.pbPrinter.visibility = View.GONE
                        binding.tvPrinterStatus.text = "Printer Ready"
                    }
                }, 1000)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Kiosk USB printer: ${e.message}", e)
            binding.pbPrinter.visibility = View.GONE
            binding.tvPrinterStatus.text = "Printer Ready"
        }
    }

    private fun connectKioskUsb(pathName: String) {
        try {
            curConnect?.close()
            curConnect = POSConnect.createDevice(POSConnect.DEVICE_TYPE_USB)
            curConnect?.connect(pathName, connectListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting USB device: ${e.message}", e)
        }
    }

    private fun bindNyxPrinterService() {
        val intent = Intent().apply {
            `package` = "net.nyx.printerservice"
            action = "net.nyx.printerservice.IPrinterService"
        }

        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                printerService = IPrinterService.Stub.asInterface(service)
                isNyxBound = true
                Log.d(TAG, "✅ Nyx Printer Service connected")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                printerService = null
                isNyxBound = false
                Log.w(TAG, "Nyx Printer Service disconnected")
            }
        }

        try {
            bindService(intent, serviceConnection!!, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.w(TAG, "Could not bind Nyx printer service: ${e.message}")
        }
    }

    private fun triggerPrint(isManual: Boolean) {
        if (isPrintingInProgress) {
            Toast.makeText(this, "Printing is already in progress...", Toast.LENGTH_SHORT).show()
            return
        }

        // Restart timer slightly on manual print so user has time to view
        if (isManual) {
            startAutoReturnTimer()
        }

        binding.pbPrinter.visibility = View.VISIBLE
        binding.tvPrinterStatus.text = "Printing receipt..."

        lifecycleScope.launch(Dispatchers.IO) {
            val kioskPrinted = if (curConnect != null) {
                printReceiptOnKioskUsb()
            } else false

            if (!kioskPrinted && printerService != null) {
                printReceiptOnNyx()
            } else if (!kioskPrinted && curConnect == null && printerService == null) {
                withContext(Dispatchers.Main) {
                    binding.pbPrinter.visibility = View.GONE
                    binding.tvPrinterStatus.text = "Receipt displayed on Kiosk screen"
                    if (isManual) {
                        Toast.makeText(this@PaymentActivity, "No physical thermal printer connected", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private suspend fun printReceiptOnKioskUsb(): Boolean {
        return try {
            isPrintingInProgress = true
            val conn = curConnect ?: return false
            val printer = POSPrinter(conn)
            val receiptBitmap = generateThermalReceiptBitmap()

            withContext(Dispatchers.Main) {
                binding.pbPrinter.visibility = View.VISIBLE
                binding.tvPrinterStatus.text = "Printing on Kiosk Thermal Printer..."
            }

            printer.printBitmap(receiptBitmap, POSConst.ALIGNMENT_CENTER, 576)
                .feedLine(2)
                .cutHalfAndFeed(1)

            receiptBitmap.recycle()
            isPrintingInProgress = false

            withContext(Dispatchers.Main) {
                binding.pbPrinter.visibility = View.GONE
                binding.tvPrinterStatus.text = "✅ Receipt printed successfully!"
            }
            true
        } catch (e: Exception) {
            isPrintingInProgress = false
            Log.e(TAG, "Error printing on Kiosk USB printer: ${e.message}", e)
            withContext(Dispatchers.Main) {
                binding.pbPrinter.visibility = View.GONE
                binding.tvPrinterStatus.text = "Print error: ${e.message}"
            }
            false
        }
    }

    private suspend fun printReceiptOnNyx(): Boolean {
        return try {
            val service = printerService ?: return false
            isPrintingInProgress = true
            val receiptBitmap = generateThermalReceiptBitmap()

            withContext(Dispatchers.Main) {
                binding.pbPrinter.visibility = View.VISIBLE
                binding.tvPrinterStatus.text = "Printing on POS Terminal..."
            }

            service.printBitmap(receiptBitmap, 0, POSConst.ALIGNMENT_CENTER)
            service.printText("\n\n\n", null)
            service.printEndAutoOut()

            receiptBitmap.recycle()
            isPrintingInProgress = false

            withContext(Dispatchers.Main) {
                binding.pbPrinter.visibility = View.GONE
                binding.tvPrinterStatus.text = "✅ Receipt printed successfully!"
            }
            true
        } catch (e: RemoteException) {
            isPrintingInProgress = false
            Log.e(TAG, "RemoteException in Nyx printing: ${e.message}", e)
            withContext(Dispatchers.Main) {
                binding.pbPrinter.visibility = View.GONE
                binding.tvPrinterStatus.text = "Printer service error"
            }
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // THERMAL RECEIPT BITMAP GENERATION (Standard 80mm / 576 dots ESC/POS)
    // ─────────────────────────────────────────────────────────────────────────

    private fun generateThermalReceiptBitmap(): Bitmap {
        val width = 576
        val itemLineHeight = 36
        val dynamicHeight = 780 + (receiptItems.size * itemLineHeight)

        val bitmap = Bitmap.createBitmap(width, dynamicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
        }

        var y = 40f

        // Temple Header
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 26f
        canvas.drawText("Sabarimala Sri Dharmasastha Temple", width / 2f, y, paint)

        y += 28f
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 17f
        paint.color = Color.DKGRAY
        canvas.drawText("Swamy Ayyappan Rd, Sannidhanam, Sabarimala, Kerala 689662", width / 2f, y, paint)

        y += 24f
        canvas.drawText("Phone: (703) 690-9355; website: www.sabarimala.com", width / 2f, y, paint)

        y += 42f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 22f
        paint.color = Color.BLACK
        canvas.drawText("Prasadam Receipt", width / 2f, y, paint)

        // Metadata Left Aligned
        y += 42f
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 19f
        paint.textAlign = Paint.Align.LEFT
        val displayOrderId = if (orderId > 0) orderId.toString() else "27533365"
        val displayPspRef = if (pspRefNo.isNotBlank()) pspRefNo else "5647784112"
        val displayTotal = if (totalAmount > 0.0) totalAmount else receiptItems.sumOf { it.total }

        canvas.drawText("Receipt No :   $displayOrderId", 20f, y, paint)
        y += 28f
        canvas.drawText("Date       :   $formattedDate", 20f, y, paint)

        // Table Header Divider
        y += 25f
        paint.strokeWidth = 1.5f
        canvas.drawLine(20f, y, width - 20f, y, paint)

        // Columns: No. | Item Name | Qty | Amount | Total
        y += 24f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 18f
        canvas.drawText("No.", 20f, y, paint)
        canvas.drawText("Item Name", 75f, y, paint)

        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Qty", 330f, y, paint)

        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("Amount", 450f, y, paint)
        canvas.drawText("Total", width - 20f, y, paint)

        // Divider below header
        y += 12f
        canvas.drawLine(20f, y, width - 20f, y, paint)

        // Dynamic Rows
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 18f
        receiptItems.forEach { item ->
            y += 32f
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText("%02d".format(item.no), 20f, y, paint)
            canvas.drawText(item.name, 75f, y, paint)

            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(item.quantity.toString(), 330f, y, paint)

            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText("%.2f".format(Locale.US, item.rate), 450f, y, paint)
            canvas.drawText("%.2f".format(Locale.US, item.total), width - 20f, y, paint)
        }

        // Divider after items
        y += 18f
        canvas.drawLine(20f, y, width - 20f, y, paint)

        // Totals Section (Right-aligned)
        y += 36f
        paint.textAlign = Paint.Align.RIGHT
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 24f
        canvas.drawText("Total Amount : ₹%.2f".format(Locale.US, displayTotal), width - 20f, y, paint)

        y += 28f
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 18f
        paint.color = Color.DKGRAY
        canvas.drawText("UPI Reference No: $displayPspRef", width - 20f, y, paint)

        // Footer Section
        y += 45f
        paint.color = Color.BLACK
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 20f
        canvas.drawText("Thank you for Your Generosity", width / 2f, y, paint)

        y += 26f
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 16f
        paint.color = Color.GRAY
        canvas.drawText("Powered by www.xeniaonline.in", width / 2f, y, paint)

        // Centered QR Code
        val qrData = "upi://pay?pa=sabarimala@sib&pn=SabarimalaTemple&tr=$displayOrderId&am=$displayTotal&cu=INR"
        val qrBitmap = QrCodeGenerator.generate(qrData, 170)
        y += 20f
        val qrLeft = (width - 170) / 2f
        canvas.drawBitmap(qrBitmap, qrLeft, y, null)
        qrBitmap.recycle()

        return bitmap
    }

    override fun onDestroy() {
        super.onDestroy()
        autoReturnTimer?.cancel()
        try {
            curConnect?.close()
        } catch (_: Exception) {}
        if (isNyxBound && serviceConnection != null) {
            try {
                unbindService(serviceConnection!!)
            } catch (_: Exception) {}
        }
    }
}
