package com.xeniaone.templeapp

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.view.MotionEvent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.razorpay.Checkout
import com.razorpay.PaymentResultListener
import org.json.JSONObject
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.xeniaone.templeapp.adapter.ProductCardAdapter
import com.xeniaone.templeapp.model.PrasadamItem
import com.xeniaone.templeapp.model.ReceiptItem
import com.xeniaone.templeapp.network.model.OrderCartItem
import com.xeniaone.templeapp.network.model.OrderKioskRequest
import com.xeniaone.templeapp.network.model.SocketProduct
import com.xeniaone.templeapp.network.socket.CatalogueSocketManager
import com.xeniaone.templeapp.pinelabs.PineLabsConstants
import com.xeniaone.templeapp.pinelabs.PineLabsManager
import com.xeniaone.templeapp.repository.OrderRepository
import com.xeniaone.templeapp.repository.Resource
import com.xeniaone.templeapp.util.AuthManager
import com.xeniaone.templeapp.util.CommonMethod
import com.xeniaone.templeapp.util.LanguageStrings
import com.xeniaone.templeapp.R
import com.xeniaone.templeapp.databinding.ActivityBillingBinding
import com.xeniaone.templeapp.databinding.ItemBillRowBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class BillingActivity : AppCompatActivity(), PaymentResultListener {

    companion object {
        const val EXTRA_OPERATOR_NAME = "extra_operator_name"
        const val EXTRA_LANGUAGE_CODE = "extra_language_code"
        private const val TAG = "BillingActivity"
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

        // Preload Razorpay Checkout SDK
        try {
            Checkout.preload(applicationContext)
        } catch (_: Exception) {}
        val base = resources.getDimensionPixelSize(R.dimen.bottom_bar_padding_bottom)
        val minClear = resources.getDimensionPixelSize(R.dimen.bottom_bar_min_clearance)

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootBilling) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.rootBilling.setPadding(systemBars.left, 0, systemBars.right, 0)
            binding.bottomBar.updatePadding(bottom = base + maxOf(systemBars.bottom, minClear))
            insets
        }

        val app = application as TempleApp
        authManager = app.container.authManager
        socketManager = app.container.catalogueSocketManager
        orderRepository = app.container.orderRepository
        val authRepo = app.container.authRepository

        currentLanguage = intent.getStringExtra(EXTRA_LANGUAGE_CODE) ?: authManager.selectedLanguage

        // Detect Pine Labs hardware / MasterApp availability while loading
        detectAndDisplayPineLabsDevice()

        // Back button to logout or exit to login
        binding.btnBackToLanguage.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
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

        // Pay button listener
        binding.btnPay.setOnClickListener {
            proceedToPayment()
        }

        // Pine Labs status badge click listener (quick pay or device info)
        binding.layoutPineLabsStatus.setOnClickListener {
            val activeItems = allItems.filter { it.quantity > 0 }
            val total = activeItems.sumOf { it.totalAmount }
            if (activeItems.isNotEmpty() && total > 0.0) {
                showPaymentOptionDialog(activeItems, total)
            } else {
                showPineLabsTerminalInfoDialog()
            }
        }

        // Apply localization header
        applyLanguageStrings()

        // Setup RecyclerView for Dynamic Products
        setupProductRecyclerView()
        setupNavigationDrawer()

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
            val companyId = if (authManager.companyId > 0) authManager.companyId else 11
            val branchId = if (authManager.defaultBranch > 0) authManager.defaultBranch else 3
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
        productAdapter = ProductCardAdapter(allItems) { item, newQty ->
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

    private fun updateUi() {
        // Populate Table Rows
        binding.llOrderItemsContainer.removeAllViews()

        val activeItems = allItems.filter { it.quantity > 0 }

        if (activeItems.isEmpty()) {
            binding.tableCard.visibility = View.GONE
            binding.tvEmptyBillState.visibility = View.VISIBLE
        } else {
            binding.tableCard.visibility = View.VISIBLE
            binding.tvEmptyBillState.visibility = View.GONE
            val inflater = LayoutInflater.from(this)

            activeItems.forEachIndexed { index, item ->
                val rowBinding = ItemBillRowBinding.inflate(inflater, binding.llOrderItemsContainer, false)

                // Format row values: "01", "02", etc.
                rowBinding.tvRowIndex.text = "%02d".format(index + 1)
                rowBinding.tvRowItemName.text = item.name
                rowBinding.tvRowQty.text = item.quantity.toString()
                rowBinding.tvRowPrice.text = "%.2f".format(item.unitPrice)
                rowBinding.tvRowTotal.text = item.formattedTotal

                // Remove button resets quantity of this item to 0
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
        detectAndDisplayPineLabsDevice()
    }

    private fun detectAndDisplayPineLabsDevice() {
        val isPineLabs = PineLabsManager.isPineLabsAvailable(this)
        if (isPineLabs) {
            binding.layoutPineLabsStatus.visibility = View.VISIBLE
            binding.tvPineLabsBadge.text = "⚡ Pine Labs POS Ready"
            Log.i(TAG, "Pine Labs POS detected: ${PineLabsManager.getDeviceStatusDescription(this)}")
        } else {
            binding.layoutPineLabsStatus.visibility = View.GONE
            Log.d(TAG, "Pine Labs not detected: ${PineLabsManager.getDeviceStatusDescription(this)}")
        }
    }

    private fun proceedToPayment() {
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

    /**
     * Shows a modern themed payment selection dialog matching the app's gold/cream theme.
     */
    private fun showPaymentOptionDialog(activeItems: List<PrasadamItem>, total: Double) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pinelabs_payment_mode, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        // Transparent background so the rounded card corners and soft elevation display cleanly
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvDialogTotalAmount = dialogView.findViewById<TextView>(R.id.tvDialogTotalAmount)
        val btnCloseDialog = dialogView.findViewById<View>(R.id.btnCloseDialog)
        val btnOptionCard = dialogView.findViewById<View>(R.id.btnOptionCard)
        val btnOptionUpi = dialogView.findViewById<View>(R.id.btnOptionUpi)
        val btnOptionCash = dialogView.findViewById<View>(R.id.btnOptionCash)
        val btnOptionSibQr = dialogView.findViewById<View>(R.id.btnOptionSibQr)

        val isPineLabs = PineLabsManager.isPineLabsAvailable(this)
        if (isPineLabs) {
            btnOptionSibQr.visibility = View.GONE
        }

        tvDialogTotalAmount.text = "₹%.2f".format(Locale.US, total)

        btnCloseDialog.setOnClickListener {
            dialog.dismiss()
        }

        btnOptionCard.setOnClickListener {
            dialog.dismiss()
            initiatePineLabsPayment(
                activeItems = activeItems,
                total = total,
                txnType = PineLabsConstants.TXN_TYPE_SALE,
                modeName = "Pine Labs Card"
            )
        }

        btnOptionUpi.setOnClickListener {
            dialog.dismiss()
            initiatePineLabsPayment(
                activeItems = activeItems,
                total = total,
                txnType = PineLabsConstants.TXN_TYPE_UPI_SALE,
                modeName = "Pine Labs UPI"
            )
        }

        btnOptionCash.setOnClickListener {
            dialog.dismiss()
            showCashReferenceDialog(activeItems, total)
        }

        btnOptionSibQr.setOnClickListener {
            dialog.dismiss()
            proceedWithRazorpayPayment(activeItems, total)
        }

        dialog.show()
    }

    /**
     * Shows modern Pine Labs device information dialog when clicking the status badge.
     */
    private fun showPineLabsTerminalInfoDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pinelabs_terminal_info, null)
        val dialog = AlertDialog.Builder(this)
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

    /**
     * Initiates Pine Labs app-to-app payment transaction (MethodId: 1001).
     */
    private fun initiatePineLabsPayment(
        activeItems: List<PrasadamItem>,
        total: Double,
        txnType: Long,
        modeName: String
    ) {
        if (!validateMobileNumberForWhatsApp()) return
        val cartItems = activeItems.map { item ->
            OrderCartItem(
                productId = item.id.toIntOrNull() ?: 9543,
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
                val companyId = if (authManager.companyId > 0) authManager.companyId else 40
                val branchId = if (authManager.defaultBranch > 0) authManager.defaultBranch else 85
                val userId = if (authManager.userId > 0) authManager.userId else 23

                val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date())

                val orderRequest = OrderKioskRequest(
                    addressId = 0,
                    deliveryTypeId = 0,
                    deliverySlotId = 0,
                    expectedDeliveryDate = nowIso,
                    remark = "$modeName Transaction",
                    orderChanel = "KIOSK",
                    tableId = 0,
                    dinningId = 0,
                    paymentProviderId = 11,
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

            // Navigate to Receipt and Thermal Print screen
            val intent = Intent(this@BillingActivity, PaymentActivity::class.java).apply {
                putExtra(PaymentActivity.EXTRA_ORDER_ID, pendingOrderId)
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
            if (isPrintEnabled()) {
                startActivity(intent)
            } else {
                Toast.makeText(this, "Payment approved successfully (Print Disabled)", Toast.LENGTH_LONG).show()
                allItems.forEach { it.quantity = 0 }
                binding.etDevoteeMobile.text?.clear()
                productAdapter.notifyDataSetChanged()
                updateUi()
            }
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

    private fun proceedWithRazorpayPayment(activeItems: List<PrasadamItem>, total: Double) {
        val cartItems = activeItems.map { item ->
            OrderCartItem(
                productId = item.id.toIntOrNull() ?: 9543,
                variantId = 0,
                quantity = item.quantity,
                rate = item.unitPrice
            )
        }

        if (cartItems.isEmpty()) {
            Toast.makeText(this, "Cart is empty. Please select prasadam items.", Toast.LENGTH_SHORT).show()
            return
        }

        CommonMethod.showLoader(this, "Connecting to Razorpay Gateway...")

        lifecycleScope.launch {
            try {
                val companyId = if (authManager.companyId > 0) authManager.companyId else 40
                val branchId = if (authManager.defaultBranch > 0) authManager.defaultBranch else 85
                val userId = if (authManager.userId > 0) authManager.userId else 23

                // Step 1: Fetch Payment Providers: GET /api/PaymentProvider/company/{companyId}
                val providersResult = orderRepository.getPaymentProviders(companyId)
                val providers = providersResult.getOrNull() ?: emptyList()
                val razorpayProvider = providers.firstOrNull { it.name.equals("RAZORPAY", ignoreCase = true) }
                val providerId = razorpayProvider?.providerId ?: 10

                Log.d(TAG, "Selected Razorpay ProviderId: $providerId")

                val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date())

                val devoteeMobile = binding.etDevoteeMobile.text?.toString()?.trim() ?: ""

                val orderRequest = OrderKioskRequest(
                    addressId = 0,
                    deliveryTypeId = 0,
                    deliverySlotId = 0,
                    OrderMobileNumber = devoteeMobile,
                    OrderCustomerName = "",
                    expectedDeliveryDate = nowIso,
                    remark = "Razorpay Online Order",
                    orderChanel = "KIOSK",
                    tableId = 0,
                    dinningId = 7,
                    paymentProviderId = providerId,
                    couponCode = "",
                    isWalletAmount = false,
                    isWalletLoyality = false,
                    cartItems = cartItems
                )

                // Step 2: Call Summary API: POST /api/Order/kiosk/summary
                val summaryResult = orderRepository.createKioskOrder(
                    companyId = companyId,
                    branchId = branchId,
                    customerId = 0,
                    userId = userId,
                    request = orderRequest
                )

                val summaryResponse = summaryResult.getOrNull()
                if (summaryResponse == null || !summaryResponse.status.equals("Success", ignoreCase = true)) {
                    CommonMethod.dismissLoader()
                    val err = summaryResponse?.message ?: summaryResult.exceptionOrNull()?.message ?: "Order summary creation failed"
                    showModernMessageDialog("Order Failed", err)
                    return@launch
                }

                val initialOrderId = summaryResponse.orderId ?: 0
                val rzpOrderIdFromSummary = summaryResponse.data ?: ""

                Log.d(TAG, "Summary order created: OrderId=$initialOrderId, RazorpayOrderId=$rzpOrderIdFromSummary")

                // Step 3: Call Process API with settled=false: POST /api/Order/kiosk/placeOrder/{orderId}/Proccess?companyId=40&customerId=0&settled=false
                val processResult = orderRepository.processOrder(
                    orderId = initialOrderId,
                    companyId = companyId,
                    customerId = 0,
                    settled = false,
                    isWhatsApp = isWhatsAppEnabled()
                )

                CommonMethod.dismissLoader()
                val processResponse = processResult.getOrNull()

                if (processResponse != null && (processResponse.status.equals("Success", ignoreCase = true) || processResponse.status.equals("CREATED", ignoreCase = true))) {
                    pendingOrderId = processResponse.orderId?.toLong() ?: initialOrderId.toLong()
                    pendingRazorpayOrderId = processResponse.data ?: rzpOrderIdFromSummary
                    pendingTotal = total
                    pendingCustomerName = ""
                    pendingCustomerMobile = devoteeMobile

                    Log.d(TAG, "Process order (settled=false) success: OrderId=$pendingOrderId, RzpOrderId=$pendingRazorpayOrderId")

                    // Step 4: Open Razorpay Checkout SDK
                    startRazorpayPayment(
                        orderId = pendingOrderId.toInt(),
                        razorpayOrderId = pendingRazorpayOrderId,
                        totalAmount = total,
                        customerName = "",
                        customerMobile = devoteeMobile
                    )
                } else {
                    val err = processResponse?.message ?: processResult.exceptionOrNull()?.message ?: "Order processing failed"
                    showModernMessageDialog("Order Failed", err)
                }
            } catch (e: Exception) {
                CommonMethod.dismissLoader()
                Log.e(TAG, "Error in proceedWithRazorpayPayment: ${e.message}", e)
                Toast.makeText(this@BillingActivity, "Razorpay error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startRazorpayPayment(orderId: Int, razorpayOrderId: String, totalAmount: Double, customerName: String, customerMobile: String) {
        try {
            val checkout = Checkout()
            val rzpKey = getString(R.string.razorpay_api_key)
            if (rzpKey.isNotBlank()) {
                checkout.setKeyID(rzpKey)
            }

            val options = JSONObject().apply {
                put("name", "Travancore Devaswom Board")
                put("description", "Prasadam Order #$orderId")
                put("currency", "INR")
                put("amount", (totalAmount * 100).toLong())
                put("order_id", razorpayOrderId)
                put("prefill", JSONObject().apply {
                    put("name", customerName)
                    put("contact", customerMobile)
                })
            }
            checkout.open(this, options)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening Razorpay checkout SDK: ${e.message}", e)
            Toast.makeText(this, "Error opening Razorpay gateway: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onPaymentSuccess(razorpayPaymentID: String?) {
        Log.i(TAG, "✅ Razorpay payment success callback: paymentId=$razorpayPaymentID")
        pollRazorpayPaymentStatusAndSettle(pendingOrderId.toInt(), pendingRazorpayOrderId)
    }

    override fun onPaymentError(errorCode: Int, response: String?) {
        Log.e(TAG, "❌ Razorpay payment error callback: code=$errorCode, response=$response")
        showRazorpayPendingDialog()
    }

    private fun pollRazorpayPaymentStatusAndSettle(orderId: Int, razorpayOrderId: String) {
        CommonMethod.showLoader(this, "Verifying Payment Status...")

        lifecycleScope.launch {
            try {
                val companyId = if (authManager.companyId > 0) authManager.companyId else 40
                var isCaptured = false
                var attempts = 0

                while (!isCaptured && attempts < 15 && !isDestroyed && !isFinishing) {
                    attempts++
                    val statusResult = orderRepository.checkRazorpayPaymentStatus(orderId, razorpayOrderId)
                    val statusResp = statusResult.getOrNull()
                    val statusItem = statusResp?.data?.firstOrNull()
                    val payStatus = statusItem?.status?.uppercase(Locale.US) ?: ""

                    Log.d(TAG, "Razorpay payment poll attempt #$attempts: status=$payStatus")

                    if (payStatus == "CAPTURED" || payStatus == "SUCCESS") {
                        isCaptured = true
                        break
                    }
                    delay(3000L)
                }

                if (isCaptured) {
                    val settleResult = orderRepository.processOrder(
                        orderId = orderId,
                        companyId = companyId,
                        customerId = 0,
                        settled = true,
                        isWhatsApp = isWhatsAppEnabled()
                    )
                    CommonMethod.dismissLoader()
                    val settleResp = settleResult.getOrNull()

                    if (settleResult.isSuccess) {
                        Log.i(TAG, "✅ Razorpay payment settled successfully!")
                        navigateToRazorpayReceipt(orderId, pendingRazorpayOrderId)
                    } else {
                        val err = settleResp?.message ?: settleResult.exceptionOrNull()?.message ?: "Order settlement failed"
                        showModernMessageDialog("Settlement Failed", err)
                    }
                } else {
                    CommonMethod.dismissLoader()
                    showRazorpayPendingDialog()
                }
            } catch (e: Exception) {
                CommonMethod.dismissLoader()
                Log.e(TAG, "Error in pollRazorpayPaymentStatusAndSettle: ${e.message}", e)
                Toast.makeText(this@BillingActivity, "Verification error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
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
                startRazorpayPayment(
                    orderId = pendingOrderId.toInt(),
                    razorpayOrderId = pendingRazorpayOrderId,
                    totalAmount = pendingTotal,
                    customerName = pendingCustomerName,
                    customerMobile = pendingCustomerMobile
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
        if (pendingOrderId <= 0L) return
        CommonMethod.showLoader(this, "Cancelling Order...")
        lifecycleScope.launch {
            try {
                val companyId = if (authManager.companyId > 0) authManager.companyId else 40
                val result = orderRepository.cancelOrder(pendingOrderId.toInt(), companyId, settled = false)
                CommonMethod.dismissLoader()
                val response = result.getOrNull()
                if (result.isSuccess) {
                    Toast.makeText(this@BillingActivity, "Order cancelled successfully (${response?.status ?: "NOT_INITIATED"})", Toast.LENGTH_SHORT).show()
                    Log.i(TAG, "✅ Order cancelled: OrderId=$pendingOrderId, Status=${response?.status}")

                    // Clear cart & devotee inputs
                    allItems.forEach { it.quantity = 0 }
                    binding.etDevoteeMobile.text?.clear()
                    productAdapter.notifyDataSetChanged()
                    updateUi()
                } else {
                    val err = response?.message ?: result.exceptionOrNull()?.message ?: "Failed to cancel order"
                    Toast.makeText(this@BillingActivity, err, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                CommonMethod.dismissLoader()
                Log.e(TAG, "Error cancelling order: ${e.message}", e)
                Toast.makeText(this@BillingActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
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
        if (isPrintEnabled()) {
            startActivity(intent)
        } else {
            Toast.makeText(this, "Razorpay payment successful (Print Disabled)", Toast.LENGTH_LONG).show()
        }
        allItems.forEach { it.quantity = 0 }
        binding.etDevoteeMobile.text?.clear()
        productAdapter.notifyDataSetChanged()
        updateUi()
    }



    private fun showCashReferenceDialog(activeItems: List<PrasadamItem>, total: Double) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_cash_reference, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        val etCashRefId = dialogView.findViewById<EditText>(R.id.etCashRefId)
        val btnCashContinue = dialogView.findViewById<View>(R.id.btnCashContinue)

        etCashRefId.setText("")
        etCashRefId.hint = "Enter Payment Reference ID"
        etCashRefId.requestFocus()

        dialog.setOnShowListener {
            etCashRefId.post {
                etCashRefId.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(etCashRefId, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }

        btnCashContinue.setOnClickListener {
            if (!validateMobileNumberForWhatsApp()) return@setOnClickListener
            val refId = etCashRefId.text?.toString()?.trim() ?: ""
            if (refId.isEmpty()) {
                Toast.makeText(this, "Please enter Payment Reference ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            proceedWithCashPayment(activeItems, total, refId)
        }

        dialog.show()
    }

    private fun proceedWithCashPayment(activeItems: List<PrasadamItem>, total: Double, customRefId: String? = null) {
        val cartItems = activeItems.map { item ->
            OrderCartItem(
                productId = item.id.toIntOrNull() ?: 9543,
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
                val companyId = if (authManager.companyId > 0) authManager.companyId else 40
                val branchId = if (authManager.defaultBranch > 0) authManager.defaultBranch else 85
                val userId = if (authManager.userId > 0) authManager.userId else 23

                val devoteeMobile = binding.etDevoteeMobile.text?.toString()?.trim() ?: ""

                val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date())

                val orderRequest = OrderKioskRequest(
                    addressId = 0,
                    deliveryTypeId = 0,
                    deliverySlotId = 0,
                    OrderMobileNumber = devoteeMobile,
                    OrderCustomerName = "",
                    expectedDeliveryDate = nowIso,
                    remark = "Counter Cash Order",
                    orderChanel = "KIOSK",
                    tableId = 0,
                    dinningId = 0,
                    paymentProviderId = 1,
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
                    val orderId = orderResponse.orderId ?: System.currentTimeMillis()
                    val pspRef = customRefId.takeIf { !it.isNullOrBlank() }
                        ?: orderResponse.easeOrderId
                        ?: "CASH-${System.currentTimeMillis()}"

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
                        putExtra(PaymentActivity.EXTRA_ORDER_ID, orderId.toLong())
                        putExtra(PaymentActivity.EXTRA_PSP_REF_NO, pspRef)
                        putExtra(PaymentActivity.EXTRA_TOTAL_AMOUNT, total)
                        putExtra(PaymentActivity.EXTRA_RECEIPT_ITEMS_JSON, receiptItemsJson)
                        putExtra(PaymentActivity.EXTRA_LANGUAGE_CODE, currentLanguage)
                        putExtra(PaymentActivity.EXTRA_PAYMENT_MODE, "Cash on Delivery")
                    }
                    if (isPrintEnabled()) {
                        startActivity(intent)
                    } else {
                        Toast.makeText(this@BillingActivity, "Cash order completed successfully (Print Disabled)", Toast.LENGTH_LONG).show()
                    }
                    allItems.forEach { item -> item.quantity = 0 }
                    binding.etDevoteeMobile.text?.clear()
                    productAdapter.notifyDataSetChanged()
                    updateUi()
                } else {
                    val errorMsg = if (!orderResponse?.message.isNullOrBlank()) {
                        orderResponse.message
                    } else {
                        orderResult.exceptionOrNull()?.message ?: "Cash order creation failed"
                    }
                    Log.e(TAG, "Cash order creation error: $errorMsg")
                    showModernMessageDialog("Order Failed", errorMsg)
                    Toast.makeText(this@BillingActivity, errorMsg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                CommonMethod.dismissLoader()
                Toast.makeText(this@BillingActivity, "Cash payment error: ${e.message}", Toast.LENGTH_SHORT).show()
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

        drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)

        drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                if (!isDrawerAuthenticated && slideOffset > 0.01f) {
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
                drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
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

        menuLogout.setOnClickListener {
            drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Logout")
                .setMessage("Are you sure you want to log out?")
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
                return false
            } else {
                binding.tilDevoteeMobile.error = null
            }
        } else {
            binding.tilDevoteeMobile.error = null
        }
        return true
    }

    private var drawerAutoCloseTimer: CountDownTimer? = null

    private fun startDrawerAutoCloseTimer(drawerLayout: androidx.drawerlayout.widget.DrawerLayout) {
        drawerAutoCloseTimer?.cancel()
        drawerAutoCloseTimer = object : CountDownTimer(10000L, 1000L) {
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

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_nav_auth, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvNavAuthMessage = dialogView.findViewById<TextView>(R.id.tvNavAuthMessage)
        val etNavPassword = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etNavPassword)
        val btnNavAuthVerify = dialogView.findViewById<View>(R.id.btnNavAuthVerify)
        val btnNavAuthCancel = dialogView.findViewById<View>(R.id.btnNavAuthCancel)

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
                val drawerLayout = findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawerLayout)
                drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED)
                drawerLayout.openDrawer(androidx.core.view.GravityCompat.START)
            } else {
                Toast.makeText(this, "Incorrect password. Enter your login password.", Toast.LENGTH_SHORT).show()
            }
        }

        btnNavAuthCancel?.setOnClickListener {
            drawerTimeoutTimer?.cancel()
            isPromptShowing = false
            lastPromptDismissTime = System.currentTimeMillis()
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            drawerTimeoutTimer?.cancel()
            isPromptShowing = false
            lastPromptDismissTime = System.currentTimeMillis()
            onDismissCallback()
        }

        dialog.show()

        // 10-second countdown timer
        drawerTimeoutTimer = object : CountDownTimer(10000L, 1000L) {
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
                    val drawerLayout = findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawerLayout)
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
        drawerTimeoutTimer?.cancel()
        drawerAutoCloseTimer?.cancel()
        CommonMethod.dismissLoader()
    }
}
