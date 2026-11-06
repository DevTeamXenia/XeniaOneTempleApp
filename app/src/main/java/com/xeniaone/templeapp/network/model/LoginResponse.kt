package com.xeniaone.templeapp.network.model

import com.google.gson.annotations.SerializedName

data class LoginResponse(
    @SerializedName("Status")
    val status: String? = null,

    @SerializedName("Token")
    val token: String? = null,

    @SerializedName("Message")
    val message: String? = null
) {
    val isSuccess: Boolean
        get() = status.equals("Success", ignoreCase = true) && !token.isNullOrBlank()
}
