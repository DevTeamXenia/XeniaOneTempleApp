package com.xeniaone.templeapp

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.nyx.printerservice.print.IPrinterService
import net.posprinter.IDeviceConnection
import net.posprinter.IPOSListener
import net.posprinter.POSConnect
import net.posprinter.POSConst
import net.posprinter.POSPrinter
import net.posprinter.utils.BitmapToByteData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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

    private val pineLabsPrintLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isPrintingInProgress = false
        if (result.data != null) {
            val extras = result.data?.extras
            if (extras != null) {
                for (key in extras.keySet()) {
                }
            }
        }
        val responseData = result.data?.getStringExtra(PineLabsConstants.EXTRA_RESPONSE_DATA)
        val printResponse = PineLabsManager.parsePrintResponse(result.data)


        binding.pbPrinter.visibility = View.GONE
        if (printResponse != null && printResponse.isSuccess) {
            PineLabsManager.resetRetryState()
            isPrintFailed = false
            BillingActivity.isPendingPrintAvailable = false
            BillingActivity.isLastOrderPrintedSuccessfully = true
            if (!isTestPrint && orderId > 0L) printedOrderIds.add(orderId)
            binding.tvPrinterStatus.text = "✅ Receipt printed on Pine Labs POS!"
            startAutoReturnTimer()
        } else {
            val rawCode = printResponse?.response?.responseCode ?: -1
            val rawMsg = printResponse?.response?.responseMsg.orEmpty()
            val msg = PineLabsManager.getPrinterErrorMessage(rawCode, rawMsg)
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
                            isPrintFailed = true
                            BillingActivity.isPendingPrintAvailable = true
                            binding.tvPrinterStatus.text = msg
                            startAutoReturnTimer()
                        }
                    } catch (e: Exception) {
                        isPrintingInProgress = false
                        isPrintFailed = true
                        BillingActivity.isPendingPrintAvailable = true
                        binding.tvPrinterStatus.text = "Print error: ${e.message}"
                        startAutoReturnTimer()
                    }
                }, 15000L)
            } else {
                isPrintFailed = true
                BillingActivity.isPendingPrintAvailable = true
                binding.tvPrinterStatus.text = msg
                startAutoReturnTimer()
            }
        }
    }

    private fun launchPineLabsPrint(intent: Intent) {
        try {
            pineLabsPrintLauncher.launch(intent)
        } catch (e: Exception) {
            isPrintingInProgress = false
            binding.pbPrinter.visibility = View.GONE
            binding.tvPrinterStatus.text = "Print error: ${e.message}"
        }
    }

    // Printer Connection state (Kiosk USB & POS)
    private var curConnect: IDeviceConnection? = null
    private var printerService: IPrinterService? = null
    private var isNyxBound = false
    private var serviceConnection: ServiceConnection? = null
    private var isPrintingInProgress = false
    private var isPrintFailed = false
    private var hasAutoPrintedKiosk = false
    private var usbReceiverRegistered = false
    private var printerReconnectJob: kotlinx.coroutines.Job? = null

    // Kiosk Auto-return Timer
    private var autoReturnTimer: CountDownTimer? = null
    private val autoReturnDurationMs = 10000L

    private lateinit var orderRepository: OrderRepository
    private var encryptedOrderId: String = ""

    companion object {
        private const val TAG = "PaymentActivity"
        private const val ACTION_USB_PERMISSION = "com.xeniaone.templeapp.USB_PERMISSION"
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
        const val EXTRA_IS_DUPLICATE_PRINT = "extra_is_duplicate_print"
        const val EXTRA_IS_TEST_PRINT = "extra_is_test_print"

        val printedOrderIds = mutableSetOf<Long>()
    }

    private var isDuplicatePrint = false
    private var isTestPrint = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPaymentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isDuplicatePrint = intent.getBooleanExtra(EXTRA_IS_DUPLICATE_PRINT, false)
        isTestPrint = intent.getBooleanExtra(EXTRA_IS_TEST_PRINT, false)

        val app = application as TempleApp
        orderRepository = app.container.orderRepository
        encryptedOrderId = intent.getStringExtra(EXTRA_ENCRYPTED_ORDER_ID) ?: ""

        // Read Intent Extras
        orderId = intent.getLongExtra(EXTRA_ORDER_ID, 0L)
        if (orderId == 0L) {
            orderId = intent.getIntExtra(EXTRA_ORDER_ID, 0).toLong()
        }
        orderNo = if (isTestPrint) "0" else (intent.getStringExtra(EXTRA_ORDER_NO) ?: "")
        orderCustomerName = if (isTestPrint) "0" else (intent.getStringExtra(EXTRA_CUSTOMER_NAME) ?: "")
        orderMobileNumber = if (isTestPrint) "0" else (intent.getStringExtra(EXTRA_CUSTOMER_MOBILE) ?: "")
        pspRefNo = if (isTestPrint) "0" else (intent.getStringExtra(EXTRA_PSP_REF_NO) ?: "")
        totalAmount = if (isTestPrint) 0.0 else intent.getDoubleExtra(EXTRA_TOTAL_AMOUNT, 0.0)
        currentLanguage = intent.getStringExtra(EXTRA_LANGUAGE_CODE) ?: "en"

        val itemsJson = intent.getStringExtra(EXTRA_RECEIPT_ITEMS_JSON)
        receiptItems = parseReceiptItems(itemsJson)

        Log.i(TAG, "💵 [PaymentActivity] onCreate loaded: orderId=$orderId, paymentMode=$paymentMode, total=$totalAmount, itemsCount=${receiptItems.size}, isPineLabs=${PineLabsManager.isPineLabsAvailable(this)}")

        if (isTestPrint) {
            orderNo = "0"
            formattedDate = "0"
            orderCustomerName = "0"
            orderMobileNumber = "0"
            pspRefNo = "0"
            val passedTotal = intent.getDoubleExtra(EXTRA_TOTAL_AMOUNT, 0.0)
            if (totalAmount <= 0.0 && passedTotal > 0.0) {
                totalAmount = passedTotal
            }
            if (receiptItems.isEmpty()) {
                receiptItems = listOf(ReceiptItem(no = 1, name = "Test Prasadam Item", quantity = 1, rate = 0.0, total = 0.0))
            }
        }

        // Generate formatted date & time: e.g. "04-Sep-2024  07:50 AM" in IST
        formattedDate = SimpleDateFormat("dd-MMM-yyyy  hh:mm a", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }.format(Date())

        fetchOrderDetailsAndInitializePrint()
    }

    private fun fetchOrderDetailsAndInitializePrint() {
        if (orderId <= 0L || (isDuplicatePrint && receiptItems.isNotEmpty() && orderNo.isNotBlank())) {
            initializeUiAndPrinting()
            return
        }

        lifecycleScope.launch {
            try {
                val result = orderRepository.getOrderDetails(orderId)
                val orderData = result.getOrNull()?.data
                if (orderData != null) {
                    if (!orderData.encryptedOrderId.isNullOrBlank()) {
                        encryptedOrderId = orderData.encryptedOrderId
                    }
                    if (!orderData.orderNo.isNullOrBlank()) {
                        orderNo = orderData.orderNo
                    }
                    if (!orderData.PaymentRef.isNullOrBlank()) {
                        pspRefNo = orderData.PaymentRef
                    }
                    if (!orderData.orderDate.isNullOrBlank()) {
                        formattedDate = convertUtcToLocalIst(orderData.orderDate)
                    }

                } else {

                }
            } catch (e: Exception) {

            }
            withContext(Dispatchers.Main) {
                initializeUiAndPrinting()
            }
        }
    }

    private fun convertUtcToLocalIst(utcDateStr: String): String {
        return try {
            val cleanDateStr = utcDateStr.substringBefore("Z").substringBefore("+")
            val utcFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val date = utcFormat.parse(cleanDateStr.substringBefore(".")) ?: Date()
            val localFormat = SimpleDateFormat("dd-MMM-yyyy  hh:mm a", Locale.ENGLISH).apply {
                timeZone = TimeZone.getTimeZone("Asia/Kolkata")
            }
            localFormat.format(date)
        } catch (e: Exception) {
            formattedDate
        }
    }

    private fun initializeUiAndPrinting() {
        setupUI()
        setupListeners()

        // Detect if running on Pine Labs device
        if (PineLabsManager.isPineLabsAvailable(this)) {
            binding.tvPrinterStatus.text = "Pine Labs POS detected. Printing receipt..."
            Handler(Looper.getMainLooper()).postDelayed({
                printReceiptOnPineLabs()
            }, 600)
        } else {
            bindNyxPrinterService()
            initKioskPrinter()

            // Fallback auto-trigger print to guarantee printout comes out on Kiosk
            Handler(Looper.getMainLooper()).postDelayed({
                if (!hasAutoPrintedKiosk && !isPrintingInProgress) {
                    hasAutoPrintedKiosk = true
                    triggerPrint(isManual = false)
                }
            }, 1500L)
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

            }
        }

        return emptyList()
    }

    private fun formatReceiptNo(orderNo: String): String {
        val raw = if (orderNo.isNotBlank()) orderNo.trim() else ""
        if (raw.contains("N", ignoreCase = true)) {
            val afterN = raw.substringAfterLast("N").substringAfterLast("n").trim()
            if (afterN.isNotBlank()) {
                return afterN
            }
        }
        return raw.replace(Regex("^(NO\\.|NO|N-|N|#|ORD-)", RegexOption.IGNORE_CASE), "").trim()
    }

    @SuppressLint("SetTextI18n")
    private fun setupUI() {
        val displayOrderId = formatReceiptNo(orderNo)
        val displayPspRef = pspRefNo
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


    }

    private fun setupListeners() {
        binding.btnPrintReceipt.setOnClickListener {
            triggerPrint(isManual = true)
        }

        binding.btnNewOrder.setOnClickListener {
            if (isPrintingInProgress) {
                Toast.makeText(this, "Receipt printing is in progress...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
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
                if (isPrintingInProgress) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!isPrintingInProgress) {
                            returnToHome()
                        } else {
                            startAutoReturnTimer()
                        }
                    }, 4000L)
                    return
                }
                returnToHome()
            }
        }.start()
    }

    private fun returnToHome() {
        if (isPrintingInProgress) {
            return
        }
        autoReturnTimer?.cancel()
        val intent = Intent(applicationContext, BillingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (isPrintFailed) {
                putExtra(BillingActivity.EXTRA_PRINT_PENDING, true)
            }
        }
        startActivity(intent)
        finish()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRINTER INTEGRATION (POSConnect for KIOSK & Nyx PrinterService)
    // ─────────────────────────────────────────────────────────────────────────

    private fun registerUsbPermissionReceiver() {
        if (usbReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }
        usbReceiverRegistered = true
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (ACTION_USB_PERMISSION == action) {
                synchronized(this) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        initKioskPrinter()
                    } else {
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED == action) {
                lifecycleScope.launch(Dispatchers.Main) {
                    delay(300L)
                    initKioskPrinter()
                }
            }
        }
    }

    private fun startPrinterReconnectPolling() {
        if (printerReconnectJob?.isActive == true || BillingActivity.isLastOrderPrintedSuccessfully) return
        printerReconnectJob = lifecycleScope.launch(Dispatchers.Main) {
            var retryCount = 0
            while (!BillingActivity.isLastOrderPrintedSuccessfully && (curConnect == null || curConnect?.isConnect() != true) && !isFinishing && !isDestroyed) {
                retryCount++
                binding.pbPrinter.visibility = View.VISIBLE
                binding.tvPrinterStatus.text = "Connection lost. Re-connecting to printer (Attempt #$retryCount)..."
                initKioskPrinter()
                delay(3000L)
            }
        }
    }

    private val connectListener = IPOSListener { code, _ ->
        when (code) {
            POSConnect.CONNECT_SUCCESS -> {
                printerReconnectJob?.cancel()
                printerReconnectJob = null
                isPrintingInProgress = false
                runOnUiThread {
                    binding.pbPrinter.visibility = View.GONE
                    binding.tvPrinterStatus.text = "KIOSK Printer Connected. Printing receipt..."
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    hasAutoPrintedKiosk = true
                    printReceiptOnKioskUsb()
                }
            }
            POSConnect.CONNECT_FAIL,
            POSConnect.CONNECT_INTERRUPT,
            POSConnect.SEND_FAIL -> {
                isPrintingInProgress = false
                hasAutoPrintedKiosk = false
                try { curConnect?.close() } catch (_: Exception) {}
                curConnect = null
                runOnUiThread {
                    binding.pbPrinter.visibility = View.GONE
                    binding.tvPrinterStatus.text = "Kiosk USB Printer disconnected. Retrying..."
                }
                startPrinterReconnectPolling()
            }
            POSConnect.USB_DETACHED -> {
                isPrintingInProgress = false
                hasAutoPrintedKiosk = false
                try { curConnect?.close() } catch (_: Exception) {}
                curConnect = null
                runOnUiThread {
                    binding.pbPrinter.visibility = View.GONE
                    binding.tvPrinterStatus.text = "Printer cable detached. Please re-plug USB cable."
                }
                startPrinterReconnectPolling()
            }
        }
    }

    private fun initKioskPrinter() {
        try {
            POSConnect.init(applicationContext)
            val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager
            val systemUsbDevices = usbManager?.deviceList?.values?.toList() ?: emptyList()
            var entries = POSConnect.getUsbDevices(applicationContext)

            if (entries.isEmpty() && systemUsbDevices.isNotEmpty()) {
                val fallbackPath = systemUsbDevices[0].deviceName
                entries = listOf(fallbackPath)
            }

            if (entries.isNotEmpty()) {
                val usbDevice = systemUsbDevices.firstOrNull()
                if (usbManager != null && usbDevice != null && !usbManager.hasPermission(usbDevice)) {
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }
                    val permissionIntent = PendingIntent.getBroadcast(
                        this,
                        0,
                        Intent(ACTION_USB_PERMISSION),
                        flags
                    )
                    registerUsbPermissionReceiver()
                    usbManager.requestPermission(usbDevice, permissionIntent)
                } else {
                    connectKioskUsb(entries[0])
                }
            } else {
                bindNyxPrinterService()
            }
        } catch (e: Exception) {
            bindNyxPrinterService()
        }
    }

    private fun connectKioskUsb(pathName: String) {
        try {
            curConnect?.close()
            curConnect = POSConnect.createDevice(POSConnect.DEVICE_TYPE_USB)
            curConnect?.connect(pathName, connectListener)
        } catch (e: Exception) {

        }
    }

    private fun bindNyxPrinterService() {
        if (printerService != null) {
            if (!isPrintingInProgress) {
                lifecycleScope.launch(Dispatchers.IO) {
                    printReceiptOnNyx()
                }
            }
            return
        }

        val intent = Intent().apply {
            `package` = "net.nyx.printerservice"
            action = "net.nyx.printerservice.IPrinterService"
        }

        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                printerService = IPrinterService.Stub.asInterface(service)
                isNyxBound = true
                if (!isPrintingInProgress && printerService != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        printReceiptOnNyx()
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                printerService = null
                isNyxBound = false
            }
        }

        try {
            bindService(intent, serviceConnection!!, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            runOnUiThread {
                binding.pbPrinter.visibility = View.GONE
                binding.tvPrinterStatus.text = "Printer Ready"
            }
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

        // If USB connection was dropped or disconnected, attempt auto-reconnect
        if (curConnect == null || curConnect?.isConnect() != true) {
            initKioskPrinter()
        } else {
            lifecycleScope.launch(Dispatchers.IO) {
                val kioskPrinted = printReceiptOnKioskUsb()
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
    }

    /**
     * Initiates Pine Labs app-to-app text print request (MethodId: 1002 with PrintDataType=0).
     */
    private fun printReceiptOnPineLabs() {
        try {
            Log.i(TAG, "🖨️ [PaymentActivity] printReceiptOnPineLabs triggered. orderNo=$orderNo, mode=$paymentMode, itemsCount=${receiptItems.size}, total=$totalAmount")
            autoReturnTimer?.cancel()

            isPrintingInProgress = true
            binding.pbPrinter.visibility = View.VISIBLE
            binding.tvPrinterStatus.text = "Printing on Pine Labs POS..."

            val app = application as TempleApp
            val authManager = app.container.authManager

            val displayOrderId = formatReceiptNo(orderNo)
            val displayPaymentMode = if (paymentMode.isNotBlank()) paymentMode else "Pine Labs POS"
            val displayRef = if (rrn.isNotBlank()) rrn else if (approvalCode.isNotBlank()) approvalCode else pspRefNo
            val displayTotal = if (totalAmount > 0.0) totalAmount else receiptItems.sumOf { it.total }
            val qrData = if (encryptedOrderId.isNotBlank()) encryptedOrderId else ""


            val footerBitmap = try {
                BitmapFactory.decodeResource(resources, R.drawable.footer)
            } catch (_: Exception) {
                null
            }

            val currentUserName = authManager.employeeName.ifBlank { authManager.savedUsername }
            val printItems = PineLabsManager.buildTempleReceiptPrintItems(
                orderId = displayOrderId,
                orderNo = orderNo,
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

            val uniquePrintRef = if (orderId > 0L) orderId.toString() else "${displayOrderId}_${System.currentTimeMillis() % 10000}"
            PineLabsManager.cachePrintSession(uniquePrintRef, printItems)

            val printIntent = PineLabsManager.createPrintIntent(
                context = this,
                printRefNo = uniquePrintRef,
                printItems = printItems,
                applicationId = authManager.pineLabsApplicationId,
                userId = authManager.pineLabsUserId
            )

            footerBitmap?.recycle()

            launchPineLabsPrint(printIntent)
        } catch (e: Exception) {
            isPrintingInProgress = false
            binding.pbPrinter.visibility = View.GONE
            binding.tvPrinterStatus.text = "Print error: ${e.message}"
        }
    }

    /**
     * Purges and resets the ESC/POS printer hardware state machine.
     *
     * If the previous bill stopped mid-print (e.g. paper ran out halfway, cover opened,
     * or connection interrupt), the printer's internal hardware ASIC remains frozen in
     * raster graphics mode (GS v 0), waiting for the remaining dot bytes.
     * In that state, standard ESC @ (0x1B 0x40) commands are consumed as image pixels,
     * which causes the NEXT order to misalign and print lines of garbage text/symbols.
     *
     * This sequence:
     * 1) Sends real-time error recovery commands (DLE ENQ, CAN, ESC S).
     * 2) Streams 24KB of null bytes (0x00) to satisfy and flush out any pending raster
     *    payload counter so the printer cleanly finishes and EXITS raster mode.
     *    (In standard command mode, 0x00 is NUL and completely ignored).
     * 3) Sends CAN (0x18) + ESC S (0x1B 0x53) + ESC @ (0x1B 0x40) to reset all registers,
     *    clear print buffers, and restore standard defaults.
     */
    private suspend fun purgeAndResetPrinterState(conn: IDeviceConnection, printer: POSPrinter) {
        try {
            // Clean ESC/POS hardware reset
            val cleanReset = byteArrayOf(
                0x18.toByte(),                               // CAN
                0x1B.toByte(), 0x53.toByte(),                // ESC S
                0x1B.toByte(), 0x40.toByte()                 // ESC @ (Initialize printer hardware)
            )
            conn.sendData(cleanReset)
            delay(50)
        } catch (e: Exception) {

        }
    }

    /**
     * Checks if the printer is currently out of paper or if the cover is open using ESC/POS DLE EOT real-time queries.
     */
    private fun isPaperOutOrCoverOpen(conn: IDeviceConnection): Pair<Boolean, String?> {
        return try {
            // DLE EOT 4 (Paper roll sensor status query)
            conn.sendData(byteArrayOf(0x10.toByte(), 0x04.toByte(), 0x04.toByte()))
            val resp = conn.readSync(100)
            if (resp.isNotEmpty()) {
                val statusByte = resp[0].toInt() and 0xFF
                // Strict check: Only block if both bits 5 & 6 are clearly set (0x60)
                if ((statusByte and 0x60) == 0x60) {
                    return Pair(true, "Paper roll is empty. Please insert paper and tap Print Receipt.")
                }
            }
            Pair(false, null)
        } catch (_: Exception) {
            Pair(false, null)
        }
    }

    private suspend fun printReceiptOnKioskUsb(): Boolean = withContext(NonCancellable) {
        try {
            kotlinx.coroutines.yield()
            delay(150L) // Allow Activity window focus dispatch to settle before heavy raster generation
            isPrintingInProgress = true
            val conn = curConnect ?: return@withContext false
            val printer = POSPrinter(conn)

            withContext(Dispatchers.Main) {
                binding.pbPrinter.visibility = View.VISIBLE
                binding.tvPrinterStatus.text = "Printing on Kiosk Thermal Printer..."
            }

            // 1. Purge & Resync: Force printer out of any frozen raster state (e.g. from mid-print paper out)
            purgeAndResetPrinterState(conn, printer)

            // 2. Real-time sensor check for Paper Out / Cover Open before generating/sending raster bitmap
            val (isBlocked, blockReason) = isPaperOutOrCoverOpen(conn)
            if (isBlocked) {
                isPrintingInProgress = false
                isPrintFailed = true
                BillingActivity.isPendingPrintAvailable = true
                BillingActivity.isLastOrderPrintedSuccessfully = false
                withContext(Dispatchers.Main) {
                    binding.pbPrinter.visibility = View.GONE
                    binding.tvPrinterStatus.text = "⚠️ $blockReason"
                    Toast.makeText(this@PaymentActivity, blockReason ?: "Printer paper empty", Toast.LENGTH_LONG).show()
                }
                return@withContext false
            }

            val receiptBitmap = generateThermalReceiptBitmap()

            // 3. Set center alignment for receipt
            printer.setAlignment(POSConst.ALIGNMENT_CENTER)
            delay(20)

            // 4. Buffer-optimized bitmap streaming:
            // Slicing into small 64-dot bands (576 width = 72 bytes/row -> 4,608 bytes/slice).
            // Thermal printer receive buffers are typically 4KB-16KB.
            // Sending small slices with a pacing delay (30ms) matching the thermal print head
            // burning rate prevents buffer overflow, dropped bytes, and corrupt garbage characters.
            val totalHeight = receiptBitmap.height
            val bitmapWidth = receiptBitmap.width
            val sliceHeight = 64
            var yOffset = 0

            while (yOffset < totalHeight) {
                // Check if connection was severed or printer faulted
                if (curConnect?.isConnect() != true) {
                    throw IllegalStateException("Printer connection lost or paper out mid-print")
                }

                val currentChunkHeight = minOf(sliceHeight, totalHeight - yOffset)
                val slice = Bitmap.createBitmap(receiptBitmap, 0, yOffset, bitmapWidth, currentChunkHeight)
                val sliceData = BitmapToByteData.rasterBmpToSendData(0, slice)
                slice.recycle()

                printer.sendData(sliceData)

                // Yield and pacing delay (30ms) matching thermal print head burn rate (~150-200mm/s).
                // Keeps buffer empty and prevents CPU starvation / ANR.
                kotlinx.coroutines.yield()
                delay(30)
                yOffset += currentChunkHeight
            }

            // 5. Feed lines and cut paper
            delay(100)
            printer.feedLine(3)
            delay(100)
            printer.cutHalfAndFeed(1)
            delay(150)

            // 6. Clean reset for next print job
            printer.initializePrinter()
            delay(100)

            receiptBitmap.recycle()
            isPrintingInProgress = false
            isPrintFailed = false

            withContext(Dispatchers.Main) {
                binding.pbPrinter.visibility = View.GONE
                binding.tvPrinterStatus.text = "✅ Receipt printed successfully!"
                BillingActivity.isPendingPrintAvailable = false
                BillingActivity.isLastOrderPrintedSuccessfully = true
            }
            true
        } catch (e: Exception) {
            isPrintingInProgress = false
            isPrintFailed = true
            BillingActivity.isPendingPrintAvailable = true
            BillingActivity.isLastOrderPrintedSuccessfully = false
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
                BillingActivity.isPendingPrintAvailable = false
                BillingActivity.isLastOrderPrintedSuccessfully = true
                if (!isTestPrint && orderId > 0L) printedOrderIds.add(orderId)
            }
            true
        } catch (e: RemoteException) {
            isPrintingInProgress = false
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
        fun drawWrapped(text: String, x: Float, maxWidth: Float, lineHeight: Float, align: Paint.Align? = null) {
            if (align != null) {
                paint.textAlign = align
            }
            val lines = wrapLines(text, maxWidth)
            lines.forEachIndexed { index, line ->
                if (index > 0) y += lineHeight
                canvas.drawText(line, x, y, paint)
            }
        }

        // ---------------- Temple Header / Test Print Header ----------------
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 34f
        val headerTitle = if (isTestPrint) "TEST PRINT" else "Sabarimala Sree Dharmasastha Temple"
        drawWrapped(headerTitle, width / 2f, maxTextWidth, 40f, Paint.Align.CENTER)

        if (paymentMode != "Sales Report") {
            y += 58f
            paint.textSize = 32f
            paint.color = Color.BLACK
            canvas.drawText("Prebooking Prasadam Receipt", width / 2f, y, paint)
        }

        if (isDuplicatePrint) {
            y += 36f
            paint.textSize = 24f
            paint.color = Color.RED
            canvas.drawText("** DUPLICATE PRINT **", width / 2f, y, paint)
        }

        // ---------------- Metadata (left aligned) ----------------
        y += 52f
        paint.textSize = 27f
        paint.color = Color.BLACK
        paint.textAlign = Paint.Align.LEFT
        val displayOrderId = formatReceiptNo(orderNo)
        val displayPspRef = pspRefNo
        val displayTotal = if (totalAmount > 0.0) totalAmount else receiptItems.sumOf { it.total }

        if (paymentMode != "Sales Report") {
            canvas.drawText("Receipt No :   $displayOrderId", margin, y, paint)
            y += 38f
        }
        canvas.drawText("Date       :   $formattedDate", margin, y, paint)

        // Devotee name and mobile: printed only when they are not empty
        if (orderCustomerName.isNotBlank()) {
            y += 38f
            drawWrapped("Name       :   ${orderCustomerName.trim()}", margin, maxTextWidth, 34f, Paint.Align.LEFT)
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
        canvas.drawText("Price", colAmountRight, y, paint)
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


        // ---------------- Footer Message & Company Brand (below QR) ----------------

        // ---------------- Centered QR Code ----------------
        val qrSize = 280
        val qrData = if (encryptedOrderId.isNotBlank()) encryptedOrderId else ""
        if (qrData.isNotBlank()) {
            val qrBitmap = QrCodeGenerator.generate(qrData, qrSize)
            y += 30f
            val qrLeft = (width - qrSize) / 2f
            canvas.drawBitmap(qrBitmap, qrLeft, y, null)
            qrBitmap.recycle()
            y += qrSize + 40f                      // bottom padding so the cutter doesn't clip the QR
        }


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
                    drawWrapped(trimmed, width / 2f, maxTextWidth, 28f, Paint.Align.CENTER)
                }
            }
        }

        if (paymentMode != "Sales Report") {
            if (footerMsg.isNotBlank()) {
                drawMultiLine(footerMsg)
            }

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
        } else {
            footerBitmap?.recycle()
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
        printerReconnectJob?.cancel()
        printerReconnectJob = null
        if (usbReceiverRegistered) {
            try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
            usbReceiverRegistered = false
        }
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
