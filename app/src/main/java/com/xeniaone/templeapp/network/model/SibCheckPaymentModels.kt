package com.xeniaone.templeapp.network.model

import com.google.gson.annotations.SerializedName

data class SibCheckPaymentResponse(
    @SerializedName("Status")
    val status: String?,
    @SerializedName("Data")
    val data: List<SibPaymentStatusItem>?,
    @SerializedName("Message")
    val message: String?,

    @SerializedName("OrderId")
    val rootOrderId: Int?,
    @SerializedName("ProviderStatus")
    val rootProviderStatus: String?,
    @SerializedName("OrderStatus")
    val rootOrderStatus: String?,
    @SerializedName("IsFinal")
    val rootIsFinal: Boolean?,
    @SerializedName("PaymentId")
    val rootPaymentId: String?
)

data class SibPaymentStatusItem(
    @SerializedName("Status")
    val status: String?,
    @SerializedName("ProviderStatus")
    val providerStatus: String?,
    @SerializedName("OrderId")
    val orderId: Int?,
    @SerializedName("OrderNo")
    val orderNo: String?,
    @SerializedName("EaseOrderId")
    val easeOrderId: String?,
    @SerializedName("Message")
    val message: String?,
    @SerializedName("Data")
    val data: String?,
    @SerializedName("IsFinal")
    val isFinal: Boolean?,
    @SerializedName("PaymentId")
    val paymentId: String?
)
