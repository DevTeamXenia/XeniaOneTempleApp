package com.xeniaone.templeapp.util

import android.util.Base64
import com.google.gson.JsonParser

data class UserSessionInfo(
    val username: String = "",
    val employeeId: String = "",
    val designation: String = "",
    val companyId: String = ""
)

object JwtDecoder {
    fun decode(token: String?): UserSessionInfo {
        if (token.isNullOrBlank()) return UserSessionInfo()
        return try {
            val parts = token.split(".")
            if (parts.size >= 2) {
                val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP))
                val json = JsonParser.parseString(payload).asJsonObject
                UserSessionInfo(
                    username = json.get("sub")?.asString ?: "",
                    employeeId = json.get("EmployeeId")?.asString ?: "",
                    designation = json.get("Designation")?.asString ?: "",
                    companyId = json.get("CompanyId")?.asString ?: ""
                )
            } else {
                UserSessionInfo()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            UserSessionInfo()
        }
    }
}
