package com.xeniaone.templeapp

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.view.MotionEvent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.google.gson.Gson
import com.xeniaone.templeapp.adapter.ProductCardAdapter
import com.xeniaone.templeapp.model.PrasadamItem
import com.xeniaone.templeapp.model.ReceiptItem
import com.xeniaone.templeapp.network.model.OrderCartItem
import com.xeniaone.templeapp.network.model.OrderKioskRequest
import com.xeniaone.templeapp.network.model.SocketProduct
import com.xeniaone.templeapp.network.socket.CatalogueSocketManager
import com.xeniaone.templeapp.util.QrCodeGenerator
import android.graphics.BitmapFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import com.xeniaone.templeapp.pinelabs.PineLabsConstants
import com.xeniaone.templeapp.pinelabs.PineLabsManager
import com.xeniaone.templeapp.repository.OrderRepository
import com.xeniaone.templeapp.repository.Resource
import com.xeniaone.templeapp.util.AuthManager
import com.xeniaone.templeapp.util.BillingDialogHelper
import com.xeniaone.templeapp.util.CommonMethod
import com.xeniaone.templeapp.util.LanguageStrings
import com.xeniaone.templeapp.databinding.ActivityBillingBinding
import com.xeniaone.templeapp.databinding.ItemBillRowBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class BillingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LANGUAGE_CODE = "extra_language_code"
        const val EXTRA_PRINT_PENDING = "extra_print_pending"
        private const val TAG = "BillingActivity"
        var lastOrderIntent: Intent? = null
        var isPendingPrintAvailable: Boolean = false
        var isLastOrderPrintedSuccessfully: Boolean = false
    }

    private lateinit var binding: ActivityBillingBinding
    private lateinit var authManager: AuthManager
    private lateinit var socketManager: CatalogueSocketManager
    private lateinit var orderRepository: OrderRepository
    private lateinit var productAdapter: ProductCardAdapter
    private var currentLanguage = "en"

    private val allItems = mutableListOf<PrasadamItem>()

    // Pine Labs & Razorpay State
    private var pendingOrderId: Long = 0L
    private var pendingTotal: Double = 0.0
    private var pendingReceiptItemsJson: String = ""
    private var pendingPaymentMode: String = "Pine Labs Card"
    private var pendingRazorpayOrderId: String = ""
    private var pendingCustomerName: String = ""
    private var pendingCustomerMobile: String = ""
    private var qrPollingJob: kotlinx.coroutines.Job? = null
    private var qrCountDownTimer: CountDownTimer? = null
    private var qrDialog: AlertDialog? = null
    private var reprintAuthDialog: AlertDialog? = null
    private var drawerNavAuthDialog: AlertDialog? = null
    private var activeMobileKeypadDialog: AlertDialog? = null
    private var isQrPaymentHandled: Boolean = false
    private var isPrintPending: Boolean = false
    private var isPaymentProcessing: Boolean = false
    private var isSettlingOrder: Boolean = false
    private var isNavigatingToReceipt: Boolean = false
    private var lastPayClickTime: Long = 0L



    private var cachedDrawerLayout: androidx.drawerlayout.widget.DrawerLayout? = null
    private fun getDrawerLayout(): androidx.drawerlayout.widget.DrawerLayout? {
        if (cachedDrawerLayout == null) {
            cachedDrawerLayout = findViewById(R.id.drawerLayout)
        }
        return cachedDrawerLayout
    }

    private val pineLabsPaymentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        CommonMethod.dismissLoader()
        handlePineLabsPaymentResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityBillingBinding.inflate(layoutInflater)
        setContentView(binding.root)


        val base = resources.getDimensionPixelSize(R.dimen.bottom_bar_padding_bottom)
        val minClear = resources.getDimensionPixelSize(R.dimen.bottom_bar_min_clearance)

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootBilling) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = maxOf(systemBars.bottom, imeInsets.bottom)
            binding.rootBilling.setPadding(systemBars.left, 0, systemBars.right, 0)
            binding.bottomBar.updatePadding(bottom = base + maxOf(bottomInset, minClear))

            if (imeInsets.bottom > 0) {
                binding.scrollViewContent.post {
                    binding.scrollViewContent.smoothScrollTo(0, binding.scrollViewContent.bottom)
                }
            }
            insets
        }

        val app = application as TempleApp
        authManager = app.container.authManager
        socketManager = app.container.catalogueSocketManager
        orderRepository = app.container.orderRepository
        val authRepo = app.container.authRepository

        binding.etDevoteeMobile.showSoftInputOnFocus = false

        currentLanguage = intent.getStringExtra(EXTRA_LANGUAGE_CODE) ?: authManager.selectedLanguage

        detectAndDisplayPineLabsDevice()

        val kioskPrefs = getSharedPreferences("kiosk_settings", MODE_PRIVATE)
        val isKioskOnStartup = kioskPrefs.getBoolean("pref_kiosk_mode_enabled", false)
        updateHomeLauncherAliasState(isKioskOnStartup)
        if (isKioskOnStartup && !PineLabsManager.isPineLabsAvailable(this)) {
            binding.root.postDelayed({
                try {
                    startLockTask()
                } catch (_: Exception) {}
            }, 800L)
        }

        binding.btnBackToLanguage.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Exit to Login")
                .setMessage("Do you want to log out of the Kiosk?")
                .setPositiveButton("Logout") { _, _ ->
                    authManager.logout()
                    val intent = Intent(this, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Pay button listener with double-tap debouncer
        binding.btnPay.setOnClickListener {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastPayClickTime < 1000L) {
                return@setOnClickListener
            }
            lastPayClickTime = now
            proceedToPayment()
        }

        // Manual print button listener (always shown; reprints last completed order)
        binding.btnManualPrint?.visibility = View.VISIBLE
        binding.btnManualPrint?.setOnClickListener {
            val lastIntent = lastOrderIntent
            if (lastIntent != null) {
                val reprintIntent = Intent(lastIntent).apply {
                    putExtra(PaymentActivity.EXTRA_IS_DUPLICATE_PRINT, true)
                }
                startActivity(reprintIntent)
            } else {
                Toast.makeText(this, "No previous order to print", Toast.LENGTH_SHORT).show()
            }
        }

        // Swipe down for refresh listener
        binding.swipeRefreshLayout.setOnRefreshListener {
            lifecycleScope.launch {
                val companyId = authManager.companyId
                val branchId = authManager.defaultBranch
                socketManager.connect(companyId, branchId)
                delay(1000)
                binding.swipeRefreshLayout.isRefreshing = false
                Toast.makeText(this@BillingActivity, "Refreshed successfully", Toast.LENGTH_SHORT).show()
            }
        }


        binding.layoutPineLabsStatus.setOnClickListener {
            val activeItems = allItems.filter { it.quantity > 0 }
            val total = activeItems.sumOf { it.totalAmount }
            if (activeItems.isNotEmpty() && total > 0.0) {
                showPaymentOptionDialog(activeItems, total)
            } else {
                showPineLabsTerminalInfoDialog()
            }
        }

        var lastTvBottom1ClickTime = 0L
        binding.tvBottom1?.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTvBottom1ClickTime < 500L) {
                if (!PineLabsManager.isPineLabsAvailable(this)) {
                    showKioskReprintAuthDialog()
                }
            }
            lastTvBottom1ClickTime = now
        }

        binding.btnTestPrint?.setOnClickListener {
            val intent = Intent(this, PaymentActivity::class.java).apply {
                putExtra(PaymentActivity.EXTRA_IS_TEST_PRINT, true)
                putExtra(PaymentActivity.EXTRA_TOTAL_AMOUNT, 0.0)
            }
            startActivity(intent)
        }

        applyLanguageStrings()
        setupProductRecyclerView()
        setupNavigationDrawer()
        updateDevoteeMobileHint()
        binding.etDevoteeMobile.setTypeface(null, android.graphics.Typeface.BOLD)
        binding.etDevoteeMobile.showSoftInputOnFocus = false
        binding.etDevoteeMobile.isFocusable = false
        binding.etDevoteeMobile.isFocusableInTouchMode = false

        val openKeypadListener = View.OnClickListener {
            showMobileKeypadDialog()
        }
        binding.etDevoteeMobile.setOnClickListener(openKeypadListener)
        binding.tilDevoteeMobile.setOnClickListener(openKeypadListener)

        if (authManager.employeeImage.isNotBlank()) {
            loadEmployeeBannerImage(authManager.employeeImage)
        }

        updateUi()

        showProductsLoader()

        lifecycleScope.launch {
            if (authManager.authToken.isNotBlank()) {
                try {
                    val profileRes = authRepo.getEmployeeProfile(authManager.authToken)
                    if (profileRes is Resource.Success && profileRes.data.data != null) {
                        val d = profileRes.data.data
                        authManager.saveProfile(
                            companyId = d.companyId,
                            branchId = d.defaultBranch,
                            userId = d.userId,
                            userName = d.userName,
                            designation = d.designation,
                            imageUrl = d.image
                        )
                        authManager.footerMessage = profileRes.data.footerMessage ?: ""
                        authManager.companyBrand = profileRes.data.companyBrand ?: ""
                        if (!d.image.isNullOrBlank()) {
                            runOnUiThread {
                                loadEmployeeBannerImage(d.image)
                            }
                        }
                    }
                } catch (e: Exception) {
                }
            }

            // 2. Connect SignalR Socket using companyId & defaultBranch
            val companyId = authManager.companyId
            val branchId = authManager.defaultBranch
            socketManager.connect(companyId, branchId)
        }

        // 3. Observe dynamic products received from Socket
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                socketManager.productsFlow.collect { socketProducts ->
                    if (socketProducts.isNotEmpty()) {
                        hideProductsLoader()
                        bindSocketProducts(socketProducts)
                    }
                }
            }
        }

        // Timeout safety: if socket doesn't return products within 5 seconds, fallback to defaults
        lifecycleScope.launch {
            delay(5000)
            if (allItems.isEmpty()) {
                hideProductsLoader()
                loadDefaultPrasadamItems()
            }
        }

    }

    private fun updateDevoteeMobileHint() {
        val hintText = if (isWhatsAppEnabled()) {
            "Enter Your Mobile Number To Receive Receipt In WhatsApp"
        } else {
            "Enter Mobile Number"
        }
//        binding.tvDevoteeMobileNotice?.text = hintText
        val spannableHint = android.text.SpannableString(hintText)
        spannableHint.setSpan(
            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            0,
            hintText.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.tilDevoteeMobile.hint = spannableHint
    }

    private fun updateProductGridSpan() {
        val smallestWidth = resources.configuration.smallestScreenWidthDp
        val spanCount = when {
            allItems.size == 1 -> 1
            allItems.size == 2 -> 2
            smallestWidth >= 700 -> 3
            else -> 2
        }
        (binding.rvProducts.layoutManager as? GridLayoutManager)?.spanCount = spanCount
    }

    private fun setupProductRecyclerView() {
        productAdapter = ProductCardAdapter(allItems) { _, _ ->
            updateUi()
        }
        val smallestWidth = resources.configuration.smallestScreenWidthDp
        val spanCount = when {
            allItems.size == 1 -> 1
            allItems.size == 2 -> 2
            smallestWidth >= 700 -> 3
            else -> 2
        }
        binding.rvProducts.layoutManager = GridLayoutManager(this, spanCount)
        binding.rvProducts.adapter = productAdapter
    }


    private fun showProductsLoader() {
        binding.layoutLoadingProducts.visibility = View.VISIBLE
        binding.rvProducts.visibility = View.GONE
        CommonMethod.showLoader(this, "Loading products...")
    }

    private fun hideProductsLoader() {
        binding.layoutLoadingProducts.visibility = View.GONE
        binding.rvProducts.visibility = View.VISIBLE
        CommonMethod.dismissLoader()
    }

    private fun loadDefaultPrasadamItems() {
        if (allItems.isNotEmpty()) return

        // Standard Prasadam items with ZERO dummy quantity and default maxQtyPerOrder = 5 for testing
        allItems.clear()
        allItems.add(PrasadamItem("9543", LanguageStrings.getItemAppam(currentLanguage), 45.00, R.drawable.item_appam, null, 0, 5))
        allItems.add(PrasadamItem("9541", LanguageStrings.getItemAravana(currentLanguage), 100.00, R.drawable.item_aravana, null, 0, 5))
        allItems.add(PrasadamItem("9546", LanguageStrings.getItemCombo(currentLanguage), 125.00, R.drawable.item_combo, null, 0, 5))
        updateProductGridSpan()
        productAdapter.updateItems(allItems)
        updateUi()
    }

    private fun bindSocketProducts(socketProducts: List<SocketProduct>) {
        runOnUiThread {
            val existingQuantities = allItems.associate { it.name.lowercase() to it.quantity }

            val newItems = mutableListOf<PrasadamItem>()
            for (p in socketProducts) {
                val nameLower = p.productName.lowercase()
                val fallbackRes = when {
                    nameLower.contains("appam") && !nameLower.contains("combo") -> R.drawable.item_appam
                    nameLower.contains("aravana") && !nameLower.contains("combo") -> R.drawable.item_aravana
                    nameLower.contains("combo") -> R.drawable.item_combo
                    else -> R.drawable.item_appam
                }

                val currentQty = existingQuantities[nameLower] ?: 0
                val unitPrice = if (p.effectivePrice > 0.0) p.effectivePrice else 40.00

                newItems.add(
                    PrasadamItem(
                        id = p.productId.toString(),
                        name = p.productName,
                        unitPrice = unitPrice,
                        imageResId = fallbackRes,
                        imageUrl = p.image,
                        quantity = currentQty, // Starts at 0
                        maxQtyPerOrder = p.maxQtyPerOrder,
                        stock = p.stock
                    )
                )
            }

            allItems.clear()
            allItems.addAll(newItems)
            updateProductGridSpan()
            productAdapter.updateItems(allItems)
            updateUi()
        }
    }

    private fun applyLanguageStrings() {
        binding.tvSelectYourItem.text = LanguageStrings.getSelectYourItem(currentLanguage)
        binding.tvColItemName.text = LanguageStrings.getColItemName(currentLanguage)
        binding.tvColQty.text = LanguageStrings.getColQty(currentLanguage)
        binding.tvColAmount.text = LanguageStrings.getColAmount(currentLanguage)
        binding.tvColTotal.text = LanguageStrings.getColTotal(currentLanguage)
        binding.tvTotalAmountLabel.text = LanguageStrings.getTotalAmountLabel(currentLanguage)
    }

    @SuppressLint("SetTextI18n")
    private fun updateUi() {
        binding.llOrderItemsContainer.removeAllViews()

        val activeItems = allItems.filter { it.quantity > 0 }

        if (activeItems.isEmpty()) {
            binding.tableCard.visibility = View.GONE
            binding.tvEmptyBillState.visibility = View.VISIBLE
            binding.tvDeliveryCounterNotice?.visibility = View.VISIBLE
        } else {
            binding.tableCard.visibility = View.VISIBLE
            binding.tvEmptyBillState.visibility = View.GONE
            binding.tvDeliveryCounterNotice?.visibility = View.GONE
            val inflater = LayoutInflater.from(this)

            activeItems.forEachIndexed { index, item ->
                val rowBinding = ItemBillRowBinding.inflate(inflater, binding.llOrderItemsContainer, false)
                rowBinding.tvRowIndex.text = "%02d".format(index + 1)
                rowBinding.tvRowItemName.text = item.name
                rowBinding.tvRowQty.text = item.quantity.toString()
                rowBinding.tvRowPrice.text = "%.2f".format(item.unitPrice)
                rowBinding.tvRowTotal.text = item.formattedTotal
                rowBinding.btnRemoveRow.setOnClickListener {
                    item.quantity = 0
                    productAdapter.notifyDataSetChanged()
                    updateUi()
                }

                binding.llOrderItemsContainer.addView(rowBinding.root)
            }
        }

        val totalAmount = allItems.sumOf { it.totalAmount }
        binding.tvTotalAmountValue.text = "₹%.2f".format(totalAmount)
        binding.btnPay.isEnabled = true
        binding.btnPay.alpha = if (activeItems.isNotEmpty()) 1.0f else 0.7f
    }

    override fun onResume() {
        super.onResume()
        isPaymentProcessing = false
        isSettlingOrder = false
        isNavigatingToReceipt = false
        detectAndDisplayPineLabsDevice()
        updateDevoteeMobileHint()
        val kioskPrefs = getSharedPreferences("kiosk_settings", MODE_PRIVATE)
        val isKioskModeOn = kioskPrefs.getBoolean("pref_kiosk_mode_enabled", false)
        if (isKioskModeOn && !PineLabsManager.isPineLabsAvailable(this)) {
            try {
                val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
                val isPinned = am.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE
                if (!isPinned) {
                    startLockTask()
                }
            } catch (e: Exception) {
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("pendingOrderId", pendingOrderId)
        outState.putDouble("pendingTotal", pendingTotal)
        outState.putString("pendingReceiptItemsJson", pendingReceiptItemsJson)
        outState.putString("pendingPaymentMode", pendingPaymentMode)
        outState.putString("pendingRazorpayOrderId", pendingRazorpayOrderId)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (pendingOrderId <= 0L) {
            pendingOrderId = savedInstanceState.getLong("pendingOrderId", 0L)
        }
        if (pendingTotal <= 0.0) {
            pendingTotal = savedInstanceState.getDouble("pendingTotal", 0.0)
        }
        if (pendingReceiptItemsJson.isBlank()) {
            pendingReceiptItemsJson = savedInstanceState.getString("pendingReceiptItemsJson") ?: ""
        }
        if (pendingPaymentMode.isBlank()) {
            pendingPaymentMode = savedInstanceState.getString("pendingPaymentMode") ?: ""
        }
        if (pendingRazorpayOrderId.isBlank()) {
            pendingRazorpayOrderId = savedInstanceState.getString("pendingRazorpayOrderId") ?: ""
        }
    }

    private fun detectAndDisplayPineLabsDevice() {
        val isPineLabs = PineLabsManager.isPineLabsAvailable(this)
        if (isPineLabs) {
            binding.layoutPineLabsStatus.visibility = View.VISIBLE
            binding.btnManualPrint?.visibility = View.VISIBLE
            binding.tvPineLabsBadge.text = "⚡ Pine Labs POS Ready"
        } else {
            binding.layoutPineLabsStatus.visibility = View.GONE
        }
    }

    private fun proceedToPayment() {
        if (isPaymentProcessing) {
            return
        }
        if (!validateMobileNumberForWhatsApp()) return
        val activeItems = allItems.filter { it.quantity > 0 }
        val total = activeItems.sumOf { it.totalAmount }
        if (total <= 0.0 || activeItems.isEmpty()) {
            val emptyMsg = LanguageStrings.getCartEmptyMessage(currentLanguage)
            Toast.makeText(this, emptyMsg, Toast.LENGTH_LONG).show()
            return
        }

        if (PineLabsManager.isPineLabsAvailable(this)) {
            showPaymentOptionDialog(activeItems, total)
        } else {
            proceedWithRazorpayPayment(activeItems, total)
        }
    }

    private fun showPaymentOptionDialog(activeItems: List<PrasadamItem>, total: Double) {
        BillingDialogHelper.showPaymentOptionDialog(
            activity = this,
            total = total,
            onCardClick = {
                initiatePineLabsPayment(
                    activeItems = activeItems,
                    total = total,
                    txnType = PineLabsConstants.TXN_TYPE_SALE,
                    modeName = "Pine Labs Card"
                )
            },
            onUpiClick = {
                initiatePineLabsPayment(
                    activeItems = activeItems,
                    total = total,
                    txnType = PineLabsConstants.TXN_TYPE_UPI_SALE,
                    modeName = "Pine Labs UPI"
                )
            },
            onCashClick = {
                showCashReferenceDialog(activeItems)
            }
        )
    }

    private fun showPineLabsTerminalInfoDialog() {
        BillingDialogHelper.showPineLabsTerminalInfoDialog(this, authManager)
    }

    private fun showReportsDialog() {
        if (isFinishing || isDestroyed) return
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_reports, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val etReportFromDate = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etReportFromDate)
        val etReportToDate = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etReportToDate)
        val layoutReportItemsContainer = dialogView.findViewById<android.widget.LinearLayout>(R.id.layoutReportItemsContainer)
        val tvReportTotalAmount = dialogView.findViewById<TextView>(R.id.tvReportTotalAmount)
        val btnCancelReport = dialogView.findViewById<View>(R.id.btnCancelReport)
        val btnPrintReport = dialogView.findViewById<View>(R.id.btnPrintReport)

        val apiDateTimeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val displayDateTimeFormat = SimpleDateFormat("dd-MMM-yyyy HH:mm:ss", Locale.getDefault())

        val calendarFrom = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
        }
        val calendarTo = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 23)
            set(java.util.Calendar.MINUTE, 59)
            set(java.util.Calendar.SECOND, 59)
        }

        var selectedFromDateTime = apiDateTimeFormat.format(calendarFrom.time)
        var selectedToDateTime = apiDateTimeFormat.format(calendarTo.time)

        etReportFromDate?.setText(displayDateTimeFormat.format(calendarFrom.time))
        etReportToDate?.setText(displayDateTimeFormat.format(calendarTo.time))

        var currentReportTotal = 0.0
        val reportReceiptItems = mutableListOf<ReceiptItem>()

        val fetchAndDisplayReport = {
            layoutReportItemsContainer?.removeAllViews()
            tvReportTotalAmount?.text = "Loading..."

            val startDateParam = selectedFromDateTime
            val endDateParam = selectedToDateTime

            lifecycleScope.launch {
                try {
                    val app = application as TempleApp
                    val result = app.container.orderRepository.getSaleReport(startDateParam, endDateParam)
                    val jsonObj = result.getOrNull()

                    if (result.isSuccess && jsonObj != null) {
                        layoutReportItemsContainer?.removeAllViews()
                        reportReceiptItems.clear()

                        val dataObj = jsonObj.getAsJsonObject("Data")
                        val ordersArray = dataObj?.getAsJsonArray("Data")
                            ?: jsonObj.getAsJsonArray("Data")
                            ?: jsonObj.getAsJsonArray("items")

                        val itemSummaryMap = mutableMapOf<String, Pair<Double, Double>>() // Name -> Pair(Qty, Amount)
                        var grandTotal = 0.0

                        if (ordersArray != null && ordersArray.size() > 0) {
                            for (orderElement in ordersArray) {
                                val orderObj = orderElement.asJsonObject
                                val itemsArray = orderObj.getAsJsonArray("Items")
                                    ?: orderObj.getAsJsonArray("items")

                                if (itemsArray != null) {
                                    for (itemElement in itemsArray) {
                                        val itemObj = itemElement.asJsonObject
                                        val itemName = itemObj.get("ItemName")?.asString
                                            ?: itemObj.get("itemName")?.asString
                                            ?: itemObj.get("Name")?.asString
                                            ?: "Item"

                                        val qty = itemObj.get("Quantity")?.asDouble
                                            ?: itemObj.get("quantity")?.asDouble
                                            ?: 1.0

                                        val amount = itemObj.get("Amount")?.asDouble
                                            ?: itemObj.get("amount")?.asDouble
                                            ?: 0.0

                                        val existing = itemSummaryMap[itemName] ?: Pair(0.0, 0.0)
                                        itemSummaryMap[itemName] = Pair(existing.first + qty, existing.second + amount)
                                    }
                                }
                            }
                        }

                        var itemCount = 0
                        var index = 0
                        for ((name, pair) in itemSummaryMap) {
                            itemCount++
                            index++
                            val (qty, amount) = pair
                            grandTotal += amount

                            reportReceiptItems.add(
                                ReceiptItem(
                                    no = index,
                                    name = name,
                                    quantity = qty.toInt(),
                                    rate = if (qty > 0) amount / qty else amount,
                                    total = amount
                                )
                            )

                            val rowView = LayoutInflater.from(this@BillingActivity).inflate(R.layout.item_report_row, layoutReportItemsContainer, false)
                            val tvReportItemName = rowView.findViewById<TextView>(R.id.tvReportItemName)
                            val tvReportItemQty = rowView.findViewById<TextView>(R.id.tvReportItemQty)
                            val tvReportItemAmount = rowView.findViewById<TextView>(R.id.tvReportItemAmount)

                            tvReportItemName?.text = name
                            tvReportItemQty?.text = qty.toInt().toString()
                            tvReportItemAmount?.text = String.format(Locale.getDefault(), "₹%.2f", amount)

                            layoutReportItemsContainer?.addView(rowView)
                        }

                        val apiTotal = dataObj?.get("TotalAmount")?.asDouble
                            ?: jsonObj.get("TotalAmount")?.asDouble
                            ?: grandTotal

                        currentReportTotal = apiTotal
                        tvReportTotalAmount?.text = String.format(Locale.getDefault(), "₹%.2f", apiTotal)

                        if (itemCount == 0) {
                            val emptyView = TextView(this@BillingActivity).apply {
                                text = "No sales records found for selected date & time range."
                                setPadding(16, 32, 16, 32)
                                gravity = android.view.Gravity.CENTER
                                setTextColor(resources.getColor(R.color.text_muted, theme))
                                textSize = 14f
                            }
                            layoutReportItemsContainer?.addView(emptyView)
                        }
                    } else {
                        tvReportTotalAmount?.text = "₹0.00"
                        val errorView = TextView(this@BillingActivity).apply {
                            text = "Failed to load report: ${result.exceptionOrNull()?.message ?: "Unknown error"}"
                            setPadding(16, 32, 16, 32)
                            gravity = android.view.Gravity.CENTER
                            setTextColor(resources.getColor(R.color.delete_red, theme))
                            textSize = 13f
                        }
                        layoutReportItemsContainer?.addView(errorView)
                    }
                } catch (e: Exception) {
                    tvReportTotalAmount?.text = "₹0.00"
                    val errorView = TextView(this@BillingActivity).apply {
                        text = "Error: ${e.message}"
                        setPadding(16, 32, 16, 32)
                        gravity = android.view.Gravity.CENTER
                        setTextColor(resources.getColor(R.color.delete_red, theme))
                        textSize = 13f
                    }
                    layoutReportItemsContainer?.addView(errorView)
                }
            }
        }

        val dateTimePickerListener = View.OnClickListener { v ->
            val et = v as? com.google.android.material.textfield.TextInputEditText
            val isFrom = (et == etReportFromDate)
            val targetCalendar = if (isFrom) calendarFrom else calendarTo

            android.app.DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    targetCalendar.set(java.util.Calendar.YEAR, year)
                    targetCalendar.set(java.util.Calendar.MONTH, month)
                    targetCalendar.set(java.util.Calendar.DAY_OF_MONTH, dayOfMonth)

                    android.app.TimePickerDialog(
                        this,
                        { _, hourOfDay, minute ->
                            targetCalendar.set(java.util.Calendar.HOUR_OF_DAY, hourOfDay)
                            targetCalendar.set(java.util.Calendar.MINUTE, minute)
                            targetCalendar.set(java.util.Calendar.SECOND, 0)

                            val displayStr = displayDateTimeFormat.format(targetCalendar.time)
                            val apiStr = apiDateTimeFormat.format(targetCalendar.time)
                            et?.setText(displayStr)

                            if (isFrom) {
                                selectedFromDateTime = apiStr
                            } else {
                                selectedToDateTime = apiStr
                            }
                            fetchAndDisplayReport()
                        },
                        targetCalendar.get(java.util.Calendar.HOUR_OF_DAY),
                        targetCalendar.get(java.util.Calendar.MINUTE),
                        true
                    ).show()
                },
                targetCalendar.get(java.util.Calendar.YEAR),
                targetCalendar.get(java.util.Calendar.MONTH),
                targetCalendar.get(java.util.Calendar.DAY_OF_MONTH)
            ).show()
        }

        etReportFromDate?.setOnClickListener(dateTimePickerListener)
        etReportToDate?.setOnClickListener(dateTimePickerListener)

        fetchAndDisplayReport()

        btnCancelReport?.setOnClickListener {
            dialog.dismiss()
        }

        btnPrintReport?.setOnClickListener {
            dialog.dismiss()
            if (PineLabsManager.isPineLabsAvailable(this)) {
                Toast.makeText(this, "Printing Sales Report on Pine Labs...", Toast.LENGTH_SHORT).show()
                try {
                    val currentUserName = authManager.employeeName.ifBlank { authManager.savedUsername }
                    val footerBitmap = try {
                        android.graphics.BitmapFactory.decodeResource(resources, R.drawable.footer)
                    } catch (_: Exception) {
                        null
                    }
                    val printItems = PineLabsManager.buildTempleReceiptPrintItems(
                        orderId = "REPORT",
                        orderNo = "REPORT",
                        dateFormatted = SimpleDateFormat("dd-MMM-yyyy  hh:mm a", Locale.ENGLISH).format(Date()),
                        paymentRef = "SALES-REPORT",
                        paymentModeTitle = "Sales Report",
                        items = reportReceiptItems,
                        totalAmount = currentReportTotal,
                        qrData = "",
                        customerName = "",
                        mobileNumber = "",
                        userName = currentUserName,
                        printerWidth = PineLabsConstants.PRINTER_WIDTH_32,
                        imageBitmap = footerBitmap,
                        footerMessage = authManager.footerMessage,
                        companyBrand = authManager.companyBrand
                    )
                    val uniqueReportRef = "RPT_${System.currentTimeMillis() % 100000}"
                    val printIntent = PineLabsManager.createPrintIntent(
                        context = this,
                        printRefNo = uniqueReportRef,
                        printItems = printItems,
                        applicationId = authManager.pineLabsApplicationId,
                        userId = authManager.pineLabsUserId
                    )
                    footerBitmap?.recycle()
                    startActivity(printIntent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Print error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Printing Sales Report...", Toast.LENGTH_SHORT).show()
                val itemsJson = Gson().toJson(reportReceiptItems)
                val intent = Intent(this, PaymentActivity::class.java).apply {
                    putExtra(PaymentActivity.EXTRA_IS_TEST_PRINT, true)
                    putExtra(PaymentActivity.EXTRA_TOTAL_AMOUNT, currentReportTotal)
                    putExtra(PaymentActivity.EXTRA_RECEIPT_ITEMS_JSON, itemsJson)
                    putExtra(PaymentActivity.EXTRA_PAYMENT_MODE, "Sales Report")
                }
                startActivity(intent)
            }
        }

        dialog.show()
    }


    private fun initiatePineLabsPayment(
        activeItems: List<PrasadamItem>,
        total: Double,
        txnType: Long,
        modeName: String
    ) {
        if (isPaymentProcessing) {
            return
        }
        isPaymentProcessing = true

        if (!validateMobileNumberForWhatsApp()) {
            isPaymentProcessing = false
            return
        }
        val cartItems = activeItems.map { item ->
            OrderCartItem(
                productId = item.id.toIntOrNull() ?: 0,
                variantId = 0,
                quantity = item.quantity,
                rate = item.unitPrice
            )
        }

        val receiptItems = activeItems.mapIndexed { index, item ->
            ReceiptItem(
                no = index + 1,
                name = item.name,
                quantity = item.quantity,
                rate = item.unitPrice,
                total = item.totalAmount
            )
        }
        pendingReceiptItemsJson = Gson().toJson(receiptItems)
        pendingTotal = total
        pendingPaymentMode = modeName

        CommonMethod.showLoader(this, "Connecting to Pine Labs POS...")

        lifecycleScope.launch {
            try {
                val companyId = authManager.companyId
                val branchId = authManager.defaultBranch
                val userId = authManager.userId

                val paymentProviderId = 15

                val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("Asia/Kolkata")
                }.format(Date())

                val currentOrderChannel = if (PineLabsManager.isPineLabsAvailable(this@BillingActivity)) "PINELABS" else "KIOSK"

                val orderRequest = OrderKioskRequest(
                    addressId = 0,
                    deliveryTypeId = 0,
                    deliverySlotId = 0,
                    expectedDeliveryDate = nowIso,
                    remark = "$modeName Transaction",
                    orderChanel = currentOrderChannel,
                    tableId = 0,
                    dinningId = 0,
                    paymentProviderId = paymentProviderId,
                    couponCode = "",
                    isWalletAmount = false,
                    isWalletLoyality = false,
                    cartItems = cartItems
                )

                val orderResult = orderRepository.createKioskOrder(
                    companyId = companyId,
                    branchId = branchId,
                    customerId = 0,
                    userId = userId,
                    request = orderRequest
                )

                CommonMethod.dismissLoader()

                val orderResponse = orderResult.getOrNull()
                if (orderResult.isFailure || orderResponse == null || !orderResponse.status.equals("Success", ignoreCase = true) || (orderResponse.orderId ?: 0) <= 0) {
                    isPaymentProcessing = false
                    val err = orderResponse?.message ?: orderResult.exceptionOrNull()?.message ?: "Pine Labs order summary failed"
                    showModernMessageDialog("Order Summary Failed", err)
                    return@launch
                }
                val orderId = orderResponse.orderId!!.toLong()
                pendingOrderId = orderId

                val pineIntent = PineLabsManager.createTransactionIntent(
                    context = this@BillingActivity,
                    billingRefNo = orderId.toString(),
                    amountRupees = total,
                    transactionType = txnType,
                    applicationId = authManager.pineLabsApplicationId,
                    userId = authManager.pineLabsUserId
                )

                pineLabsPaymentLauncher.launch(pineIntent)
            } catch (e: Exception) {
                isPaymentProcessing = false
                CommonMethod.dismissLoader()
                Toast.makeText(this@BillingActivity, "Pine Labs error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun handlePineLabsPaymentResult(resultCode: Int, data: Intent?) {
        isPaymentProcessing = false
        val response = PineLabsManager.parseTransactionResponse(data)
        if (response != null && response.isApproved) {
            val detail = response.detail
            val approvalCode = detail?.approvalCode.orEmpty()
            val rrn = detail?.rrn.orEmpty()
            val cardNumber = detail?.cardNumber.orEmpty()
            val cardType = detail?.cardType.orEmpty()
            val invoiceNo = detail?.invoiceNumber ?: 0L
            val pspRef = if (rrn.isNotBlank()) rrn else if (approvalCode.isNotBlank()) approvalCode else detail?.plutusTransactionLogId.orEmpty()

            // Safe recovery: if pendingOrderId was lost due to process death, restore from billingRefNo
            val refOrderId = detail?.billingRefNo?.toLongOrNull() ?: 0L
            if (pendingOrderId <= 0L && refOrderId > 0L) {
                pendingOrderId = refOrderId
            }
            // Safe recovery: if pendingTotal was lost, restore from authAmountPaise (converted from paise)
            val amountFromPaise = detail?.authAmountPaise?.toDoubleOrNull()?.let { it / 100.0 } ?: 0.0
            if (pendingTotal <= 0.0 && amountFromPaise > 0.0) {
                pendingTotal = amountFromPaise
            }
            // Safe recovery: if pendingReceiptItemsJson was lost, restore from active cart items
            if (pendingReceiptItemsJson.isBlank()) {
                val activeItems = allItems.filter { it.quantity > 0 }
                if (activeItems.isNotEmpty()) {
                    val receiptItems = activeItems.mapIndexed { index, item ->
                        ReceiptItem(
                            no = index + 1,
                            name = item.name,
                            quantity = item.quantity,
                            rate = item.unitPrice,
                            total = item.totalAmount
                        )
                    }
                    pendingReceiptItemsJson = Gson().toJson(receiptItems)
                    if (pendingTotal <= 0.0) {
                        pendingTotal = activeItems.sumOf { it.totalAmount }
                    }
                }
            }

            Toast.makeText(this, "✅ Payment Approved! (Auth: $approvalCode)", Toast.LENGTH_SHORT).show()

            processAndNavigatePineLabsOrder(
                pspRef = pspRef,
                approvalCode = approvalCode,
                cardNumber = cardNumber,
                cardType = cardType,
                invoiceNo = invoiceNo,
                rrn = rrn
            )
        } else if (response != null && response.isUpiPending) {
            Toast.makeText(this, "UPI Transaction initiated on terminal. Complete on device.", Toast.LENGTH_LONG).show()
        } else {
            val errorMsg = response?.response?.responseMsg
                ?: response?.detail?.remark
                ?: "Payment was cancelled or could not be completed on Pine Labs terminal."
            showModernMessageDialog("Transaction Not Completed", errorMsg)
        }
    }

    private fun showModernMessageDialog(title: String, message: String) {
        if (isFinishing || isDestroyed) return
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_modern_alert, null)
        val dialog = AlertDialog.Builder(this)
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

    private fun showPlaceOrderRetryDialog(
        pspRef: String,
        approvalCode: String,
        cardNumber: String,
        cardType: String,
        invoiceNo: Long,
        rrn: String,
        errorMessage: String
    ) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Place Order Incomplete")
            .setMessage("Payment was approved, but server order processing is incomplete: $errorMessage\n\nPlease tap 'Place Order Again' to complete the order and generate print.")
            .setCancelable(false)
            .setPositiveButton("Place Order Again") { _, _ ->
                processAndNavigatePineLabsOrder(pspRef, approvalCode, cardNumber, cardType, invoiceNo, rrn)
            }
            .setNeutralButton("Choose Payment") { dialog, _ ->
                dialog.dismiss()
                val activeItems = allItems.filter { it.quantity > 0 }
                if (activeItems.isNotEmpty()) {
                    showPaymentOptionDialog(activeItems, pendingTotal)
                } else {
                    Toast.makeText(this, "Cart is empty", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    /**
     * Executes Process API (api/Order/kiosk/placeOrder/{orderId}/Proccess) with settled=true
     * upon Pine Labs payment approval, then clears cart and launches thermal print screen.
     */
    private fun processAndNavigatePineLabsOrder(
        pspRef: String,
        approvalCode: String,
        cardNumber: String,
        cardType: String,
        invoiceNo: Long,
        rrn: String
    ) {
        CommonMethod.showLoader(this, "Processing Pine Labs Order...")
        lifecycleScope.launch {
            try {
                val companyId = authManager.companyId
                val orderIdInt = pendingOrderId.toInt()

                val paymentRefToPass = if (rrn.isNotBlank()) rrn else pspRef
                val processResult = orderRepository.processOrder(
                    orderId = orderIdInt,
                    companyId = companyId,
                    customerId = 0,
                    settled = true,
                    isWhatsApp = isWhatsAppEnabled(),
                    paymentRef = paymentRefToPass
                )

                CommonMethod.dismissLoader()
                val processResponse = processResult.getOrNull()
                val finalOrderId = processResponse?.orderId?.toLong() ?: 0L
                var finalOrderNo = processResponse?.orderNo ?: ""
                var finalEncryptedOrderId = processResponse?.encryptedOrderId ?: ""

                if (finalOrderNo.isBlank() || finalEncryptedOrderId.isBlank() || pendingReceiptItemsJson.isBlank() || pendingTotal <= 0.0) {
                    try {
                        val detailsResult = orderRepository.getOrderDetails(finalOrderId)
                        val detailsData = detailsResult.getOrNull()?.data
                        if (detailsData != null) {
                            if (finalOrderNo.isBlank() && !detailsData.orderNo.isNullOrBlank()) {
                                finalOrderNo = detailsData.orderNo
                            }
                            if (finalEncryptedOrderId.isBlank() && !detailsData.encryptedOrderId.isNullOrBlank()) {
                                finalEncryptedOrderId = detailsData.encryptedOrderId
                            }
                            if (pendingTotal <= 0.0 && !detailsData.totalAmount.isNullOrBlank()) {
                                pendingTotal = detailsData.totalAmount.toDoubleOrNull() ?: pendingTotal
                            }
                            if (pendingReceiptItemsJson.isBlank() && !detailsData.items.isNullOrEmpty()) {
                                val fetchedItems = detailsData.items.mapIndexed { idx, itm ->
                                    val qty = (itm.quantity ?: 1.0).toInt()
                                    val rate = itm.itemRate?.toDoubleOrNull() ?: 0.0
                                    val itemTotal = itm.amount?.toDoubleOrNull() ?: (qty * rate)
                                    ReceiptItem(
                                        no = itm.slNo ?: (idx + 1),
                                        name = itm.itemName ?: "Prasadam Item",
                                        quantity = qty,
                                        rate = rate,
                                        total = itemTotal
                                    )
                                }
                                pendingReceiptItemsJson = Gson().toJson(fetchedItems)
                            }
                        }
                    } catch (_: Exception) {}
                }

                if (processResult.isSuccess && processResponse != null && processResponse.status.equals("PAID", ignoreCase = true) && finalOrderNo.isNotBlank() && finalEncryptedOrderId.isNotBlank()) {
                    val devoteeMobile = binding.etDevoteeMobile.text?.toString()?.trim() ?: ""
                    val intent = Intent(this@BillingActivity, PaymentActivity::class.java).apply {
                        putExtra(PaymentActivity.EXTRA_ORDER_ID, finalOrderId)
                        putExtra(PaymentActivity.EXTRA_PSP_REF_NO, pspRef)
                        putExtra(PaymentActivity.EXTRA_CUSTOMER_MOBILE, devoteeMobile)
                        putExtra(PaymentActivity.EXTRA_LANGUAGE_CODE, currentLanguage)
                        putExtra(PaymentActivity.EXTRA_PAYMENT_MODE, pendingPaymentMode)
                    }
                    lastOrderIntent = intent
                    isLastOrderPrintedSuccessfully = false
                    pendingOrderId = 0L // Reset pending order ID

                    // Clear cart & devotee inputs upon payment completion
                    allItems.forEach { it.quantity = 0 }
                    binding.etDevoteeMobile.text?.clear()
                    productAdapter.notifyDataSetChanged()
                    updateUi()

                    // ... success branch (condition above this unchanged)


                    if (isPrintEnabled()) {
                        startActivity(intent)
                    } else {
                        Toast.makeText(this@BillingActivity, "Payment approved successfully (Print Disabled)", Toast.LENGTH_LONG).show()
                    }
                } else {
                    val err = if (finalOrderNo.isBlank() || finalEncryptedOrderId.isBlank()) {
                        "Order Number or Encrypted Order ID not yet generated by server."
                    } else {
                        processResponse?.message ?: processResult.exceptionOrNull()?.message ?: "Server returned error"
                    }


                    processResult.exceptionOrNull()?.let { Log.e(TAG, "placeOrder exception", it) }

                    showPlaceOrderRetryDialog(pspRef, approvalCode, cardNumber, cardType, invoiceNo, rrn, err)
                }
            } catch (e: Exception) {
                CommonMethod.dismissLoader()
                showPlaceOrderRetryDialog(pspRef, approvalCode, cardNumber, cardType, invoiceNo, rrn, e.message ?: "Unknown error")
            }
        }
    }

    private fun proceedWithRazorpayPayment(activeItems: List<PrasadamItem>, total: Double) {
        proceedWithRazorpayQrPayment(activeItems, total)
    }



    private fun proceedWithRazorpayQrPayment(activeItems: List<PrasadamItem>, total: Double) {
        if (isPaymentProcessing) {
            return
        }
        isPaymentProcessing = true

        val cartItems = activeItems.map { item ->
            OrderCartItem(
                productId = item.id.toIntOrNull() ?: 0,
                variantId = 0,
                quantity = item.quantity,
                rate = item.unitPrice
            )
        }

        if (cartItems.isEmpty()) {
            isPaymentProcessing = false
            Toast.makeText(this, "Cart is empty. Please select prasadam items.", Toast.LENGTH_SHORT).show()
            return
        }

        CommonMethod.showLoader(this, "Generating Razorpay QR Code...")

        lifecycleScope.launch {
            try {
                val companyId = authManager.companyId
                val branchId = authManager.defaultBranch
                val userId = authManager.userId
                val providersResult = orderRepository.getPaymentProviders(companyId)
                val providers = providersResult.getOrNull() ?: emptyList()

                val razorpayQrProvider = providers.firstOrNull { it.name.equals("RAZORAYQR", ignoreCase = true) }
                if (razorpayQrProvider == null) {
                    CommonMethod.dismissLoader()
                    isPaymentProcessing = false
                    val msg = if (providers.isEmpty()) "No payment providers found for Company ID: $companyId" else "RAZORAYQR payment provider is not configured"
                    showModernMessageDialog("Payment Error", msg)
                    return@launch
                }
                val targetProviderId = razorpayQrProvider.providerId

                val sdf = SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss",
                    Locale.US
                )
                sdf.timeZone = TimeZone.getTimeZone("Asia/Kolkata")

                val nowIso = sdf.format(Date())

                val devoteeMobile = binding.etDevoteeMobile.text?.toString()?.trim() ?: ""
                val currentOrderChannel = if (PineLabsManager.isPineLabsAvailable(this@BillingActivity)) "PINELABS" else "KIOSK"

                val orderRequest = OrderKioskRequest(
                    addressId = 0,
                    deliveryTypeId = 0,
                    deliverySlotId = 0,
                    OrderMobileNumber = devoteeMobile,
                    OrderCustomerName = "",
                    expectedDeliveryDate = nowIso,
                    remark = "Razorpay QR Order",
                    orderChanel = currentOrderChannel,
                    tableId = 0,
                    dinningId = 7,
                    paymentProviderId = targetProviderId,
                    couponCode = "",
                    isWalletAmount = false,
                    isWalletLoyality = false,
                    cartItems = cartItems
                )
                val summaryResult = orderRepository.createKioskOrder(
                    companyId = companyId,
                    branchId = branchId,
                    customerId = 0,
                    userId = userId,
                    request = orderRequest
                )

                CommonMethod.dismissLoader()

                val summaryResponse = summaryResult.getOrNull()
                if (summaryResult.isFailure || summaryResponse == null || !summaryResponse.status.equals("Success", ignoreCase = true) || (summaryResponse.orderId ?: 0) <= 0) {
                    isPaymentProcessing = false
                    val err = summaryResponse?.message ?: summaryResult.exceptionOrNull()?.message ?: "Order summary creation failed"
                    showModernMessageDialog("Order Summary Failed", err)
                    return@launch
                }
                val initialOrderId = summaryResponse.orderId!!
                val easeOrderIdFromSummary = summaryResponse.easeOrderId ?: ""
                val qrUrlFromSummary = summaryResponse.data ?: ""

                pendingOrderId = initialOrderId.toLong()
                pendingRazorpayOrderId = easeOrderIdFromSummary
                pendingTotal = total
                pendingCustomerName = ""
                pendingCustomerMobile = devoteeMobile

                // Step 4: Show QR Dialog and start 3-second status polling
                showRazorpayQrDialog(
                    orderId = initialOrderId,
                    easeOrderId = easeOrderIdFromSummary,
                    qrUrl = qrUrlFromSummary,
                    totalAmount = total
                )
            } catch (e: Exception) {
                CommonMethod.dismissLoader()
                isPaymentProcessing = false
                showModernMessageDialog("Order Error", "Failed to initiate Razorpay QR payment: ${e.message}")
            }
        }
    }

    private fun showRazorpayQrDialog(orderId: Int, easeOrderId: String, qrUrl: String, totalAmount: Double) {
        stopQrPollingAndDismiss()
        isQrPaymentHandled = false

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_razorpay_qr, null)
        val tvQrTotalAmount = dialogView.findViewById<TextView>(R.id.tvQrTotalAmount)
        val ivRazorpayQrCode = dialogView.findViewById<ImageView>(R.id.ivRazorpayQrCode)
        val pbQrLoading = dialogView.findViewById<ProgressBar>(R.id.pbQrLoading)
        val tvQrTimer = dialogView.findViewById<TextView>(R.id.tvQrTimer)
        val btnCancelQrPayment = dialogView.findViewById<View>(R.id.btnCancelQrPayment)

        val pbQrHeaderLoader = dialogView.findViewById<ProgressBar>(R.id.pbQrHeaderLoader)
        val ivQrHeaderIcon = dialogView.findViewById<ImageView>(R.id.ivQrHeaderIcon)
        val ivQrHeaderTitleIcon = dialogView.findViewById<ImageView>(R.id.ivQrHeaderTitleIcon)
        val tvQrDialogTitle = dialogView.findViewById<TextView>(R.id.tvQrDialogTitle)
        val layoutQrResultOverlay = dialogView.findViewById<View>(R.id.layoutQrResultOverlay)
        val ivQrResultIcon = dialogView.findViewById<ImageView>(R.id.ivQrResultIcon)
        val pbQrSlipGenerating = dialogView.findViewById<ProgressBar>(R.id.pbQrSlipGenerating)
        val tvQrResultTitle = dialogView.findViewById<TextView>(R.id.tvQrResultTitle)
        val tvQrResultSubtitle = dialogView.findViewById<TextView>(R.id.tvQrResultSubtitle)
        val tvQrInstruction = dialogView.findViewById<TextView>(R.id.tvQrInstruction)

        val verificationMessages = listOf(
            "Please wait while we verify your payment. This may take a few moments.",
            "Processing Payment. Please wait...",
            "Almost there! We're confirming your payment. Please wait..."
        )
        tvQrDialogTitle?.text = "Razorpay UPI QR Payment"
        pbQrHeaderLoader?.visibility = View.GONE
        ivQrHeaderTitleIcon?.visibility = View.VISIBLE
        ivQrHeaderIcon?.visibility = View.GONE
        tvQrTimer?.visibility = View.GONE

        tvQrTotalAmount.text = String.format(Locale.US, "₹%.2f", totalAmount)

        qrDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create().apply {
                setCanceledOnTouchOutside(false)
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }

        // Asynchronously download and load QR Poster Image
        if (qrUrl.isNotBlank()) {
            pbQrLoading.visibility = View.VISIBLE
            lifecycleScope.launch(Dispatchers.IO) {
                var posterBitmap: android.graphics.Bitmap? = null
                try {
                    val client = OkHttpClient.Builder()
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .build()

                    val request = Request.Builder()
                        .url(qrUrl)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                        .build()

                    val response = client.newCall(request).execute()
                    if (response.isSuccessful && response.body != null) {
                        val bytes = response.body!!.bytes()
                        posterBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                        if (posterBitmap != null) {
                        } else {
                            val htmlContent = String(bytes, Charsets.UTF_8)
                            val imgUrlPattern = Regex("""(?i)<meta\s+property=["']og:image["']\s+content=["']([^"']+)["']|<img[^>]+src=["']([^"']+)["']|https?://[^\s"'<>]+\.(?:png|jpg|jpeg|webp)""")
                            val match = imgUrlPattern.find(htmlContent)
                            val extractedImageUrl = match?.groups?.get(1)?.value ?: match?.groups?.get(2)?.value ?: match?.value

                            if (!extractedImageUrl.isNullOrBlank()) {
                                val finalImageUrl = if (extractedImageUrl.startsWith("http")) extractedImageUrl else if (extractedImageUrl.startsWith("//")) "https:$extractedImageUrl" else "https://rzp.io$extractedImageUrl"

                                val imgRequest = Request.Builder()
                                    .url(finalImageUrl)
                                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                                    .build()

                                val imgResponse = client.newCall(imgRequest).execute()
                                if (imgResponse.isSuccessful && imgResponse.body != null) {
                                    val imgBytes = imgResponse.body!!.bytes()
                                    posterBitmap = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size)
                                }
                            }
                        }
                    } else {
                    }
                } catch (e: Exception) {
                }

                // Fallback to local ZXing QR Bitmap if remote poster download returned null
                if (posterBitmap == null) {
                    try {
                        posterBitmap = QrCodeGenerator.generate(qrUrl, 512)
                    } catch (e: Exception) {
                    }
                }

                withContext(Dispatchers.Main) {
                    if (!isDestroyed && !isFinishing) {
                        if (posterBitmap != null) {
                            ivRazorpayQrCode.setImageBitmap(posterBitmap)
                        }
                        pbQrLoading.visibility = View.GONE
                    }
                }
            }
        } else {
            pbQrLoading.visibility = View.GONE
        }

        // Cancel button listener
        btnCancelQrPayment.setOnClickListener {
            stopQrPollingAndDismiss()
            cancelPendingRazorpayOrder()
            showModernMessageDialog("Payment Cancelled", "Razorpay QR payment was cancelled. You can try again.")
        }

        // 5-minute countdown timer
        qrCountDownTimer = object : CountDownTimer(300_000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val minutes = (millisUntilFinished / 1000) / 60
                val seconds = (millisUntilFinished / 1000) % 60
                tvQrTimer.text = String.format(Locale.US, "Please Wait %02d:%02d", minutes, seconds)
            }

            override fun onFinish() {
                tvQrTimer.text = "QR Expired"
                if (!isQrPaymentHandled) {
                    isQrPaymentHandled = true
                    stopQrPollingAndDismiss()
                    cancelPendingRazorpayOrder()
                    showModernMessageDialog("QR Expired", "The QR code has expired after 5 minutes. Please try placing your order again.")
                }
            }
        }.start()

        // Coroutine polling every 3 seconds for status API - INSTANT SUCCESS SETTLEMENT
        qrPollingJob = lifecycleScope.launch {
            var failedRetryCount = 0
            var pollingSeconds = 0

            while (!isQrPaymentHandled && isActive) {
                try {
                    withContext(Dispatchers.Main) {
                        if (pollingSeconds < 15) {
                            tvQrDialogTitle?.text = "Razorpay UPI QR Payment"
                            pbQrHeaderLoader?.visibility = View.GONE
                            ivQrHeaderTitleIcon?.visibility = View.VISIBLE
                            tvQrTimer?.visibility = View.GONE
                        } else {
                            pbQrHeaderLoader?.visibility = View.VISIBLE
                            ivQrHeaderTitleIcon?.visibility = View.GONE
                            tvQrTimer?.visibility = View.VISIBLE
                            val msgIndex = (((pollingSeconds - 15) / 3) % verificationMessages.size)
                            tvQrDialogTitle?.text = verificationMessages[msgIndex]
                        }
                    }

                    val result = orderRepository.checkRazorpayQrPaymentStatus(orderId, easeOrderId)
                    val response = result.getOrNull()
                    val statusItem = response?.data?.firstOrNull()
                    val paymentStatus = statusItem?.status?.uppercase(Locale.US) ?: response?.rootOrderStatus?.uppercase(Locale.US) ?: ""
                    val providerStatus = statusItem?.providerStatus?.uppercase(Locale.US) ?: response?.rootProviderStatus?.uppercase(Locale.US) ?: ""
                    val message = statusItem?.message ?: response?.message ?: ""

                    val isSuccess = paymentStatus in listOf("CAPTURED", "PAID", "SUCCESS", "COMPLETED", "S") ||
                                    providerStatus in listOf("CAPTURED", "PAID", "SUCCESS", "COMPLETED", "S")

                    val isFailed = paymentStatus in listOf("FAILED", "EXPIRED", "CANCELLED", "F", "FAILURE") ||
                                   providerStatus in listOf("FAILED", "EXPIRED", "CANCELLED", "F", "FAILURE")

                    if (isSuccess) {
                        if (!isQrPaymentHandled) {
                            isQrPaymentHandled = true
                            withContext(Dispatchers.Main) {
                                qrCountDownTimer?.cancel()
                                btnCancelQrPayment?.visibility = View.GONE
                                tvQrTimer?.visibility = View.GONE
                                tvQrInstruction?.visibility = View.GONE

                                // 1. Show Payment Success State
                                pbQrHeaderLoader?.visibility = View.GONE
                                ivQrHeaderTitleIcon?.visibility = View.GONE
                                ivQrHeaderIcon?.visibility = View.VISIBLE
                                ivQrHeaderIcon?.setImageResource(R.drawable.ic_check_circle)
                                ivQrHeaderIcon?.setColorFilter(Color.parseColor("#2E7D32"))
                                tvQrDialogTitle?.text = "Payment Confirmed!"
                                tvQrDialogTitle?.setTextColor(Color.parseColor("#2E7D32"))

                                layoutQrResultOverlay?.visibility = View.VISIBLE
                                ivQrResultIcon?.visibility = View.VISIBLE
                                ivQrResultIcon?.setImageResource(R.drawable.ic_transaction_success)
                                ivQrResultIcon?.clearColorFilter()
                                pbQrSlipGenerating?.visibility = View.GONE
                                tvQrResultTitle?.text = "Payment Successful!"
                                tvQrResultTitle?.setTextColor(Color.parseColor("#2E7D32"))
                                tvQrResultSubtitle?.text = "Your payment has been received successfully."

                                // Brief pause so the customer sees the transaction success
                                delay(1800L)

                                // 2. Show "Generating slip..." state
                                ivQrResultIcon?.visibility = View.GONE
                                pbQrSlipGenerating?.visibility = View.VISIBLE
                                tvQrResultTitle?.text = "Generating Receipt Slip"
                                tvQrResultTitle?.setTextColor(Color.parseColor("#E65100"))
                                tvQrResultSubtitle?.text = "Generating slip. Please wait..."

                                tvQrDialogTitle?.text = "Generating slip. Please wait..."
                                tvQrDialogTitle?.setTextColor(Color.parseColor("#1F2937"))
                                pbQrHeaderLoader?.visibility = View.VISIBLE
                                ivQrHeaderIcon?.visibility = View.GONE

                                // 3. Call PlaceOrder (processOrder) API and navigate
                                settleAndNavigateRazorpayQrOrder(orderId, easeOrderId)
                            }
                        }
                        break
                    } else if (isFailed) {
                        if (failedRetryCount < 3) {
                            failedRetryCount++
                            delay(2000L)
                            pollingSeconds += 2
                            continue
                        }
                        if (!isQrPaymentHandled) {
                            isQrPaymentHandled = true
                            withContext(Dispatchers.Main) {
                                qrCountDownTimer?.cancel()
                                pbQrHeaderLoader?.visibility = View.GONE
                                ivQrHeaderIcon?.visibility = View.VISIBLE
                                ivQrHeaderIcon?.setImageResource(R.drawable.ic_close)
                                ivQrHeaderIcon?.setColorFilter(Color.RED)
                                tvQrDialogTitle?.text = "Payment Failed"
                                tvQrDialogTitle?.setTextColor(Color.RED)

                                layoutQrResultOverlay?.visibility = View.VISIBLE
                                ivQrResultIcon?.visibility = View.VISIBLE
                                ivQrResultIcon?.setImageResource(R.drawable.ic_close)
                                ivQrResultIcon?.setColorFilter(Color.RED)
                                pbQrSlipGenerating?.visibility = View.GONE
                                tvQrResultTitle?.text = "Payment Failed"
                                tvQrResultTitle?.setTextColor(Color.RED)
                                tvQrResultSubtitle?.text = if (message.isNotBlank()) message else "Payment was cancelled or failed. Please try again."

                                delay(2500L)
                                stopQrPollingAndDismiss()
                                cancelPendingRazorpayOrder()
                                showModernMessageDialog("Payment Failed", if (message.isNotBlank()) message else "Payment failed or was cancelled. Please try again.")
                            }
                        }
                        break
                    } else {
                        failedRetryCount = 0
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                }

                delay(3000L)
                pollingSeconds += 3
            }
        }

        qrDialog?.show()
        qrDialog?.window?.let { win ->
            val displayMetrics = resources.displayMetrics
            val dialogWidth = (displayMetrics.widthPixels * 0.98).toInt()
            win.setLayout(dialogWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun settleAndNavigateRazorpayQrOrder(orderId: Int, rzpOrderId: String) {
        if (isSettlingOrder) {
            return
        }
        isSettlingOrder = true

        lifecycleScope.launch {
            try {
                val companyId = authManager.companyId
                val settleResult = orderRepository.processOrder(
                    orderId = orderId,
                    companyId = companyId,
                    customerId = 0,
                    settled = true,
                    isWhatsApp = isWhatsAppEnabled()
                )
                val settleResp = settleResult.getOrNull()

                CommonMethod.dismissLoader()
                var finalOrderNo = settleResp?.orderNo ?: ""
                var finalEncryptedOrderId = settleResp?.encryptedOrderId ?: ""

                // If orderNo or encryptedOrderId are missing from placeOrder response, fetch from getOrderDetails
                if (finalOrderNo.isBlank() || finalEncryptedOrderId.isBlank()) {
                    try {
                        val detailsResult = orderRepository.getOrderDetails(orderId.toLong())
                        val detailsData = detailsResult.getOrNull()?.data
                        if (detailsData != null) {
                            if (finalOrderNo.isBlank() && !detailsData.orderNo.isNullOrBlank()) {
                                finalOrderNo = detailsData.orderNo
                            }
                            if (finalEncryptedOrderId.isBlank() && !detailsData.encryptedOrderId.isNullOrBlank()) {
                                finalEncryptedOrderId = detailsData.encryptedOrderId
                            }
                        }
                    } catch (e: Exception) {
                    }
                }

                // ONLY navigate to print when BOTH orderNo and encryptedOrderId are generated!
                if (settleResult.isSuccess && settleResp != null && settleResp.status.equals("PAID", ignoreCase = true) && finalOrderNo.isNotBlank() && finalEncryptedOrderId.isNotBlank()) {
                    pendingOrderId = 0L // Reset pending order ID
                    delay(500L)
                    stopQrPollingAndDismiss()
                    isSettlingOrder = false
                    navigateToRazorpayReceipt(
                        orderId = orderId,
                        rzpOrderId = rzpOrderId,
                        orderNo = finalOrderNo,
                        encryptedOrderId = finalEncryptedOrderId
                    )
                } else {
                    isSettlingOrder = false
                    CommonMethod.dismissLoader()
                    stopQrPollingAndDismiss()
                    val err = if (finalOrderNo.isBlank() || finalEncryptedOrderId.isBlank()) {
                        "Order Number or Encrypted Order ID not yet generated by server."
                    } else {
                        settleResp?.message ?: settleResult.exceptionOrNull()?.message ?: "Order placement failed on server"
                    }
                    showRazorpayPlaceOrderRetryDialog(orderId, rzpOrderId, err)
                }
            } catch (e: Exception) {
                isSettlingOrder = false
                CommonMethod.dismissLoader()
                stopQrPollingAndDismiss()
                showRazorpayPlaceOrderRetryDialog(orderId, rzpOrderId, e.message ?: "An error occurred during order placement")
            }
        }
    }

    private fun showRazorpayPlaceOrderRetryDialog(
        orderId: Int,
        rzpOrderId: String,
        errorMessage: String
    ) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Place Order Incomplete")
            .setMessage("Payment was received, but server place order is incomplete:\n$errorMessage\n\nPlease tap 'Place Order Again' to generate the order number and print receipt.")
            .setCancelable(false)
            .setPositiveButton("Place Order Again") { dialog, _ ->
                dialog.dismiss()
                CommonMethod.showLoader(this@BillingActivity, "Placing Order...")
                settleAndNavigateRazorpayQrOrder(orderId, rzpOrderId)
            }

            .show()
    }

    private fun stopQrPollingAndDismiss() {
        isPaymentProcessing = false
        qrPollingJob?.cancel()
        qrPollingJob = null
        qrCountDownTimer?.cancel()
        qrCountDownTimer = null
        qrDialog?.dismiss()
        qrDialog = null
    }



    private fun showRazorpayPendingDialog() {
        if (isFinishing || isDestroyed) return
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_payment_pending, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        val tvOrderId = dialogView.findViewById<android.widget.TextView>(R.id.tvPendingOrderId)
        val tvRzpRef = dialogView.findViewById<android.widget.TextView>(R.id.tvPendingRzpRef)
        val tvTotalAmount = dialogView.findViewById<android.widget.TextView>(R.id.tvPendingTotalAmount)
        val btnContinue = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnContinuePaymentAction)
        val btnCancel = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnCancelOrderAction)

        tvOrderId?.text = "#$pendingOrderId"
        tvRzpRef?.text = if (pendingRazorpayOrderId.isNotBlank()) pendingRazorpayOrderId else "N/A"
        tvTotalAmount?.text = "₹%.2f".format(Locale.US, pendingTotal)

        btnContinue?.setOnClickListener {
            dialog.dismiss()
            if (pendingOrderId > 0L && pendingRazorpayOrderId.isNotBlank()) {
                showRazorpayQrDialog(
                    orderId = pendingOrderId.toInt(),
                    easeOrderId = pendingRazorpayOrderId,
                    qrUrl = "",
                    totalAmount = pendingTotal
                )
            } else {
                Toast.makeText(this, "No active order to continue", Toast.LENGTH_SHORT).show()
            }
        }

        btnCancel?.setOnClickListener {
            dialog.dismiss()
            cancelPendingRazorpayOrder()
        }

        dialog.show()
    }

    private fun cancelPendingRazorpayOrder() {
        if (pendingOrderId <= 0L) {
            allItems.forEach { it.quantity = 0 }
            binding.etDevoteeMobile.text?.clear()
            productAdapter.notifyDataSetChanged()
            updateUi()
            return
        }
        CommonMethod.showLoader(this, "Cancelling Order...")
        lifecycleScope.launch {
            try {
                val companyId = authManager.companyId
                val result = orderRepository.cancelOrder(pendingOrderId.toInt(), companyId, settled = false)
                CommonMethod.dismissLoader()
                val response = result.getOrNull()

                allItems.forEach { it.quantity = 0 }
                binding.etDevoteeMobile.text?.clear()
                productAdapter.notifyDataSetChanged()
                updateUi()

                if (result.isSuccess) {
                    Toast.makeText(this@BillingActivity, "Order cancelled successfully", Toast.LENGTH_SHORT).show()
                } else {
                    val err = response?.message ?: result.exceptionOrNull()?.message ?: "Failed to cancel order"
                }
            } catch (e: Exception) {
                CommonMethod.dismissLoader()
                allItems.forEach { it.quantity = 0 }
                binding.etDevoteeMobile.text?.clear()
                productAdapter.notifyDataSetChanged()
                updateUi()
            }
        }
    }

    private fun navigateToRazorpayReceipt(
        orderId: Int,
        rzpOrderId: String,
        orderNo: String,
        encryptedOrderId: String
    ) {
        if (isNavigatingToReceipt) {
            return
        }
        if (orderNo.isBlank() || encryptedOrderId.isBlank()) {
            isNavigatingToReceipt = false
            showRazorpayPlaceOrderRetryDialog(orderId, rzpOrderId, "Order number or Encrypted Order ID not generated yet.")
            return
        }
        isNavigatingToReceipt = true

        val devoteeMobile = binding.etDevoteeMobile.text?.toString()?.trim() ?: ""

        val intent = Intent(this@BillingActivity, PaymentActivity::class.java).apply {
            putExtra(PaymentActivity.EXTRA_CUSTOMER_NAME, "")
            putExtra(PaymentActivity.EXTRA_CUSTOMER_MOBILE, devoteeMobile)
            putExtra(PaymentActivity.EXTRA_ORDER_ID, orderId.toLong())
            putExtra(PaymentActivity.EXTRA_PSP_REF_NO, rzpOrderId)
            putExtra(PaymentActivity.EXTRA_LANGUAGE_CODE, currentLanguage)
            putExtra(PaymentActivity.EXTRA_PAYMENT_MODE, "Razorpay UPI QR")
        }
        lastOrderIntent = intent
        isLastOrderPrintedSuccessfully = false

        // Clear cart & devotee inputs FIRST
        allItems.forEach { it.quantity = 0 }
        binding.etDevoteeMobile.text?.clear()
        productAdapter.notifyDataSetChanged()
        updateUi()

        if (isPrintEnabled()) {
            startActivity(intent)
        } else {
            Toast.makeText(this, "Razorpay payment successful (Print Disabled)", Toast.LENGTH_LONG).show()
        }
    }



    private fun showCashReferenceDialog(activeItems: List<PrasadamItem>) {
        if (!validateMobileNumberForWhatsApp()) return
        BillingDialogHelper.showCashReferenceDialog(this, authManager) { refId, adminPassword, onSuccess, onError ->
            proceedWithCashPayment(activeItems, refId, adminPassword, onSuccess, onError)
        }
    }


    private fun proceedWithCashPayment(
        activeItems: List<PrasadamItem>, 
        customRefId: String? = null, 
        adminPassword: String = "",
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (isPaymentProcessing) {
            return
        }
        isPaymentProcessing = true

        val cartItems = activeItems.map { item ->
            OrderCartItem(
                productId = item.id.toIntOrNull() ?: 0,
                variantId = 0,
                quantity = item.quantity,
                rate = item.unitPrice
            )
        }

        if (cartItems.isEmpty()) {
            isPaymentProcessing = false
            Toast.makeText(this, "Cart is empty. Please select prasadam items.", Toast.LENGTH_SHORT).show()
            return
        }

        CommonMethod.showLoader(this, "Processing Cash Order...")

        lifecycleScope.launch {
            try {
                val companyId = authManager.companyId
                val branchId = authManager.defaultBranch
                val userId = authManager.userId

                val devoteeMobile = binding.etDevoteeMobile.text?.toString()?.trim() ?: ""

                val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("Asia/Kolkata")
                }.format(Date())

                val orderChannel = if (PineLabsManager.isPineLabsAvailable(this@BillingActivity)) "PINELABS" else "KIOSK"

                val orderRequest = OrderKioskRequest(
                    addressId = 0,
                    deliveryTypeId = 0,
                    deliverySlotId = 0,
                    OrderMobileNumber = devoteeMobile,
                    OrderCustomerName = "",
                    expectedDeliveryDate = nowIso,
                    remark = "Counter Cash Order",
                    orderChanel = orderChannel,
                    paymentRefNo = customRefId ?: "",
                    adminPassword = adminPassword,
                    tableId = 0,
                    dinningId = 0,
                    paymentProviderId = 0,
                    couponCode = "",
                    isWalletAmount = false,
                    isWalletLoyality = false,
                    cartItems = cartItems
                )

                val orderResult = orderRepository.createKioskOrder(
                    companyId = companyId,
                    branchId = branchId,
                    customerId = 0,
                    userId = userId,
                    request = orderRequest
                )

                CommonMethod.dismissLoader()
                val orderResponse = orderResult.getOrNull()

                if (orderResult.isSuccess && orderResponse != null && orderResponse.status.equals("Success", ignoreCase = true) && (orderResponse.orderId ?: 0) > 0) {
                    val initialOrderId = orderResponse.orderId!!
                    val pspRef = customRefId.takeIf { !it.isNullOrBlank() }
                        ?: orderResponse.easeOrderId
                        ?: "CASH-${System.currentTimeMillis()}"

                    CommonMethod.showLoader(this@BillingActivity, "Placing Cash Order...")

                    // Step 2: Execute Process API for Cash Order (settled=true)
                    val processResult = orderRepository.processOrder(
                        orderId = initialOrderId,
                        companyId = companyId,
                        customerId = 0,
                        settled = true,
                        isWhatsApp = isWhatsAppEnabled()
                    )
                    CommonMethod.dismissLoader()
                    val processResponse = processResult.getOrNull()

                    val finalOrderId = (processResponse?.orderId ?: initialOrderId).toLong()
                    var finalOrderNo = processResponse?.orderNo ?: ""
                    var finalEncryptedOrderId = processResponse?.encryptedOrderId ?: ""

                    if (finalOrderNo.isBlank() || finalEncryptedOrderId.isBlank()) {
                        try {
                            val detailsResult = orderRepository.getOrderDetails(finalOrderId)
                            val detailsData = detailsResult.getOrNull()?.data
                            if (detailsData != null) {
                                if (finalOrderNo.isBlank() && !detailsData.orderNo.isNullOrBlank()) {
                                    finalOrderNo = detailsData.orderNo
                                }
                                if (finalEncryptedOrderId.isBlank() && !detailsData.encryptedOrderId.isNullOrBlank()) {
                                    finalEncryptedOrderId = detailsData.encryptedOrderId
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    if (processResult.isSuccess && processResponse != null && processResponse.status.equals("PAID", ignoreCase = true) && finalOrderNo.isNotBlank() && finalEncryptedOrderId.isNotBlank()) {
                        onSuccess() // Dismisses cash dialog ONLY when BOTH summary and placeOrder succeed with orderNo & encryptedOrderId!

                        val intent = Intent(this@BillingActivity, PaymentActivity::class.java).apply {
                            putExtra(PaymentActivity.EXTRA_CUSTOMER_NAME, "")
                            putExtra(PaymentActivity.EXTRA_CUSTOMER_MOBILE, devoteeMobile)
                            putExtra(PaymentActivity.EXTRA_ORDER_ID, finalOrderId)
                            putExtra(PaymentActivity.EXTRA_PSP_REF_NO, pspRef)
                            putExtra(PaymentActivity.EXTRA_LANGUAGE_CODE, currentLanguage)
                            putExtra(PaymentActivity.EXTRA_PAYMENT_MODE, "Cash Payment")
                        }
                        lastOrderIntent = intent
                        isLastOrderPrintedSuccessfully = false
                        pendingOrderId = 0L // Reset pending order ID
                        if (isPrintEnabled()) {
                            isPrintPending = false
                            binding.btnManualPrint?.visibility = View.GONE
                            startActivity(intent)
                        } else {
                            Toast.makeText(this@BillingActivity, "Cash order completed successfully (Print Disabled)", Toast.LENGTH_LONG).show()
                            isPrintPending = true
                            binding.btnManualPrint?.visibility = View.VISIBLE
                        }
                        allItems.forEach { item -> item.quantity = 0 }
                        binding.etDevoteeMobile.text?.clear()
                        productAdapter.notifyDataSetChanged()
                        updateUi()
                    } else {
                        isPaymentProcessing = false
                        val placeOrderErr = if (finalOrderNo.isBlank() || finalEncryptedOrderId.isBlank()) {
                            "Order Number or Encrypted Order ID not yet generated by server."
                        } else {
                            processResponse?.message ?: processResult.exceptionOrNull()?.message ?: "Cash order placement failed"
                        }
                        onError(placeOrderErr)
                    }
                } else {
                    isPaymentProcessing = false
                    val errorMsg = orderResponse?.message ?: orderResult.exceptionOrNull()?.message ?: "Cash order summary creation failed"
                    onError(errorMsg)
                }
            } catch (e: Exception) {
                isPaymentProcessing = false
                CommonMethod.dismissLoader()
                val err = e.message ?: "Cash payment error"
                onError(err)
            }
        }
    }

    private fun loadEmployeeBannerImage(url: String) {
        try {
            com.bumptech.glide.Glide.with(this)
                .load(url)
                .placeholder(R.drawable.temple_banner)
                .error(R.drawable.temple_banner)
                .centerCrop()
                .into(binding.ivTempleBanner)
        } catch (e: Exception) {
        }
    }

    private var isDrawerAuthenticated = false
    private var isPromptShowing = false
    private var lastPromptDismissTime: Long = 0L
    private var drawerTimeoutTimer: CountDownTimer? = null

    private fun setupNavigationDrawer() {
        val drawerLayout = findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawerLayout)
        val tvDrawerEmployeeName = findViewById<TextView>(R.id.tvDrawerEmployeeName)
        val tvDrawerDesignation = findViewById<TextView>(R.id.tvDrawerDesignation)
        val ivDrawerProfile = findViewById<ImageView>(R.id.ivDrawerProfile)
        val tvAppVersion = findViewById<TextView>(R.id.tvAppVersion)
        val menuTerminalInfo = findViewById<View>(R.id.menuTerminalInfo)
        val menuLogout = findViewById<View>(R.id.menuLogout)

        val isPineLabs = PineLabsManager.isPineLabsAvailable(this)
        if (isPineLabs) {
            drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED)
        } else {
            drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        }

        drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                if (!PineLabsManager.isPineLabsAvailable(this@BillingActivity) && !isDrawerAuthenticated && slideOffset > 0.01f) {
                    drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START, false)
                }
            }

            override fun onDrawerOpened(drawerView: View) {
                isPromptShowing = false
                startDrawerAutoCloseTimer(drawerLayout)
            }

            override fun onDrawerClosed(drawerView: View) {
                isDrawerAuthenticated = false
                isPromptShowing = false
                lastPromptDismissTime = System.currentTimeMillis()
                if (!PineLabsManager.isPineLabsAvailable(this@BillingActivity)) {
                    drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                }
                drawerAutoCloseTimer?.cancel()
            }

            override fun onDrawerStateChanged(newState: Int) {}
        })

        val name = authManager.employeeName
        tvDrawerEmployeeName.text = if (name.isNotBlank()) name else "Counter Operator"

        val desig = authManager.designation
        tvDrawerDesignation.text = if (desig.isNotBlank()) desig else "Operations Manager"

        if (authManager.employeeImage.isNotBlank()) {
            try {
                com.bumptech.glide.Glide.with(this)
                    .load(authManager.employeeImage)
                    .placeholder(R.drawable.ic_person)
                    .error(R.drawable.ic_person)
                    .circleCrop()
                    .into(ivDrawerProfile)
            } catch (_: Exception) {}
        }

        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            tvAppVersion.text = "Version ${pInfo.versionName ?: "1.0.0"}"
        } catch (_: Exception) {
            tvAppVersion.text = "Version 1.0.0"
        }

        menuTerminalInfo.setOnClickListener {
            drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
            showPineLabsTerminalInfoDialog()
        }

        val menuReport = findViewById<View>(R.id.menuReport)
        menuReport?.setOnClickListener {
            drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
            showReportsDialog()
        }

        val menuLockCounter = findViewById<View>(R.id.menuLockCounter)
        val switchKioskMode = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchKioskMode)
        val kioskPrefs = getSharedPreferences("kiosk_settings", MODE_PRIVATE)
        val isKioskModeOn = kioskPrefs.getBoolean("pref_kiosk_mode_enabled", false)
        switchKioskMode?.isChecked = isKioskModeOn

        switchKioskMode?.setOnCheckedChangeListener { _, isChecked ->
            kioskPrefs.edit().putBoolean("pref_kiosk_mode_enabled", isChecked).apply()
            updateHomeLauncherAliasState(isChecked)
            if (isChecked) {
                checkAndRequestOverlayPermission()
                if (!PineLabsManager.isPineLabsAvailable(this)) {
                    try {
                        startLockTask() // Screen pinning / Lock Kiosk
                        Toast.makeText(this, "KIOSK Mode Enabled (Locked)", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Screen pinning not supported", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "KIOSK Mode is disabled on Pine Labs terminal", Toast.LENGTH_SHORT).show()
                }
            } else {
                try {
                    stopLockTask()
                    Toast.makeText(this, "KIOSK Mode Disabled (Unlocked)", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {

                }
            }
        }

        menuLockCounter?.setOnClickListener {
            switchKioskMode?.isChecked = !(switchKioskMode?.isChecked ?: false)
        }

        menuLogout.setOnClickListener {
            drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Logout")
                .setMessage("Are you sure you want to log out?")
                .setPositiveButton("Logout") { _, _ ->
                    authManager.logout()
                    try {
                        stopLockTask() // Exit Kiosk Mode on logout
                    } catch (_: Exception) {}
                    val intent = Intent(this, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        val switchWhatsApp = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchWhatsApp)
        val switchPrint = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPrint)

        val prefs = getSharedPreferences("kiosk_settings", MODE_PRIVATE)
        var isWhatsAppOn = prefs.getBoolean("pref_whatsapp_enabled", true)
        var isPrintOn = prefs.getBoolean("pref_print_enabled", true)

        switchWhatsApp?.isChecked = isWhatsAppOn
        switchPrint?.isChecked = isPrintOn

        switchWhatsApp?.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked && switchPrint != null && !switchPrint.isChecked) {
                switchWhatsApp.isChecked = true
                Toast.makeText(this, "Select at least one: WhatsApp or Print", Toast.LENGTH_SHORT).show()
            } else {
                isWhatsAppOn = isChecked
                prefs.edit().putBoolean("pref_whatsapp_enabled", isWhatsAppOn).apply()
                updateDevoteeMobileHint()
            }
        }

        switchPrint?.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked && switchWhatsApp != null && !switchWhatsApp.isChecked) {
                switchPrint.isChecked = true
                Toast.makeText(this, "Select at least one: WhatsApp or Print", Toast.LENGTH_SHORT).show()
            } else {
                isPrintOn = isChecked
                prefs.edit().putBoolean("pref_print_enabled", isPrintOn).apply()
            }
        }
    }

    private fun updateHomeLauncherAliasState(isEnabled: Boolean) {
        try {
            val componentName = android.content.ComponentName(this, "$packageName.HomeLauncherAlias")
            val state = if (isEnabled) {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            packageManager.setComponentEnabledSetting(
                componentName,
                state,
                android.content.pm.PackageManager.DONT_KILL_APP
            )

        } catch (e: Exception) {

        }
    }

    private fun checkAndRequestOverlayPermission() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            try {
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(intent)
                Toast.makeText(this, "Please allow 'Display over other apps' for Kiosk auto-start", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {

            }
        }
    }

    private fun isWhatsAppEnabled(): Boolean {
        if (PineLabsManager.isPineLabsAvailable(this)) {
            return false // Pine Labs devices always bypass WhatsApp switch
        }
        val prefs = getSharedPreferences("kiosk_settings", MODE_PRIVATE)
        return prefs.getBoolean("pref_whatsapp_enabled", true)
    }

    private fun isPrintEnabled(): Boolean {
        if (PineLabsManager.isPineLabsAvailable(this)) {
            return true // Pine Labs devices always have printing enabled
        }
        val prefs = getSharedPreferences("kiosk_settings", MODE_PRIVATE)
        return prefs.getBoolean("pref_print_enabled", true)
    }

    private fun validateMobileNumberForWhatsApp(): Boolean {
        if (PineLabsManager.isPineLabsAvailable(this)) {
            binding.tilDevoteeMobile.error = null
            return true // Bypasses mobile number validation on Pine Labs devices
        }
        if (isWhatsAppEnabled()) {
            val mobile = binding.etDevoteeMobile.text?.toString()?.trim() ?: ""
            if (mobile.isBlank() || mobile.length < 10) {
                binding.tilDevoteeMobile.error = "Mobile number is mandatory when WhatsApp is enabled"
                Toast.makeText(this, "Please enter a valid 10-digit mobile number for WhatsApp receipt", Toast.LENGTH_LONG).show()
                binding.etDevoteeMobile.requestFocus()
                showMobileKeypadDialog()
                return false
            } else {
                binding.tilDevoteeMobile.error = null
            }
        } else {
            binding.tilDevoteeMobile.error = null
        }
        return true
    }

    private fun showMobileKeypadDialog() {
        if (activeMobileKeypadDialog?.isShowing == true) return
        var currentInput = binding.etDevoteeMobile.text?.toString()?.trim() ?: ""
        var isFreshInput = currentInput.isBlank()

        val res = resources
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

        val rootLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(rootPadPx, rootPadPx, rootPadPx, rootPadPx)
        }

        val noticeText = if (isWhatsAppEnabled()) {
            "Enter Your Mobile Number To Receive Receipt In WhatsApp"
        } else {
            "Enter Mobile Number"
        }

        val tvNotice = TextView(this).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setTextColor(getColor(R.color.temple_gold_primary))
            text = noticeText
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = displayMarginBottomPx / 2
            }
        }
        rootLayout.addView(tvNotice)

        val tvDisplay = TextView(this).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, displayTextPx)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setTextColor(getColor(R.color.text_dark))
            text = if (currentInput.isEmpty()) "" else currentInput
            setBackgroundResource(R.drawable.bg_total_box)
            setPadding(displayPadPx, displayPadPx, displayPadPx, displayPadPx)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = displayMarginBottomPx
            }
        }
        rootLayout.addView(tvDisplay)

        fun updateDisplay() {
            tvDisplay.text = if (currentInput.isEmpty()) "" else currentInput
        }

        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("C", "0", "⌫")
        )

        rows.forEach { rowKeys ->
            val rowLayout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                isBaselineAligned = false
                weightSum = 3f
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = rowMarginBottomPx
                }
            }

            rowKeys.forEach { key ->
                val btn = androidx.appcompat.widget.AppCompatButton(this).apply {
                    text = key
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, keyTextPx)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    stateListAnimator = null
                    when (key) {
                        "C" -> {
                            setTextColor(getColor(R.color.delete_red))
                            setBackgroundResource(R.drawable.bg_keypad_key_clear)
                        }
                        "⌫" -> {
                            setTextColor(getColor(R.color.pay_button_orange))
                            setBackgroundResource(R.drawable.bg_keypad_action)
                        }
                        else -> {
                            setTextColor(getColor(R.color.text_dark))
                            setBackgroundResource(R.drawable.bg_keypad_button)
                        }
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(
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

        val actionRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            weightSum = 2f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = actionMarginTopPx
            }
        }

        val btnCancel = androidx.appcompat.widget.AppCompatButton(this).apply {
            text = "Cancel"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, actionTextPx)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            stateListAnimator = null
            setTextColor(getColor(R.color.text_muted))
            setBackgroundResource(R.drawable.bg_payment_card_selector)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0,
                actionHeightPx,
                1f
            ).apply {
                setMargins(0, 0, actionGapPx, 0)
            }
        }

        val btnOk = androidx.appcompat.widget.AppCompatButton(this).apply {
            text = "OK"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, actionTextPx)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            stateListAnimator = null
            setTextColor(getColor(R.color.white))
            setBackgroundResource(R.drawable.bg_pay_button)
            layoutParams = android.widget.LinearLayout.LayoutParams(
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

        val scrollView = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(rootLayout)
        }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("")
            .setView(scrollView)
            .create()
        activeMobileKeypadDialog = dialog
        dialog.setOnDismissListener {
            if (activeMobileKeypadDialog == dialog) {
                activeMobileKeypadDialog = null
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnOk.setOnClickListener {
            binding.etDevoteeMobile.setText(currentInput)
            binding.tilDevoteeMobile.error = null
            dialog.dismiss()
        }

        dialog.show()
    }

    private var drawerAutoCloseTimer: CountDownTimer? = null

    private fun startDrawerAutoCloseTimer(drawerLayout: androidx.drawerlayout.widget.DrawerLayout) {
        drawerAutoCloseTimer?.cancel()
        drawerAutoCloseTimer = object : CountDownTimer(20000L, 2000L) {
            override fun onTick(millisUntilFinished: Long) {}

            override fun onFinish() {
                if (!isFinishing && !isDestroyed && drawerLayout.isDrawerOpen(androidx.core.view.GravityCompat.START)) {
                    drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
                    Toast.makeText(this@BillingActivity, "Navigation menu closed due to inactivity", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun showDrawerPasswordDialog(onDismissCallback: () -> Unit) {
        if (isFinishing || isDestroyed) return
        drawerTimeoutTimer?.cancel()
        drawerNavAuthDialog?.dismiss()

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_nav_auth, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        drawerNavAuthDialog = dialog

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvNavAuthMessage = dialogView.findViewById<TextView>(R.id.tvNavAuthMessage)
        val etNavPassword = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etNavPassword)
        val btnNavAuthVerify = dialogView.findViewById<View>(R.id.btnNavAuthVerify)
        val btnNavAuthCancel = dialogView.findViewById<View>(R.id.btnNavAuthCancel)
        val btnNavAuthCancelStandard = dialogView.findViewById<View>(R.id.btnNavAuthCancelStandard)

        btnNavAuthVerify?.setOnClickListener {
            val password = etNavPassword?.text?.toString()?.trim().orEmpty()
            val loginPassword = authManager.savedPassword
            val isValid = loginPassword.isNotBlank() && password == loginPassword

            if (isValid) {
                drawerTimeoutTimer?.cancel()
                isDrawerAuthenticated = true
                isPromptShowing = false
                lastPromptDismissTime = System.currentTimeMillis()
                dialog.dismiss()
                if (!PineLabsManager.isPineLabsAvailable(this)) {
                    try {
                        stopLockTask() // UNPIN only for Kiosk mode
                    } catch (e: Exception) {
                    }
                }
                val drawerLayout = findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawerLayout)
                drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED)
                drawerLayout.openDrawer(androidx.core.view.GravityCompat.START)
            } else {
                Toast.makeText(this, "Incorrect password. Enter your login password.", Toast.LENGTH_SHORT).show()
            }
        }

        val cancelClickListener = View.OnClickListener {
            drawerTimeoutTimer?.cancel()
            isPromptShowing = false
            lastPromptDismissTime = System.currentTimeMillis()
            dialog.dismiss()
        }

        btnNavAuthCancel?.setOnClickListener(cancelClickListener)
        btnNavAuthCancelStandard?.setOnClickListener(cancelClickListener)

        dialog.setOnDismissListener {
            drawerTimeoutTimer?.cancel()
            isPromptShowing = false
            lastPromptDismissTime = System.currentTimeMillis()
            onDismissCallback()
        }

        dialog.show()

        // 10-second countdown timer
        drawerTimeoutTimer = object : CountDownTimer(20000L, 2000L) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = millisUntilFinished / 1000
                if (dialog.isShowing) {
                    tvNavAuthMessage?.text = "Please enter operator password to continue.\nPopup auto-closes in ${sec}s"
                }
            }

            override fun onFinish() {
                if (dialog.isShowing) {
                    dialog.dismiss()
                    Toast.makeText(this@BillingActivity, "Navigation authentication timed out", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private var swipeStartX = 0f
    private var swipeStartY = 0f

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val edgeThresholdPx = 120f * resources.displayMetrics.density
        val now = System.currentTimeMillis()

        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = ev.x
                swipeStartY = ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - swipeStartX
                val dy = Math.abs(ev.y - swipeStartY)
                if (swipeStartX < edgeThresholdPx && dx > 30f && dy < 100f) {
                    if (PineLabsManager.isPineLabsAvailable(this)) {
                        return super.dispatchTouchEvent(ev)
                    }

                    val drawerLayout = getDrawerLayout()
                    val isOpened = drawerLayout?.isDrawerOpen(androidx.core.view.GravityCompat.START) == true
                    if (!isOpened && !isDrawerAuthenticated && !isPromptShowing && (now - lastPromptDismissTime > 1500L)) {
                        isPromptShowing = true
                        showDrawerPasswordDialog {
                            isPromptShowing = false
                            lastPromptDismissTime = System.currentTimeMillis()
                        }
                        return true
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopQrPollingAndDismiss()
        reprintAuthDialog?.dismiss()
        reprintAuthDialog = null
        drawerNavAuthDialog?.dismiss()
        drawerNavAuthDialog = null
        activeMobileKeypadDialog?.dismiss()
        activeMobileKeypadDialog = null
        drawerTimeoutTimer?.cancel()
        drawerAutoCloseTimer?.cancel()
        CommonMethod.dismissLoader()
    }

    private fun showKioskReprintAuthDialog() {
        if (isFinishing || isDestroyed) return
        reprintAuthDialog?.dismiss()
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_nav_auth, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        reprintAuthDialog = dialog

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val tvNavAuthMessage = dialogView.findViewById<TextView>(R.id.tvNavAuthMessage)
        val etNavPassword = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etNavPassword)
        val layoutVerifyRow = dialogView.findViewById<View>(R.id.layoutVerifyRow)
        val layoutReprintTestRow = dialogView.findViewById<View>(R.id.layoutReprintTestRow)
        val btnNavAuthCancel = dialogView.findViewById<View>(R.id.btnNavAuthCancel)
        val btnNavAuthCancelStandard = dialogView.findViewById<View>(R.id.btnNavAuthCancelStandard)
        val btnNavAuthReprint = dialogView.findViewById<View>(R.id.btnNavAuthReprint)
        val btnNavAuthTestPrint = dialogView.findViewById<View>(R.id.btnNavAuthTestPrint)

        tvNavAuthMessage?.text = "Enter operator password to reprint or test print."
        layoutVerifyRow?.visibility = View.GONE
        layoutReprintTestRow?.visibility = View.VISIBLE

        btnNavAuthReprint?.setOnClickListener {
            val password = etNavPassword?.text?.toString()?.trim().orEmpty()
            val loginPassword = authManager.savedPassword
            val isValid = loginPassword.isNotBlank() && password == loginPassword

            if (isValid) {
                dialog.dismiss()
                reprintAuthDialog = null
                val lastIntent = lastOrderIntent
                if (lastIntent != null) {
                    val reprintIntent = Intent(lastIntent).apply {
                        putExtra(PaymentActivity.EXTRA_IS_DUPLICATE_PRINT, true)
                    }
                    startActivity(reprintIntent)
                    Toast.makeText(this, "Reprinting last order as Duplicate", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "No previous order to print", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Incorrect password. Enter your login password.", Toast.LENGTH_SHORT).show()
            }
        }

        btnNavAuthTestPrint?.setOnClickListener {
            val password = etNavPassword?.text?.toString()?.trim().orEmpty()
            val loginPassword = authManager.savedPassword
            val isValid = loginPassword.isNotBlank() && password == loginPassword

            if (isValid) {
                dialog.dismiss()
                reprintAuthDialog = null
                val intent = Intent(this, PaymentActivity::class.java).apply {
                    putExtra(PaymentActivity.EXTRA_IS_TEST_PRINT, true)
                    putExtra(PaymentActivity.EXTRA_TOTAL_AMOUNT, 0.0)
                }
                startActivity(intent)
                Toast.makeText(this, "Generating Test Print", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Incorrect password. Enter your login password.", Toast.LENGTH_SHORT).show()
            }
        }

        val cancelListener = View.OnClickListener {
            dialog.dismiss()
            reprintAuthDialog = null
        }
        btnNavAuthCancel?.setOnClickListener(cancelListener)
        btnNavAuthCancelStandard?.setOnClickListener(cancelListener)

        dialog.show()
    }
}