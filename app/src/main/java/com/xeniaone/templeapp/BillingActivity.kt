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

    // Razorpay QR State & Coroutine Polling
    private var qrPollingJob: kotlinx.coroutines.Job? = null
    private var qrCountDownTimer: CountDownTimer? = null
    private var qrDialog: AlertDialog? = null
    private var reprintAuthDialog: AlertDialog? = null
    private var drawerNavAuthDialog: AlertDialog? = null
    private var activeMobileKeypadDialog: AlertDialog? = null
    private var isQrPaymentHandled: Boolean = false
    private var isPrintPending: Boolean = false
    private var isPaymentProcessing: Boolean = false
    private var lastPayClickTime: Long = 0L

    private var cachedDrawerLayout: androidx.drawerlayout.widget.DrawerLayout? = null
    private fun getDrawerLayout(): androidx.drawerlayout.widget.DrawerLayout? {
        if (cachedDrawerLayout == null) {
            cachedDrawerLayout = findViewById(R.id.drawerLayout)
        }
        return cachedDrawerLayout
    }

    // Launcher for Pine Labs App-to-App Payment Intent (MethodId: 1001)
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

        // Suppress system default keyboard for devotee mobile input (custom keypad used)
        binding.etDevoteeMobile.showSoftInputOnFocus = false

        currentLanguage = intent.getStringExtra(EXTRA_LANGUAGE_CODE) ?: authManager.selectedLanguage

        // Detect Pine Labs hardware / MasterApp availability while loading
        detectAndDisplayPineLabsDevice()

        // Check if Kiosk Mode was enabled; if so, enforce lock task & home launcher alias
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
                Log.w(TAG, "⚠️ Rapid tap detected on Pay button. Ignoring duplicate click.")
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
                startActivity(lastIntent)
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

        // Initial empty render (No dummy table items, initial total ₹0.00)
        updateUi()

        // Show loading state while connecting to socket and fetching products
        showProductsLoader()

        // 1. Fetch fresh employee profile in background
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
                        Log.i(TAG, "Profile refreshed: CompanyId=${d.companyId}, DefaultBranch=${d.defaultBranch}")
                        if (!d.image.isNullOrBlank()) {
                            runOnUiThread {
                                loadEmployeeBannerImage(d.image)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Profile fetch fallback to saved: ${e.message}")
                }
            }

            // 2. Connect SignalR Socket using companyId & defaultBranch
            val companyId = authManager.companyId
            val branchId = authManager.defaultBranch
            Log.i(TAG, "Connecting SignalR Socket for companyId=$companyId, branchId=$branchId")
            socketManager.connect(companyId, branchId)
        }

        // 3. Observe dynamic products received from Socket
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                socketManager.productsFlow.collect { socketProducts ->
                    if (socketProducts.isNotEmpty()) {
                        Log.i(TAG, "📥 [Socket Products Flow] Collected ${socketProducts.size} products from SocketFlow stream!")
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
                Log.w(TAG, "Socket response timed out, loading default prasadam items with 0 quantities")
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
            // Keep track of any quantity the user might have already selected
            val existingQuantities = allItems.associate { it.name.lowercase() to it.quantity }

            val newItems = mutableListOf<PrasadamItem>()
            val gson = Gson()
            for (p in socketProducts) {
                Log.d("BillingActivity", "Parsed SocketProduct: ${gson.toJson(p)}")
                Log.d("BillingActivity", "Product: ${p.productName}, maxQtyPerOrder: ${p.maxQtyPerOrder}")
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
            Log.i(TAG, "🖥️ [Product Display] Successfully displayed ${newItems.size} items on the product list grid.")
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
        // Populate Table Rows
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

        // Calculate and format Total Amount (Starts at ₹0.00 when no items)
        val totalAmount = allItems.sumOf { it.totalAmount }
        binding.tvTotalAmountValue.text = "₹%.2f".format(totalAmount)
        binding.btnPay.isEnabled = true
        binding.btnPay.alpha = if (activeItems.isNotEmpty()) 1.0f else 0.7f
    }

    override fun onResume() {
        super.onResume()
        isPaymentProcessing = false
        detectAndDisplayPineLabsDevice()
        updateDevoteeMobileHint()

        // Re-enforce Kiosk Mode (Screen Pinning) if enabled and unpinned by "No thanks"
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
                Log.w(TAG, "Re-enforce lock task failed: ${e.message}")
            }
        }
    }

    private fun detectAndDisplayPineLabsDevice() {
        val isPineLabs = PineLabsManager.isPineLabsAvailable(this)
        if (isPineLabs) {
            binding.layoutPineLabsStatus.visibility = View.VISIBLE
            binding.btnManualPrint?.visibility = View.VISIBLE
            binding.tvPineLabsBadge.text = "⚡ Pine Labs POS Ready"
            Log.i(TAG, "Pine Labs POS detected: ${PineLabsManager.getDeviceStatusDescription(this)}")
        } else {
            binding.layoutPineLabsStatus.visibility = View.GONE
            Log.d(TAG, "Pine Labs not detected: ${PineLabsManager.getDeviceStatusDescription(this)}")
        }
    }

    private fun proceedToPayment() {
        if (isPaymentProcessing) {
            Log.w(TAG, "⚠️ Payment is already in progress. Ignoring duplicate trigger.")
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

        // If Pine Labs is detected on this device, offer Pine Labs App-to-App payment
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
                showCashReferenceDialog(activeItems, total)
            }
        )
    }

    private fun showPineLabsTerminalInfoDialog() {
        BillingDialogHelper.showPineLabsTerminalInfoDialog(this, authManager)
    }

    /**
     * Initiates Pine Labs app-to-app payment transaction (MethodId: 1001).
     */
    private fun initiatePineLabsPayment(
        activeItems: List<PrasadamItem>,
        total: Double,
        txnType: Long,
        modeName: String
    ) {
        if (isPaymentProcessing) {
            Log.w(TAG, "⚠️ Pine Labs payment is already processing. Ignoring duplicate click.")
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
                val orderId = orderResponse?.orderId?.toLong() ?: (System.currentTimeMillis() % 100000000)
                pendingOrderId = orderId

                Log.d(TAG, "Launching Pine Labs DoTransaction for Order #$orderId, Amount: ₹$total, Type: $txnType")
                Log.i(TAG, "🔑 [PineLabs AppId Log] BillingActivity -> passing applicationId = ${authManager.pineLabsApplicationId}")

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
                Log.e(TAG, "Error initiating Pine Labs payment: ${e.message}", e)
                Toast.makeText(this@BillingActivity, "Pine Labs error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Handles the callback received from Pine Labs MasterApp DoTransaction.
     */
    private fun handlePineLabsPaymentResult(resultCode: Int, data: Intent?) {
        isPaymentProcessing = false
        val response = PineLabsManager.parseTransactionResponse(data)
        Log.d(TAG, "Pine Labs Payment Result: isApproved=${response?.isApproved}, code=${response?.response?.responseCode}, msg=${response?.response?.responseMsg}")

        if (response != null && response.isApproved) {
            val detail = response.detail
            val approvalCode = detail?.approvalCode.orEmpty()
            val rrn = detail?.rrn.orEmpty()
            val cardNumber = detail?.cardNumber.orEmpty()
            val cardType = detail?.cardType.orEmpty()
            val invoiceNo = detail?.invoiceNumber ?: 0L
            val pspRef = if (rrn.isNotBlank()) rrn else if (approvalCode.isNotBlank()) approvalCode else detail?.plutusTransactionLogId.orEmpty()

            Toast.makeText(this, "✅ Payment Approved! (Auth: $approvalCode)", Toast.LENGTH_SHORT).show()

            // Execute Process API (api/Order/kiosk/placeOrder/{orderId}/Proccess) and navigate to print screen
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
            Log.w(TAG, "Pine Labs payment failed/cancelled: $errorMsg")
            showModernMessageDialog("Transaction Not Completed", errorMsg)
        }
    }

    private fun showModernMessageDialog(title: String, message: String) {
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
                Log.d(TAG, "⚙️ Calling Process API for Pine Labs OrderId=$orderIdInt, CompanyId=$companyId, paymentRef=$paymentRefToPass")

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
                Log.d(TAG, "⚙️ Pine Labs Process API Response: Status=${processResponse?.status}, OrderId=${processResponse?.orderId}, Message=${processResponse?.message}")

                val finalOrderId = processResponse?.orderId?.toLong() ?: pendingOrderId

                val intent = Intent(this@BillingActivity, PaymentActivity::class.java).apply {
                    putExtra(PaymentActivity.EXTRA_ORDER_ID, finalOrderId)
                    putExtra(PaymentActivity.EXTRA_PSP_REF_NO, pspRef)
                    putExtra(PaymentActivity.EXTRA_TOTAL_AMOUNT, pendingTotal)
                    putExtra(PaymentActivity.EXTRA_RECEIPT_ITEMS_JSON, pendingReceiptItemsJson)
                    putExtra(PaymentActivity.EXTRA_LANGUAGE_CODE, currentLanguage)
                    putExtra(PaymentActivity.EXTRA_PAYMENT_MODE, pendingPaymentMode)
                    putExtra(PaymentActivity.EXTRA_CARD_NUMBER, cardNumber)
                    putExtra(PaymentActivity.EXTRA_APPROVAL_CODE, approvalCode)
                    putExtra(PaymentActivity.EXTRA_INVOICE_NUMBER, invoiceNo)
                    putExtra(PaymentActivity.EXTRA_RRN, rrn)
                    putExtra(PaymentActivity.EXTRA_CARD_TYPE, cardType)
                }
                lastOrderIntent = intent
                isLastOrderPrintedSuccessfully = false

                // Clear cart & devotee inputs upon payment completion
                allItems.forEach { it.quantity = 0 }
                binding.etDevoteeMobile.text?.clear()
                productAdapter.notifyDataSetChanged()
                updateUi()

                if (isPrintEnabled()) {
                    startActivity(intent)
                } else {
                    Toast.makeText(this@BillingActivity, "Payment approved successfully (Print Disabled)", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                CommonMethod.dismissLoader()
                Log.e(TAG, "Error processing Pine Labs order: ${e.message}", e)
                Toast.makeText(this@BillingActivity, "Pine Labs process error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun proceedWithRazorpayPayment(activeItems: List<PrasadamItem>, total: Double) {
        proceedWithRazorpayQrPayment(activeItems, total)
    }



    private fun proceedWithRazorpayQrPayment(activeItems: List<PrasadamItem>, total: Double) {
        if (isPaymentProcessing) {
            Log.w(TAG, "⚠️ Razorpay QR payment is already processing. Ignoring duplicate click.")
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
            Toast.makeText(this, "Cart is empty. Please select prasadam items.", Toast.LENGTH_SHORT).show()
            return
        }

        CommonMethod.showLoader(this, "Generating Razorpay QR Code...")

        lifecycleScope.launch {
            try {
                val companyId = authManager.companyId
                val branchId = authManager.defaultBranch
                val userId = authManager.userId

                // Step 1: Fetch Payment Providers: GET /api/PaymentProvider/company/{companyId}
                val providersResult = orderRepository.getPaymentProviders(companyId)
                val providers = providersResult.getOrNull() ?: emptyList()
                Log.d("KIOSK_PAYMENT_DEBUG", "📊 Payment Providers API Response: $providers")

                // Check for ProviderId where Name == "RAZORAYQR"
                val razorpayQrProvider = providers.firstOrNull { it.name.equals("RAZORAYQR", ignoreCase = true) }
                if (razorpayQrProvider == null) {
                    CommonMethod.dismissLoader()
                    val msg = if (providers.isEmpty()) "No payment providers found for Company ID: $companyId" else "RAZORAYQR payment provider is not configured"
                    Log.w(TAG, "⚠️ $msg")
                    showModernMessageDialog("Payment Error", msg)
                    return@launch
                }
                val targetProviderId = razorpayQrProvider.providerId

                Log.d("KIOSK_PAYMENT_DEBUG", "✅ Matched Name == 'RAZORAYQR', ProviderId: $targetProviderId (Object: $razorpayQrProvider)")

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

                // Step 2: Call Summary API: POST /api/Order/kiosk/summary with paymentProviderId
                Log.d("KIOSK_PAYMENT_DEBUG", "📤 Sending Summary Request: paymentProviderId=$targetProviderId, companyId=$companyId, branchId=$branchId")
                val summaryResult = orderRepository.createKioskOrder(
                    companyId = companyId,
                    branchId = branchId,
                    customerId = 0,
                    userId = userId,
                    request = orderRequest
                )

                val summaryResponse = summaryResult.getOrNull()
                Log.d("KIOSK_PAYMENT_DEBUG", "📋 Summary API Response: Status=${summaryResponse?.status}, OrderId=${summaryResponse?.orderId}, EaseOrderId=${summaryResponse?.easeOrderId}, Data=${summaryResponse?.data}, Message=${summaryResponse?.message}")

                if (summaryResponse == null || !summaryResponse.status.equals("Success", ignoreCase = true)) {
                    CommonMethod.dismissLoader()
                    val err = summaryResponse?.message ?: summaryResult.exceptionOrNull()?.message ?: "Order summary creation failed"
                    showModernMessageDialog("Order Failed", err)
                    return@launch
                }

                val initialOrderId = summaryResponse.orderId ?: 0
                val easeOrderIdFromSummary = summaryResponse.easeOrderId ?: ""
                val qrUrlFromSummary = summaryResponse.data ?: ""

                CommonMethod.dismissLoader()

                if (initialOrderId > 0) {
                    pendingOrderId = initialOrderId.toLong()
                    pendingRazorpayOrderId = easeOrderIdFromSummary
                    pendingTotal = total
                    pendingCustomerName = ""
                    pendingCustomerMobile = devoteeMobile

                    Log.d("KIOSK_PAYMENT_DEBUG", "🚀 Opening QR Dialog: OrderId=$initialOrderId, EaseOrderId=$easeOrderIdFromSummary, QR URL=$qrUrlFromSummary")

//                    settleAndNavigateRazorpayQrOrder(initialOrderId, easeOrderIdFromSummary)

//                     Step 4: Show QR Dialog and start 3-second status polling
                    showRazorpayQrDialog(
                        orderId = initialOrderId,
                        easeOrderId = easeOrderIdFromSummary,
                        qrUrl = qrUrlFromSummary,
                        totalAmount = total
                    )
                } else {
                    showModernMessageDialog("Order Failed", "Invalid Order ID received from summary")
                }
            } catch (e: Exception) {
                CommonMethod.dismissLoader()
                Log.e(TAG, "Error in proceedWithRazorpayQrPayment: ${e.message}", e)
                Toast.makeText(this@BillingActivity, "Razorpay QR error: ${e.message}", Toast.LENGTH_SHORT).show()
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
            Log.d("KIOSK_PAYMENT_DEBUG", "🖼️ Downloading Razorpay Poster Image from URL: $qrUrl")
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
                            Log.d("KIOSK_PAYMENT_DEBUG", "✅ Poster direct image decoded! Size: ${bytes.size} bytes, Bitmap: ${posterBitmap.width}x${posterBitmap.height}")
                        } else {
                            val htmlContent = String(bytes, Charsets.UTF_8)
                            Log.d("KIOSK_PAYMENT_DEBUG", "📄 Response is HTML (${htmlContent.length} chars). Extracting image URL...")

                            val imgUrlPattern = Regex("""(?i)<meta\s+property=["']og:image["']\s+content=["']([^"']+)["']|<img[^>]+src=["']([^"']+)["']|https?://[^\s"'<>]+\.(?:png|jpg|jpeg|webp)""")
                            val match = imgUrlPattern.find(htmlContent)
                            val extractedImageUrl = match?.groups?.get(1)?.value ?: match?.groups?.get(2)?.value ?: match?.value

                            if (!extractedImageUrl.isNullOrBlank()) {
                                val finalImageUrl = if (extractedImageUrl.startsWith("http")) extractedImageUrl else if (extractedImageUrl.startsWith("//")) "https:$extractedImageUrl" else "https://rzp.io$extractedImageUrl"
                                Log.d("KIOSK_PAYMENT_DEBUG", "🎯 Extracted Image URL from HTML: $finalImageUrl")

                                val imgRequest = Request.Builder()
                                    .url(finalImageUrl)
                                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                                    .build()

                                val imgResponse = client.newCall(imgRequest).execute()
                                if (imgResponse.isSuccessful && imgResponse.body != null) {
                                    val imgBytes = imgResponse.body!!.bytes()
                                    posterBitmap = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size)
                                    Log.d("KIOSK_PAYMENT_DEBUG", "✅ Extracted poster image decoded! Size: ${imgBytes.size} bytes, Bitmap: ${posterBitmap?.width}x${posterBitmap?.height}")
                                }
                            }
                        }
                    } else {
                        Log.w("KIOSK_PAYMENT_DEBUG", "Poster HTTP response failed: code=${response.code}")
                    }
                } catch (e: Exception) {
                    Log.e("KIOSK_PAYMENT_DEBUG", "Error fetching poster image via OkHttp: ${e.message}", e)
                }

                // Fallback to local ZXing QR Bitmap if remote poster download returned null
                if (posterBitmap == null) {
                    try {
                        posterBitmap = QrCodeGenerator.generate(qrUrl, 512)
                        Log.d("KIOSK_PAYMENT_DEBUG", "Fallback: Generated local ZXing QR Bitmap")
                    } catch (e: Exception) {
                        Log.e("KIOSK_PAYMENT_DEBUG", "ZXing fallback generation failed: ${e.message}", e)
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

                    Log.d(TAG, "🔍 Polling Razorpay QR status ($pollingSeconds sec): orderId=$orderId, easeOrderId=$easeOrderId")
                    val result = orderRepository.checkRazorpayQrPaymentStatus(orderId, easeOrderId)
                    val response = result.getOrNull()
                    val statusItem = response?.data?.firstOrNull()
                    val paymentStatus = statusItem?.status?.uppercase(Locale.US) ?: response?.rootOrderStatus?.uppercase(Locale.US) ?: ""
                    val providerStatus = statusItem?.providerStatus?.uppercase(Locale.US) ?: response?.rootProviderStatus?.uppercase(Locale.US) ?: ""
                    val isFinal = statusItem?.isFinal == true || response?.rootIsFinal == true
                    val message = statusItem?.message ?: ""

                    Log.d(TAG, "📊 Razorpay QR Status: '$paymentStatus', ProviderStatus: '$providerStatus', IsFinal: $isFinal, Message: '$message'")

                    val isSuccess = paymentStatus == "CAPTURED" || paymentStatus == "PAID" || paymentStatus == "SUCCESS" || paymentStatus == "COMPLETED" || paymentStatus == "S" ||
                                    providerStatus == "CAPTURED" || providerStatus == "PAID" || providerStatus == "SUCCESS" || providerStatus == "COMPLETED" || providerStatus == "S" ||
                                    isFinal

                    val isFailed = paymentStatus == "FAILED" || paymentStatus == "EXPIRED" || paymentStatus == "CANCELLED" || paymentStatus == "F" ||
                                   providerStatus == "FAILED" || providerStatus == "EXPIRED" || providerStatus == "CANCELLED" || providerStatus == "F"

                    if (isSuccess) {
                        Log.i(TAG, "✅ Razorpay QR Payment Captured Successfully!")
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
                            Log.w(TAG, "⚠️ Razorpay QR Status reported failed/expired. Retrying check ($failedRetryCount/3)...")
                            delay(2000L)
                            pollingSeconds += 2
                            continue
                        }
                        Log.e(TAG, "❌ Razorpay QR Payment Failed after 3 retries: status=$paymentStatus, providerStatus=$providerStatus, msg=$message")
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
                    Log.w(TAG, "Network error during QR status poll (retrying next tick): ${e.message}")
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

                if (settleResult.isSuccess) {
                    Log.i(TAG, "✅ Razorpay QR payment settled successfully via placeOrder process API!")
                    delay(800L)
                    stopQrPollingAndDismiss()
                    navigateToRazorpayReceipt(orderId, rzpOrderId)
                } else {
                    stopQrPollingAndDismiss()
                    val err = settleResp?.message ?: settleResult.exceptionOrNull()?.message ?: "Order settlement failed"
                    showModernMessageDialog("Settlement Failed", err)
                }
            } catch (e: Exception) {
                stopQrPollingAndDismiss()
                Log.e(TAG, "Error in settleAndNavigateRazorpayQrOrder: ${e.message}", e)
                Toast.makeText(this@BillingActivity, "Settlement error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
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
                    Log.i(TAG, "✅ Order cancelled: OrderId=$pendingOrderId")
                } else {
                    val err = response?.message ?: result.exceptionOrNull()?.message ?: "Failed to cancel order"
                    Log.w(TAG, "Cancel order warning: $err")
                }
            } catch (e: Exception) {
                CommonMethod.dismissLoader()
                allItems.forEach { it.quantity = 0 }
                binding.etDevoteeMobile.text?.clear()
                productAdapter.notifyDataSetChanged()
                updateUi()
                Log.e(TAG, "Error cancelling order: ${e.message}", e)
            }
        }
    }

    private fun navigateToRazorpayReceipt(orderId: Int, rzpOrderId: String) {
        val activeItems = allItems.filter { it.quantity > 0 }
        val receiptItems = activeItems.mapIndexed { index, item ->
            ReceiptItem(
                no = index + 1,
                name = item.name,
                quantity = item.quantity,
                rate = item.unitPrice,
                total = item.totalAmount
            )
        }
        val receiptItemsJson = Gson().toJson(receiptItems)
        val devoteeMobile = binding.etDevoteeMobile.text?.toString()?.trim() ?: ""
        val total = activeItems.sumOf { it.totalAmount }

        val intent = Intent(this@BillingActivity, PaymentActivity::class.java).apply {
            putExtra(PaymentActivity.EXTRA_CUSTOMER_NAME, "")
            putExtra(PaymentActivity.EXTRA_CUSTOMER_MOBILE, devoteeMobile)
            putExtra(PaymentActivity.EXTRA_ORDER_ID, orderId.toLong())
            putExtra(PaymentActivity.EXTRA_PSP_REF_NO, rzpOrderId)
            putExtra(PaymentActivity.EXTRA_TOTAL_AMOUNT, total)
            putExtra(PaymentActivity.EXTRA_RECEIPT_ITEMS_JSON, receiptItemsJson)
            putExtra(PaymentActivity.EXTRA_LANGUAGE_CODE, currentLanguage)
            putExtra(PaymentActivity.EXTRA_PAYMENT_MODE, "Razorpay")
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



    private fun showCashReferenceDialog(activeItems: List<PrasadamItem>, total: Double) {
        if (!validateMobileNumberForWhatsApp()) return
        BillingDialogHelper.showCashReferenceDialog(this, authManager) { refId, adminPassword, onSuccess, onError ->
            proceedWithCashPayment(activeItems, total, refId, adminPassword, onSuccess, onError)
        }
    }


    private fun proceedWithCashPayment(
        activeItems: List<PrasadamItem>, 
        total: Double, 
        customRefId: String? = null, 
        adminPassword: String = "",
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (isPaymentProcessing) {
            Log.w(TAG, "⚠️ Cash payment is already processing. Ignoring duplicate click.")
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

                if (orderResponse != null && orderResponse.status.equals("Success", ignoreCase = true)) {
                    onSuccess() // Dismisses cash dialog ONLY when order creation succeeds!
                    val initialOrderId = orderResponse.orderId ?: (System.currentTimeMillis() % 100000000).toInt()
                    val pspRef = customRefId.takeIf { !it.isNullOrBlank() }
                        ?: orderResponse.easeOrderId
                        ?: "CASH-${System.currentTimeMillis()}"

                    // Step 2: Execute Process API for Cash Order (settled=true)
                    Log.d(TAG, "⚙️ Calling Process API for Cash OrderId=$initialOrderId")
                    val processResult = orderRepository.processOrder(
                        orderId = initialOrderId,
                        companyId = companyId,
                        customerId = 0,
                        settled = true,
                        isWhatsApp = isWhatsAppEnabled()
                    )
                    val processResponse = processResult.getOrNull()
                    val finalOrderId = processResponse?.orderId ?: initialOrderId
                    Log.d(TAG, "⚙️ Cash Process API Response: Status=${processResponse?.status}, OrderId=$finalOrderId")

                    val receiptItems = activeItems.mapIndexed { index, item ->
                        com.xeniaone.templeapp.model.ReceiptItem(
                            no = index + 1,
                            name = item.name,
                            quantity = item.quantity,
                            rate = item.unitPrice,
                            total = item.totalAmount
                        )
                    }
                    val receiptItemsJson = com.google.gson.Gson().toJson(receiptItems)

                    val intent = Intent(this@BillingActivity, PaymentActivity::class.java).apply {
                        putExtra(PaymentActivity.EXTRA_CUSTOMER_NAME, "")
                        putExtra(PaymentActivity.EXTRA_CUSTOMER_MOBILE, devoteeMobile)
                        putExtra(PaymentActivity.EXTRA_ORDER_ID, finalOrderId.toLong())
                        putExtra(PaymentActivity.EXTRA_PSP_REF_NO, pspRef)
                        putExtra(PaymentActivity.EXTRA_TOTAL_AMOUNT, total)
                        putExtra(PaymentActivity.EXTRA_RECEIPT_ITEMS_JSON, receiptItemsJson)
                        putExtra(PaymentActivity.EXTRA_LANGUAGE_CODE, currentLanguage)
                        putExtra(PaymentActivity.EXTRA_PAYMENT_MODE, "Cash Payment")
                    }
                    lastOrderIntent = intent
                    isLastOrderPrintedSuccessfully = false
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
                    val errorMsg = if (!orderResponse?.message.isNullOrBlank()) {
                        orderResponse.message
                    } else {
                        orderResult.exceptionOrNull()?.message ?: "Cash order creation failed"
                    }
                    Log.e(TAG, "Cash order creation error: $errorMsg")
                    onError(errorMsg)
                }
            } catch (e: Exception) {
                isPaymentProcessing = false
                CommonMethod.dismissLoader()
                val err = e.message ?: "Cash payment error"
                Log.e(TAG, "Error in proceedWithCashPayment: $err", e)
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
            Log.w(TAG, "Error loading employee banner image: ${e.message}")
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
                        Log.w(TAG, "Start lock task failed: ${e.message}")
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
                    Log.w(TAG, "Stop lock task failed: ${e.message}")
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
            Log.d(TAG, "HomeLauncherAlias state updated to: isEnabled=$isEnabled")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update HomeLauncherAlias state: ${e.message}")
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
                Log.w(TAG, "Failed to request overlay permission: ${e.message}")
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
                        Log.w(TAG, "Stop lock task error: ${e.message}")
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
        Log.i("KIOSK_REPRINT_LOG", "📋 [KioskReprint] Opening reprint authentication dialog...")
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
            Log.d("KIOSK_REPRINT_LOG", "🔐 [KioskReprint] Password verification attempted. isValid=$isValid")

            if (isValid) {
                dialog.dismiss()
                reprintAuthDialog = null
                val lastIntent = lastOrderIntent
                if (lastIntent != null) {
                    Log.i("KIOSK_REPRINT_LOG", "✅ [KioskReprint] Last order intent found! Preparing duplicate reprint intent.")
                    val reprintIntent = Intent(lastIntent).apply {
                        putExtra(PaymentActivity.EXTRA_IS_DUPLICATE_PRINT, true)
                    }
                    startActivity(reprintIntent)
                    Toast.makeText(this, "Reprinting last order as Duplicate", Toast.LENGTH_SHORT).show()
                } else {
                    Log.w("KIOSK_REPRINT_LOG", "⚠️ [KioskReprint] lastOrderIntent is NULL. No previous order available to reprint.")
                    Toast.makeText(this, "No previous order to print", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.e("KIOSK_REPRINT_LOG", "❌ [KioskReprint] Authentication failed: Incorrect operator password.")
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
            Log.d("KIOSK_REPRINT_LOG", "🚫 [KioskReprint] Reprint auth dialog cancelled by operator.")
            dialog.dismiss()
            reprintAuthDialog = null
        }
        btnNavAuthCancel?.setOnClickListener(cancelListener)
        btnNavAuthCancelStandard?.setOnClickListener(cancelListener)

        dialog.show()
    }
}