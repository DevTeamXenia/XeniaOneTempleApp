package com.xeniaone.templeapp.repository

import com.xeniaone.templeapp.network.AuthApiService
import com.xeniaone.templeapp.network.model.EmployeeProfileResponse
import com.xeniaone.templeapp.network.model.LoginResponse
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class AuthRepositoryImpl(
    private val authApiService: AuthApiService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AuthRepository {

    override suspend fun employeeLogin(userName: String, password: String): Resource<LoginResponse> =
        withContext(ioDispatcher) {
            try {
                val emptyBody = "".toRequestBody("text/plain".toMediaType())
                val response = authApiService.employeeLogin(userName, password, body = emptyBody)

                if (response.isSuccessful && response.body() != null) {
                    val loginResponse = response.body()!!
                    if (loginResponse.isSuccess) {
                        Resource.Success(loginResponse)
                    } else {
                        Resource.Error(loginResponse.message ?: "Login failed. Please check credentials.")
                    }
                } else {
                    val errorString = response.errorBody()?.string()
                    val errorMessage = try {
                        val parsed = Gson().fromJson(errorString, LoginResponse::class.java)
                        parsed.message ?: "Authentication failed (${response.code()})"
                    } catch (e: Exception) {
                        "Authentication failed: ${response.message().ifEmpty { "Invalid credentials" }}"
                    }
                    Resource.Error(errorMessage, response.code())
                }
            } catch (e: Exception) {
                Resource.Error(e.localizedMessage ?: "Network error. Please check your internet connection.")
            }
        }

    override suspend fun getEmployeeProfile(authToken: String): Resource<EmployeeProfileResponse> =
        withContext(ioDispatcher) {
            try {
                val authHeader = if (authToken.startsWith("Bearer ")) authToken else "Bearer $authToken"
                val response = authApiService.getEmployeeProfile(authHeader)

                if (response.isSuccessful && response.body() != null) {
                    val profileResponse = response.body()!!
                    if (profileResponse.isSuccess) {
                        Resource.Success(profileResponse)
                    } else {
                        Resource.Error(profileResponse.message ?: "Failed to fetch employee profile.")
                    }
                } else {
                    val errorMsg = "Profile request failed (${response.code()}): ${response.message()}"
                    Resource.Error(errorMsg, response.code())
                }
            } catch (e: Exception) {
                Resource.Error(e.localizedMessage ?: "Failed to retrieve employee profile.")
            }
        }
}
