package com.xeniaone.templeapp.network.model

import com.google.gson.annotations.SerializedName

data class EmployeeProfileResponse(
    @SerializedName("Status")
    val status: String? = null,
    @SerializedName("Data")
    val data: EmployeeProfileData? = null,
    @SerializedName("Message")
    val message: String? = null,
    @SerializedName("FooterMessage")
    val footerMessage: String? = null,
    @SerializedName("CompanyBrand")
    val companyBrand: String? = null
) {
    val isSuccess: Boolean
        get() = status.equals("Success", ignoreCase = true) && data != null
}

data class EmployeeProfileData(
    @SerializedName("UserId")
    val userId: Int = 0,
    @SerializedName("CompanyId")
    val companyId: Int = 11,
    @SerializedName("UserRoleId")
    val userRoleId: Int = 0,
    @SerializedName("UserName")
    val userName: String = "",
    @SerializedName("Designation")
    val designation: String = "",
    @SerializedName("Address")
    val address: String? = null,
    @SerializedName("DOB")
    val dob: String? = null,
    @SerializedName("PhoneNumber")
    val phoneNumber: String? = null,
    @SerializedName("Email")
    val email: String? = null,
    @SerializedName("Image")
    val image: String? = null,
    @SerializedName("DeviceToken")
    val deviceToken: String? = null,
    @SerializedName("Active")
    val active: Boolean = true,
    @SerializedName("DefaultBranch")
    val defaultBranch: Int = 3
)
