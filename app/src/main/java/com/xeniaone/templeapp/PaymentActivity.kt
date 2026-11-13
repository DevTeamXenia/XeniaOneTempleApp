package com.xeniaone.templeapp

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.xeniaone.templeapp.model.ReceiptItem
import com.xeniaone.templeapp.pinelabs.PineLabsConstants
import com.xeniaone.templeapp.pinelabs.PineLabsManager
import com.xeniaone.templeapp.repository.OrderRepository
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
    private var orderNo: String = ""
    private var pspRefNo: String = ""
    private var totalAmount: Double = 0.0
    private var receiptItems: List<ReceiptItem> = emptyList()
    private var currentLanguage: String = "en"
    private var formattedDate: String = ""

    private var paymentMode: String = ""
    private var cardNumber: String = ""
    private var approvalCode: String = ""
    private var invoiceNumber: Long = 0L
    private var rrn: String = ""
    private var cardType: String = ""
    private var orderCustomerName: String = ""
    private var orderMobileNumber: String = ""

    // Launcher for Pine Labs App-to-App Print Intent (MethodId: 1002)
    private val pineLabsPrintLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isPrintingInProgress = false
        Log.d(TAG, "🖨️ [PineLabs Print Result] resultCode=${result.resultCode}, dataIsNull=${result.data == null}")
        if (result.data != null) {
            val extras = result.data?.extras
            if (extras != null) {
                for (key in extras.keySet()) {
                    Log.d(TAG, "🖨️ [PineLabs Print Result Extra] $key = ${extras.get(key).toString()}")
                }
            }
        }
        val responseData = result.data?.getStringExtra(PineLabsConstants.EXTRA_RESPONSE_DATA)
        Log.d(TAG, "🖨️ [PineLabs Print Response DATA]: $responseData")

        val printResponse = PineLabsManager.parsePrintResponse(result.data)
        Log.d(TAG, "🖨️ [PineLabs Print Response Parsed]: isSuccess=${printResponse?.isSuccess}, code=${printResponse?.response?.responseCode}, msg=${printResponse?.response?.responseMsg}")

        binding.pbPrinter.visibility = View.GONE
        if (printResponse != null && printResponse.isSuccess) {
            PineLabsManager.resetRetryState()
            binding.tvPrinterStatus.text = "✅ Receipt printed on Pine Labs POS!"
            Log.i(TAG, "✅ [PineLabs Print] Receipt printed successfully on terminal!")
            startAutoReturnTimer()
        } else {
            val msg = printResponse?.response?.responseMsg ?: "Printer busy or finished"
            Log.w(TAG, "⚠️ [PineLabs Print] Print failed or returned warning: $msg (Code: ${printResponse?.response?.responseCode})")

            if (PineLabsManager.shouldRetry(printResponse)) {
                binding.tvPrinterStatus.text = "Retrying print (${PineLabsManager.currentRetryCount}/${PineLabsManager.MAX_PINE_LABS_RETRIES})..."
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val app = application as TempleApp
                        val authManager = app.container.authManager
                        val retryIntent = PineLabsManager.createRetryPrintIntent(
                            context = this,
                            applicationId = authManager.pineLabsApplicationId,
                            userId = authManager.pineLabsUserId
                        )
                        if (retryIntent != null) {
                            isPrintingInProgress = true
                            binding.pbPrinter.visibility = View.VISIBLE
                            launchPineLabsPrint(retryIntent)
                        } else {
                            binding.tvPrinterStatus.text = "Print status: $msg"
                            startAutoReturnTimer()
                        }
                    } catch (e: Exception) {
                        isPrintingInProgress = false
                        Log.e(TAG, "❌ [PineLabs Print Retry Error] ${e.message}", e)
                        binding.tvPrinterStatus.text = "Print error: ${e.message}"
                        startAutoReturnTimer()
                    }
                }, 15000L)
            } else {
                binding.tvPrinterStatus.text = "Print status: $msg"
                startAutoReturnTimer()
            }
        }
    }

    private fun launchPineLabsPrint(intent: Intent) {
        pineLabsPrintLauncher.launch(intent)
    }

    // Printer Connection state (Kiosk USB & POS)
    private var curConnect: IDeviceConnection? = null
    private var printerService: IPrinterService? = null
    private var isNyxBound = false
    private var serviceConnection: ServiceConnection? = null
    private var isPrintingInProgress = false

    // Kiosk Auto-return Timer
    private var autoReturnTimer: CountDownTimer? = null
    private val autoReturnDurationMs = 10000L

    private lateinit var orderRepository: OrderRepository
    private var encryptedOrderId: String = ""

    companion object {
        private const val TAG = "PaymentActivity"
        const val EXTRA_ORDER_ID = "extra_order_id"
        const val EXTRA_ORDER_NO = "extra_order_no"
        const val EXTRA_ENCRYPTED_ORDER_ID = "extra_encrypted_order_id"
        const val EXTRA_PSP_REF_NO = "extra_psp_ref_no"
        const val EXTRA_TOTAL_AMOUNT = "extra_total_amount"
        const val EXTRA_RECEIPT_ITEMS_JSON = "extra_receipt_items_json"
        const val EXTRA_LANGUAGE_CODE = "extra_language_code"
        const val EXTRA_PAYMENT_MODE = "extra_payment_mode"
        const val EXTRA_CARD_NUMBER = "extra_card_number"
        const val EXTRA_APPROVAL_CODE = "extra_approval_code"
        const val EXTRA_INVOICE_NUMBER = "extra_invoice_number"
        const val EXTRA_RRN = "extra_rrn"
        const val EXTRA_CARD_TYPE = "extra_card_type"
        const val EXTRA_CUSTOMER_NAME = "extra_customer_name"

        const val EXTRA_CUSTOMER_MOBILE = "extra_customer_mobile"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPaymentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as TempleApp
        orderRepository = app.container.orderRepository
        encryptedOrderId = intent.getStringExtra(EXTRA_ENCRYPTED_ORDER_ID) ?: ""

        // Read Intent Extras
        orderId = intent.getLongExtra(EXTRA_ORDER_ID, 0L)
        if (orderId == 0L) {
            orderId = intent.getIntExtra(EXTRA_ORDER_ID, 0).toLong()
        }
        orderNo = intent.getStringExtra(EXTRA_ORDER_NO) ?: ""
        orderCustomerName = intent.getStringExtra(EXTRA_CUSTOMER_NAME) ?: ""
        orderMobileNumber = intent.getStringExtra(EXTRA_CUSTOMER_MOBILE) ?: ""
        pspRefNo = intent.getStringExtra(EXTRA_PSP_REF_NO) ?: ""
        totalAmount = intent.getDoubleExtra(EXTRA_TOTAL_AMOUNT, 0.0)
        currentLanguage = intent.getStringExtra(EXTRA_LANGUAGE_CODE) ?: "en"

        // Pine Labs Extras
        paymentMode = intent.getStringExtra(EXTRA_PAYMENT_MODE) ?: ""
        cardNumber = intent.getStringExtra(EXTRA_CARD_NUMBER) ?: ""
        approvalCode = intent.getStringExtra(EXTRA_APPROVAL_CODE) ?: ""
        invoiceNumber = intent.getLongExtra(EXTRA_INVOICE_NUMBER, 0L)
        rrn = intent.getStringExtra(EXTRA_RRN) ?: ""
        cardType = intent.getStringExtra(EXTRA_CARD_TYPE) ?: ""

        val itemsJson = intent.getStringExtra(EXTRA_RECEIPT_ITEMS_JSON)
        receiptItems = parseReceiptItems(itemsJson)

        // Generate formatted date matching proposal design: "04 - Sep - 2024  07: 50 AM"
        formattedDate = SimpleDateFormat("dd - MMM - yyyy  hh: mm a", Locale.ENGLISH).format(Date())

        fetchOrderDetailsAndInitializePrint()
    }

    private fun fetchOrderDetailsAndInitializePrint() {
        if (orderId <= 0L) {
            initializeUiAndPrinting()
            return
        }

        lifecycleScope.launch {
            try {
                Log.d(TAG, "Fetching order details for orderId=$orderId from GET api/Order/$orderId")
                val result = orderRepository.getOrderDetails(orderId)
                val orderData = result.getOrNull()?.data
                if (orderData != null) {
                    if (!orderData.encryptedOrderId.isNullOrBlank()) {
                        encryptedOrderId = orderData.encryptedOrderId
                    }
                    if (!orderData.orderNo.isNullOrBlank()) {
                        orderNo = orderData.orderNo
                    }
                    Log.d(TAG, "Fetched order details: orderNo=$orderNo, encryptedOrderId=$encryptedOrderId")
                } else {
                    Log.w(TAG, "getOrderDetails failed or returned null: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching order details for orderId=$orderId: ${e.message}", e)
            }
            withContext(Dispatchers.Main) {
                initializeUiAndPrinting()
            }
        }
    }

    private fun initializeUiAndPrinting() {
        setupUI()
        setupListeners()

        // Detect if running on Pine Labs device
        if (PineLabsManager.isPineLabsAvailable(this)) {
            Log.d(TAG, "Pine Labs POS detected. Initializing Pine Labs print...")
            binding.tvPrinterStatus.text = "Pine Labs POS detected. Printing receipt..."
            Handler(Looper.getMainLooper()).postDelayed({
                printReceiptOnPineLabs()
            }, 600)
        } else {
            bindNyxPrinterService()
            initKioskPrinter()
        }

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
        val displayOrderId = if (orderNo.isNotBlank()) orderNo else if (orderId > 0L) orderId.toString() else "27533365"
        val displayPspRef = if (pspRefNo.isNotBlank()) pspRefNo else "5647784112"
        val displayTotal = if (totalAmount > 0.0) totalAmount else receiptItems.sumOf { it.total }

        // Populate Receipt Metadata
        binding.tvReceiptNo.text = displayOrderId
        binding.tvReceiptDate.text = formattedDate
        binding.tvReceiptTotalAmount.text = "Total Amount : ₹%.2f".format(Locale.US, displayTotal)

        val modeLabel = if (paymentMode.isNotBlank()) paymentMode else "UPI Payment"
        if (cardNumber.isNotBlank()) {
            binding.tvReceiptUpiRef.text = "$modeLabel: $cardNumber (Auth: $approvalCode)"
        } else if (rrn.isNotBlank()) {
            binding.tvReceiptUpiRef.text = "$modeLabel (RRN: $rrn)"
        } else if (pspRefNo.isNotBlank()) {
            binding.tvReceiptUpiRef.text = "$modeLabel Ref: $displayPspRef"
        } else {
            binding.tvReceiptUpiRef.text = "$modeLabel Success"
        }

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
                if (isPrintingInProgress) return
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

        // Prioritize Pine Labs POS print if on a Pine Labs terminal
        if (PineLabsManager.isPineLabsAvailable(this)) {
            printReceiptOnPineLabs()
            return
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

    /**
     * Initiates Pine Labs app-to-app text print request (MethodId: 1002 with PrintDataType=0).
     */
    private fun printReceiptOnPineLabs() {
        try {
            autoReturnTimer?.cancel()

            isPrintingInProgress = true
            binding.pbPrinter.visibility = View.VISIBLE
            binding.tvPrinterStatus.text = "Printing on Pine Labs POS..."

            val app = application as TempleApp
            val authManager = app.container.authManager

            val displayOrderId = if (orderNo.isNotBlank()) orderNo else if (orderId > 0L) orderId.toString() else ""
            val displayPaymentMode = if (paymentMode.isNotBlank()) paymentMode else "Pine Labs POS"
            val displayRef = if (rrn.isNotBlank()) rrn else if (approvalCode.isNotBlank()) approvalCode else pspRefNo
            val displayTotal = if (totalAmount > 0.0) totalAmount else receiptItems.sumOf { it.total }
            val qrData = if (encryptedOrderId.isNotBlank()) encryptedOrderId else ""

            Log.d(TAG, "🖨️ [PineLabs Text Print] Preparing print items for orderId=$displayOrderId, total=$displayTotal, mode=$displayPaymentMode")

            val footerBitmap = try {
                BitmapFactory.decodeResource(resources, R.drawable.footer)
            } catch (_: Exception) {
                null
            }

            val currentUserName = authManager.employeeName.ifBlank { authManager.savedUsername }
            val printItems = PineLabsManager.buildTempleReceiptPrintItems(
                orderId = displayOrderId,
                dateFormatted = formattedDate,
                paymentRef = displayRef,
                paymentModeTitle = displayPaymentMode,
                items = receiptItems,
                totalAmount = displayTotal,
                qrData = qrData,
                customerName = orderCustomerName,
                mobileNumber = orderMobileNumber,
                userName = currentUserName,
                printerWidth = PineLabsConstants.PRINTER_WIDTH_32,
                imageBitmap = footerBitmap,
                footerMessage = authManager.footerMessage,
                companyBrand = authManager.companyBrand
            )

            PineLabsManager.cachePrintSession(displayOrderId, printItems)

            Log.d(TAG, "🖨️ [PineLabs Text Print] Built ${printItems.size} text print items.")
            printItems.forEachIndexed { index, item ->
                Log.d(TAG, "🖨️ [PrintItem $index] Type=${item.printDataType}, Center=${item.isCenterAligned}, Data='${item.dataToPrint}'")
            }
            Log.i(TAG, "🔑 [PineLabs AppId Log] PaymentActivity -> passing applicationId = ${authManager.pineLabsApplicationId}")

            val printIntent = PineLabsManager.createPrintIntent(
                context = this,
                printRefNo = displayOrderId,
                printItems = printItems,
                applicationId = authManager.pineLabsApplicationId,
                userId = authManager.pineLabsUserId
            )

            footerBitmap?.recycle()

            Log.d(TAG, "🖨️ [PineLabs Text Print] Launching print intent package=${printIntent.getPackage()}, action=${printIntent.action}")
            launchPineLabsPrint(printIntent)
        } catch (e: Exception) {
            isPrintingInProgress = false
            Log.e(TAG, "❌ [PineLabs Print] Error initiating Pine Labs text print: ${e.message}", e)
            binding.pbPrinter.visibility = View.GONE
            binding.tvPrinterStatus.text = "Print error: ${e.message}"
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
        val width = 576                       // printer dot width (80mm) - keep as is
        val margin = 24f
        val right = width - margin
        val maxTextWidth = width - 2 * margin

        // Column positions (tuned for the larger text, without No. column)
        val colName = margin
        val colNameMaxWidth = 230f            // item names wrap inside this width
        val colQtyCenter = 340f
        val colAmountRight = 460f
        val colTotalRight = right

        // ...column positions unchanged...

        // Footer image: scaled to the printable width, keeping its aspect ratio
        val footerTargetWidth = (width - 2 * margin).toInt()
        val footerBitmap: Bitmap? = try {
            val raw = BitmapFactory.decodeResource(resources, R.drawable.footersib)
            if (raw != null) {
                val scaledHeight = (raw.height * footerTargetWidth.toFloat() / raw.width).toInt()
                val scaled = Bitmap.createScaledBitmap(raw, footerTargetWidth, scaledHeight, true)
                if (scaled !== raw) raw.recycle()
                scaled
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Could not load footer image: ${e.message}", e)
            null
        }
        val footerExtraHeight = (footerBitmap?.height ?: 0) + 60

        // Canvas now includes room for the footer image
        val canvasHeight = 1900 + receiptItems.size * 160 + footerExtraHeight
        val bitmap = Bitmap.createBitmap(width, canvasHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            isFakeBoldText = true
        }

        var y = 46f

        // Splits text into lines that fit maxWidth using the current paint settings
        fun wrapLines(text: String, maxWidth: Float): List<String> {
            val lines = mutableListOf<String>()
            var remaining = text.trim()
            while (remaining.isNotEmpty()) {
                val count = paint.breakText(remaining, true, maxWidth, null)
                if (count >= remaining.length) {
                    lines.add(remaining)
                    break
                }
                var cut = remaining.lastIndexOf(' ', count)
                if (cut <= 0) cut = count
                lines.add(remaining.substring(0, cut).trim())
                remaining = remaining.substring(cut).trim()
            }
            return lines
        }

        // Draws wrapped text starting at current y; leaves y on the last line's baseline
        fun drawWrapped(text: String, x: Float, maxWidth: Float, lineHeight: Float) {
            val lines = wrapLines(text, maxWidth)
            lines.forEachIndexed { index, line ->
                if (index > 0) y += lineHeight
                canvas.drawText(line, x, y, paint)
            }
        }

        // ---------------- Temple Header ----------------
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 34f
        drawWrapped("Sabarimala Sri Dharmasastha Temple", width / 2f, maxTextWidth, 40f)

        y += 36f
        paint.textSize = 24f
        paint.color = Color.BLACK
        drawWrapped("Swamy Ayyappan Rd, Sannidhanam, Sabarimala, Kerala 689662", width / 2f, maxTextWidth, 30f)

        y += 32f
        drawWrapped("Phone: (703) 690-9355; website: www.sabarimala.com", width / 2f, maxTextWidth, 30f)

        y += 58f
        paint.textSize = 32f
        paint.color = Color.BLACK
        canvas.drawText("Prasadam Receipt", width / 2f, y, paint)

        // ---------------- Metadata (left aligned) ----------------
        y += 52f
        paint.textSize = 27f
        paint.color = Color.BLACK
        paint.textAlign = Paint.Align.LEFT
        val displayOrderId = if (orderNo.isNotBlank()) orderNo else if (orderId > 0L) orderId.toString() else "27533365"
        val displayPspRef = if (pspRefNo.isNotBlank()) pspRefNo else "5647784112"
        val displayTotal = if (totalAmount > 0.0) totalAmount else receiptItems.sumOf { it.total }

        canvas.drawText("Receipt No :   $displayOrderId", margin, y, paint)
        y += 38f
        canvas.drawText("Date       :   $formattedDate", margin, y, paint)

        // Devotee name and mobile: printed only when they are not empty
        if (orderCustomerName.isNotBlank()) {
            y += 38f
            drawWrapped("Name       :   ${orderCustomerName.trim()}", margin, maxTextWidth, 34f)
        }
        if (orderMobileNumber.isNotBlank()) {
            y += 38f
            canvas.drawText("Mobile     :   ${orderMobileNumber.trim()}", margin, y, paint)
        }

        // ---------------- Table header ----------------
        y += 32f
        paint.strokeWidth = 2.5f
        canvas.drawLine(margin, y, right, y, paint)

        y += 34f
        paint.textSize = 24f
        paint.color = Color.BLACK
        paint.textAlign = Paint.Align.LEFT
//        canvas.drawText("No.", colNo, y, paint)
        canvas.drawText("Item Name", colName, y, paint)

        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Qty", colQtyCenter, y, paint)

        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("Amount", colAmountRight, y, paint)
        canvas.drawText("Total", colTotalRight, y, paint)

        y += 16f
        canvas.drawLine(margin, y, right, y, paint)

        // ---------------- Dynamic rows ----------------
        paint.textSize = 24f
        paint.color = Color.BLACK
        val rowLineHeight = 30f
        receiptItems.forEach { item ->
            y += 42f

            // Item name may wrap onto several lines
            paint.textAlign = Paint.Align.LEFT
            val nameLines = wrapLines(item.name, colNameMaxWidth)

            nameLines.forEachIndexed { index, line ->
                canvas.drawText(line, colName, y + index * rowLineHeight, paint)
            }

            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(item.quantity.toString(), colQtyCenter, y, paint)

            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText("%.2f".format(Locale.US, item.rate), colAmountRight, y, paint)
            canvas.drawText("%.2f".format(Locale.US, item.total), colTotalRight, y, paint)

            y += (nameLines.size - 1).coerceAtLeast(0) * rowLineHeight
        }

        // Divider after items
        y += 24f
        canvas.drawLine(margin, y, right, y, paint)

        // ---------------- Totals (right aligned) ----------------
        y += 48f
        paint.textAlign = Paint.Align.RIGHT
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 34f
        canvas.drawText("Total Amount : ₹%.2f".format(Locale.US, displayTotal), right, y, paint)

        y += 38f
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 24f
        paint.color = Color.DKGRAY
        canvas.drawText(" Reference No: $displayPspRef", right, y, paint)

        val app = application as TempleApp
        val authManager = app.container.authManager
        val currentUserName = authManager.employeeName.ifBlank { authManager.savedUsername }
        if (currentUserName.isNotBlank()) {
            y += 34f
            canvas.drawText(" User: ${currentUserName.trim()}", right, y, paint)
        }

        // ---------------- Footer ----------------
        y += 62f
        paint.color = Color.BLACK
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 28f
        drawWrapped("Thank you for Your Generosity", width / 2f, maxTextWidth, 34f)

        // ---------------- Footer Message & Company Brand (below QR) ----------------
        val footerMsg = authManager.footerMessage
        val compBrand = authManager.companyBrand

        fun drawMultiLine(text: String) {
            if (text.isBlank()) return
            val normalized = text.replace("\\n", "\n")
            normalized.split("\n").forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotBlank()) {
                    y += 32f
                    paint.typeface = Typeface.DEFAULT
                    paint.textSize = 22f
                    paint.color = Color.DKGRAY
                    drawWrapped(trimmed, width / 2f, maxTextWidth, 28f)
                }
            }
        }

        if (footerMsg.isNotBlank()) {
            drawMultiLine(footerMsg)
        }

        // ---------------- Centered QR Code ----------------
        val qrSize = 280
        val qrData = if (encryptedOrderId.isNotBlank()) encryptedOrderId else if (orderId > 0) orderId.toString() else "27533365"
        val qrBitmap = QrCodeGenerator.generate(qrData, qrSize)
        y += 30f
        val qrLeft = (width - qrSize) / 2f
        canvas.drawBitmap(qrBitmap, qrLeft, y, null)
        qrBitmap.recycle()
        y += qrSize + 40f                      // bottom padding so the cutter doesn't clip the QR




        // ---------------- Footer image (below QR) ----------------
        if (footerBitmap != null) {
            y += 35f // Added space between footer text and footer image
            val footerLeft = (width - footerBitmap.width) / 2f
            canvas.drawBitmap(footerBitmap, footerLeft, y, null)
            y += footerBitmap.height
            footerBitmap.recycle()
        }
        y += 40f   // bottom padding so the cutter doesn't clip the image


        if (compBrand.isNotBlank()) {
            drawMultiLine(compBrand)
        }


// Crop to actual content height


        // Crop to actual content height
        val finalHeight = y.toInt().coerceAtMost(canvasHeight)
        val result = Bitmap.createBitmap(bitmap, 0, 0, width, finalHeight)
        if (result !== bitmap) bitmap.recycle()
        return result
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
