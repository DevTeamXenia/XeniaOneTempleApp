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
    val orderChanel: String,

    @SerializedName("OrderMobileNumber")
    val OrderMobileNumber: String = "",

    @SerializedName("PaymentRefNo")
    val paymentRefNo: String = "",

    @SerializedName("AdminPassword")
    val adminPassword: String = "",

    @SerializedName("tableId")
    val tableId: Int = 0,

    @SerializedName("tableEncrypted")
    val tableEncrypted: String = "",

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

    @SerializedName("CounterRegisterId")
    val counterRegisterId: Int = 0,

    @SerializedName("CounterId")
    val counterId: Int = 0,

    @SerializedName("WaiterId")
    val waiterId: Int = 0,

    @SerializedName("OrderCustomerName")
    val OrderCustomerName: String="",


    @SerializedName("CustomerAddress")
    val customerAddress: CustomerAddress? = null,

    @SerializedName("CartItems")
    val cartItems: List<OrderCartItem>
)

data class CustomerAddress(
    @SerializedName("Name")
    val name: String = "",

    @SerializedName("MobileNo1")
    val mobileNo1: String = "",

    @SerializedName("MobileNo2")
    val mobileNo2: String = "",

    @SerializedName("HouseNo")
    val houseNo: String = "",

    @SerializedName("Area")
    val area: String = "",

    @SerializedName("City")
    val city: String = "",

    @SerializedName("State")
    val state: String = "",

    @SerializedName("Country")
    val country: String = "",

    @SerializedName("Landmark")
    val landmark: String = "",

    @SerializedName("Pincode")
    val pincode: String = "",

    @SerializedName("Latitude")
    val latitude: String = "",

    @SerializedName("Longitude")
    val longitude: String = "",

    @SerializedName("Default")
    val default: Boolean = true
)

data class OrderCartItem(
    @SerializedName("ProductId")
    val productId: Int,

    @SerializedName("VariantId")
    val variantId: Int = 0,

    @SerializedName("Quantity")
    val quantity: Int,

    @SerializedName("Rate")
    val rate: Double = 0.0,

    @SerializedName("Modifiers")
    val modifiers: List<OrderModifier> = emptyList()
)

data class OrderModifier(
    @SerializedName("ProductId")
    val productId: Int,

    @SerializedName("Qty")
    val qty: Int,

    @SerializedName("Type")
    val type: String = "",

    @SerializedName("Rate")
    val rate: Double = 0.0
)

data class OrderKioskSummaryResponse(
    @SerializedName("Status", alternate = ["status", "STATUS"])
    val status: String?,

    @SerializedName("OrderId", alternate = ["orderId", "order_id", "ORDERID"])
    val orderId: Int?,

    @SerializedName("EncryptedOrderId", alternate = ["encryptedOrderId", "encrypted_order_id", "EncryptedOrderNo", "encryptedOrderNo", "encrypted_order_no"])
    val encryptedOrderId: String?,

    @SerializedName("EaseOrderId", alternate = ["easeOrderId", "ease_order_id"])
    val easeOrderId: String?,

    @SerializedName("OrderNo", alternate = ["orderNo", "order_no", "OrderNumber", "orderNumber"])
    val orderNo: String?,

    @SerializedName("InvoiceNo", alternate = ["invoiceNo", "invoice_no"])
    val invoiceNo: String?,

    @SerializedName("Data", alternate = ["data", "DATA"])
    val data: String?,

    @SerializedName("Message", alternate = ["message", "MESSAGE"])
    val message: String?,

    @SerializedName("AdminPassword", alternate = ["adminPassword", "admin_password"])
    val adminPassword: String? = null
)

data class OrderDetailsResponse(
    @SerializedName("Status")
    val status: String?,

    @SerializedName("Data")
    val data: OrderDetailsData?
)

data class OrderDetailsData(
    @SerializedName("OrderId")
    val orderId: Long?,

    @SerializedName("OrderNo", alternate = ["orderNo", "order_no", "OrderNumber", "orderNumber"])
    val orderNo: String?,
    @SerializedName("PaymentRef", alternate = ["paymentRef", "PaymentReference"])
    val PaymentRef: String?,

    @SerializedName("InvoiceNo", alternate = ["invoiceNo", "invoice_no"])
    val invoiceNo: String?,

    @SerializedName("EncryptedOrderId", alternate = ["encryptedOrderId", "encrypted_order_id", "EncryptedOrderNo", "encryptedOrderNo", "encrypted_order_no"])
    val encryptedOrderId: String?,

    @SerializedName("OrderDate")
    val orderDate: String?,

    @SerializedName("OrderStatus")
    val orderStatus: String?,

    @SerializedName("PaymentMethod")
    val paymentMethod: String?,

    @SerializedName("PaymentStatus")
    val paymentStatus: String?,

    @SerializedName("PaymentOrderRef")
    val paymentOrderRef: String?,

    @SerializedName("TotalAmount")
    val totalAmount: String?,

    @SerializedName("Items")
    val items: List<OrderDetailItem>?
)

data class OrderDetailItem(
    @SerializedName("SlNo")
    val slNo: Int?,

    @SerializedName("ItemName")
    val itemName: String?,

    @SerializedName("Quantity")
    val quantity: Double?,

    @SerializedName("ItemRate")
    val itemRate: String?,

    @SerializedName("Amount")
    val amount: String?
)