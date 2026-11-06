package com.xeniaone.templeapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xeniaone.templeapp.adapter.ProductCardAdapter
import com.xeniaone.templeapp.model.PrasadamItem
import com.xeniaone.templeapp.network.model.OrderCartItem
import com.xeniaone.templeapp.network.model.OrderKioskRequest
import com.xeniaone.templeapp.network.model.SocketProduct
import com.xeniaone.templeapp.network.socket.CatalogueSocketManager
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

class BillingActivity : AppCompatActivity() {

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityBillingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootBilling) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.rootBilling.setPadding(systemBars.left, 0, systemBars.right, 0)
            binding.bottomBar.setPadding(
                binding.bottomBar.paddingLeft,
                binding.bottomBar.paddingTop,
                binding.bottomBar.paddingRight,
                systemBars.bottom.coerceAtLeast(12)
            )
            insets
        }

        val app = application as TempleApp
        authManager = app.container.authManager
        socketManager = app.container.catalogueSocketManager
        orderRepository = app.container.orderRepository
        val authRepo = app.container.authRepository

        currentLanguage = intent.getStringExtra(EXTRA_LANGUAGE_CODE) ?: authManager.selectedLanguage

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

        // Apply localization header
        applyLanguageStrings()

        // Setup RecyclerView for Dynamic Products
        setupProductRecyclerView()

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
                            designation = d.designation
                        )
                        Log.i(TAG, "Profile refreshed: CompanyId=${d.companyId}, DefaultBranch=${d.defaultBranch}")
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
                        Log.i(TAG, "Collected ${socketProducts.size} products from SocketFlow")
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

    private fun setupProductRecyclerView() {
        productAdapter = ProductCardAdapter(allItems) { item, newQty ->
            updateUi()
        }
        binding.rvProducts.layoutManager = LinearLayoutManager(
            this,
            RecyclerView.HORIZONTAL,
            false
        )
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

        // Standard Prasadam items with ZERO dummy quantity
        allItems.clear()
        allItems.add(PrasadamItem("9543", LanguageStrings.getItemAppam(currentLanguage), 45.00, R.drawable.item_appam, null, 0))
        allItems.add(PrasadamItem("9541", LanguageStrings.getItemAravana(currentLanguage), 100.00, R.drawable.item_aravana, null, 0))
        allItems.add(PrasadamItem("9546", LanguageStrings.getItemCombo(currentLanguage), 125.00, R.drawable.item_combo, null, 0))

        productAdapter.updateItems(allItems)
        updateUi()
    }

    private fun bindSocketProducts(socketProducts: List<SocketProduct>) {
        runOnUiThread {
            // Keep track of any quantity the user might have already selected
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
                        quantity = currentQty // Starts at 0
                    )
                )
            }

            allItems.clear()
            allItems.addAll(newItems)
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

    private fun updateUi() {
        // Populate Table Rows
        binding.llOrderItemsContainer.removeAllViews()

        val activeItems = allItems.filter { it.quantity > 0 }

        if (activeItems.isEmpty()) {
            binding.tvEmptyBillState.visibility = View.VISIBLE
        } else {
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
        binding.btnPay.isEnabled = activeItems.isNotEmpty()
        binding.btnPay.alpha = if (activeItems.isNotEmpty()) 1.0f else 0.6f
    }

    private fun proceedToPayment() {
        val activeItems = allItems.filter { it.quantity > 0 }
        val total = activeItems.sumOf { it.totalAmount }
        if (total <= 0.0 || activeItems.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_items_selected), Toast.LENGTH_SHORT).show()
            return
        }

        // Map active items to OrderCartItem list
        val cartItems = activeItems.map { item ->
            OrderCartItem(
                productId = item.id.toIntOrNull() ?: 9543,
                variantId = 0,
                quantity = item.quantity,
                rate = item.unitPrice
            )
        }

        // Guard: API requires CartItems not to be empty or null
        if (cartItems.isEmpty()) {
            Toast.makeText(this, "Cart is empty. Please select prasadam items.", Toast.LENGTH_SHORT).show()
            return
        }

        // Show transparent dialog loader while calling APIs
        CommonMethod.showLoader(this, "Connecting to SIB Payment Gateway...")

        lifecycleScope.launch {
            try {
                val companyId = if (authManager.companyId > 0) authManager.companyId else 40
                val branchId = if (authManager.defaultBranch > 0) authManager.defaultBranch else 85
                val userId = if (authManager.userId > 0) authManager.userId else 23

                Log.d(TAG, "Initiating payment for Company: $companyId, Branch: $branchId, User: $userId, Total: ₹$total")

                // Step 1: Fetch Payment Providers: GET /api/PaymentProvider/company/{companyId}
                val providersResult = orderRepository.getPaymentProviders(companyId)
                val providers = providersResult.getOrNull() ?: emptyList()

                // Step 2: Check for SIB, take its ProviderId
                val sibProvider = providers.firstOrNull { it.name.equals("SIB", ignoreCase = true) }
                val providerId = sibProvider?.providerId ?: 11

                Log.d(TAG, "Selected PaymentProviderId: $providerId (SIB found: ${sibProvider != null})")

                val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date())

                val orderRequest = OrderKioskRequest(
                    addressId = 0,
                    deliveryTypeId = 0,
                    deliverySlotId = 0,
                    expectedDeliveryDate = nowIso,
                    remark = "Temple Kiosk Order",
                    orderChanel = "KIOSK",
                    tableId = 0,
                    dinningId = 0,
                    paymentProviderId = providerId,
                    couponCode = "",
                    isWalletAmount = false,
                    isWalletLoyality = false,
                    cartItems = cartItems
                )

                val jsonPayload = com.google.gson.Gson().toJson(orderRequest)
                Log.d("KIOSK_PAYMENT_DEBUG", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.d("KIOSK_PAYMENT_DEBUG", "🚀 POST /api/Order/kiosk/summary?companyId=$companyId&branchId=$branchId&customerId=0&userId=$userId")
                Log.d("KIOSK_PAYMENT_DEBUG", "📦 REQUEST BODY:\n$jsonPayload")
                Log.d("KIOSK_PAYMENT_DEBUG", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                // Step 4: Call Kiosk Summary API: POST /api/Order/kiosk/summary
                val orderResult = orderRepository.createKioskOrder(
                    companyId = companyId,
                    branchId = branchId,
                    customerId = 0,
                    userId = userId,
                    request = orderRequest
                )

                CommonMethod.dismissLoader()

                val orderResponse = orderResult.getOrNull()
                Log.d("KIOSK_PAYMENT_DEBUG", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.d("KIOSK_PAYMENT_DEBUG", "📥 RESPONSE STATUS: ${orderResponse?.status}")
                Log.d("KIOSK_PAYMENT_DEBUG", "📋 ORDER ID: ${orderResponse?.orderId}")
                Log.d("KIOSK_PAYMENT_DEBUG", "🔗 URL (DATA): ${orderResponse?.data}")
                Log.d("KIOSK_PAYMENT_DEBUG", "💬 MESSAGE: ${orderResponse?.message}")
                Log.d("KIOSK_PAYMENT_DEBUG", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                if (orderResponse != null && orderResponse.status.equals("Success", ignoreCase = true)) {
                    val qrUrl = if (!orderResponse.data.isNullOrBlank()) {
                        Log.d("KIOSK_PAYMENT_DEBUG", "✅ Live SIB bank URL received: ${orderResponse.data}")
                        orderResponse.data
                    } else {
                        Log.w("KIOSK_PAYMENT_DEBUG", "⚠️ SIB returned empty data/URL. Using UPI fallback.")
                        // UPI payment fallback with live order ID
                        String.format(
                            Locale.US,
                            "upi://pay?pa=xeniatechnologies@okhdfcbank&pn=Temple%%20Prasadam%%20Counter&am=%.2f&cu=INR&tn=Order%%20%s",
                            total,
                            orderResponse.orderId?.toString() ?: "Prasadam"
                        )
                    }

                    Log.d(TAG, "Order #${orderResponse.orderId} created successfully. QR URL: $qrUrl")

                    // Build receipt items list for print & receipt display
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

                    val intent = Intent(this@BillingActivity, PaymentQrActivity::class.java).apply {
                        putExtra(PaymentQrActivity.EXTRA_TOTAL_AMOUNT, total)
                        putExtra(PaymentQrActivity.EXTRA_QR_URL, qrUrl)
                        putExtra(PaymentQrActivity.EXTRA_ORDER_ID, orderResponse.orderId ?: 0)
                        putExtra(PaymentQrActivity.EXTRA_PSP_REF_NO, orderResponse.easeOrderId ?: "")
                        putExtra(PaymentQrActivity.EXTRA_RECEIPT_ITEMS_JSON, receiptItemsJson)
                        putExtra(EXTRA_LANGUAGE_CODE, currentLanguage)
                    }
                    startActivity(intent)
                } else {
                    val errorMsg = orderResponse?.message ?: orderResult.exceptionOrNull()?.message ?: "Order creation failed"
                    Log.e(TAG, "Order creation error: $errorMsg")
                    Toast.makeText(this@BillingActivity, errorMsg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                CommonMethod.dismissLoader()
                Log.e(TAG, "Error in proceedToPayment: ${e.message}", e)
                Toast.makeText(this@BillingActivity, "Payment error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        CommonMethod.dismissLoader()
    }
}
