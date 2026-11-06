package com.xeniaone.templeapp.network.model

import com.google.gson.annotations.SerializedName

data class PaymentProviderResponse(
    @SerializedName("Status")
    val status: String?,
    @SerializedName("Data")
    val data: List<PaymentProviderItem>?
)

data class PaymentProviderItem(
    @SerializedName("ProviderId")
    val providerId: Int,
    @SerializedName("CompanyId")
    val companyId: Int,
    @SerializedName("Name")
    val name: String?,
    @SerializedName("Description")
    val description: String?
)
