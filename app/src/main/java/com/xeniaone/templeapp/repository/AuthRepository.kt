package com.xeniaone.templeapp.repository

import com.xeniaone.templeapp.network.model.EmployeeProfileResponse
import com.xeniaone.templeapp.network.model.LoginResponse

sealed class Resource<out T> {
    data class Success<out T>(val data: T) : Resource<T>()
    data class Error(val message: String, val statusCode: Int? = null) : Resource<Nothing>()
    object Loading : Resource<Nothing>()
}

interface AuthRepository {
    suspend fun employeeLogin(userName: String, password: String): Resource<LoginResponse>
    suspend fun getEmployeeProfile(authToken: String): Resource<EmployeeProfileResponse>
}
