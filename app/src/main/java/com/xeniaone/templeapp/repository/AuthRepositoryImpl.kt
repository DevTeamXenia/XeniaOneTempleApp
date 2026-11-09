package com.xeniaone.templeapp.repository

import android.content.ContentValues.TAG
import android.util.Log
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
            Log.d(TAG, "getEmployeeProfile: request started")
            try {
                val authHeader = if (authToken.startsWith("Bearer ")) authToken else "Bearer $authToken"
                Log.d(TAG, "getEmployeeProfile: auth header set (token length=${authToken.length})")

                val response = authApiService.getEmployeeProfile(authHeader)
                Log.d(TAG, "getEmployeeProfile: response received, code=${response.code()}, successful=${response.isSuccessful}")

                if (response.isSuccessful && response.body() != null) {
                    val profileResponse = response.body()!!
                    if (profileResponse.isSuccess) {
                        Log.i(TAG, "getEmployeeProfile: profile fetched successfully")
                        Log.d(TAG, "getEmployeeProfile: full response = ${Gson().toJson(profileResponse)}")
                        Resource.Success(profileResponse)
                    } else {
                        Log.w(TAG, "getEmployeeProfile: API returned failure, message=${profileResponse.message}")
                        Resource.Error(profileResponse.message ?: "Failed to fetch employee profile.")
                    }
                } else {
                    val errorMsg = "Profile request failed (${response.code()}): ${response.message()}"
                    Log.e(TAG, "getEmployeeProfile: $errorMsg, bodyNull=${response.body() == null}")
                    Resource.Error(errorMsg, response.code())
                }
            } catch (e: Exception) {
                Log.e(TAG, "getEmployeeProfile: exception - ${e.localizedMessage}", e)
                Resource.Error(e.localizedMessage ?: "Failed to retrieve employee profile.")
            }
        }
}
