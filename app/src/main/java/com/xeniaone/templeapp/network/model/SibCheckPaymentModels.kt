package com.xeniaone.templeapp.network.model

import com.google.gson.annotations.SerializedName

data class SibCheckPaymentResponse(
    @SerializedName("Status")
    val status: String?,
    @SerializedName("Data")
    val data: List<SibPaymentStatusItem>?,
    @SerializedName("Message")
    val message: String?
)

data class SibPaymentStatusItem(
    @SerializedName("Status")
    val status: String?,
    @SerializedName("OrderId")
    val orderId: Int?,
    @SerializedName("OrderNo")
    val orderNo: String?,
    @SerializedName("EaseOrderId")
    val easeOrderId: String?,
    @SerializedName("Message")
    val message: String?,
    @SerializedName("Data")
    val data: String?
)
