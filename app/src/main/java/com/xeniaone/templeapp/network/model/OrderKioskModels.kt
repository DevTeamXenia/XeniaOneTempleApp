package com.xeniaone.templeapp.network.model

import com.google.gson.annotations.SerializedName

data class OrderKioskRequest(
    @SerializedName("AddressId")
    val addressId: Int = 0,

    @SerializedName("DeliveryTypeId")
    val deliveryTypeId: Int = 0,

    @SerializedName("DeliverySlotId")
    val deliverySlotId: Int = 0,

    @SerializedName("ExpectedDeliveryDate")
    val expectedDeliveryDate: String,

    @SerializedName("Remark")
    val remark: String = "Temple Kiosk Order",

    @SerializedName("OrderChanel")
    val orderChanel: String = "KIOSK",

    @SerializedName("tableId")
    val tableId: Int = 0,

    @SerializedName("dinningId")
    val dinningId: Int = 0,

    @SerializedName("PaymentProviderId")
    val paymentProviderId: Int,

    @SerializedName("CouponCode")
    val couponCode: String = "",

    @SerializedName("isWalletAmount")
    val isWalletAmount: Boolean = false,

    @SerializedName("isWalletLoyality")
    val isWalletLoyality: Boolean = false,

    @SerializedName("CartItems")
    val cartItems: List<OrderCartItem>
)

data class OrderCartItem(
    @SerializedName("ProductId")
    val productId: Int,

    @SerializedName("VariantId")
    val variantId: Int = 0,

    @SerializedName("Quantity")
    val quantity: Int,

    @SerializedName("Rate")
    val rate: Double = 0.0
)

data class OrderKioskSummaryResponse(
    @SerializedName("Status")
    val status: String?,

    @SerializedName("OrderId")
    val orderId: Int?,

    @SerializedName("EncryptedOrderId")
    val encryptedOrderId: String?,

    @SerializedName("EaseOrderId")
    val easeOrderId: String?,

    @SerializedName("OrderNo")
    val orderNo: String?,

    @SerializedName("InvoiceNo")
    val invoiceNo: String?,

    @SerializedName("Data")
    val data: String?,

    @SerializedName("Message")
    val message: String?
)
