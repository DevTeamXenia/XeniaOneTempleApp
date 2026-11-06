package com.xeniaone.templeapp.network

import com.xeniaone.templeapp.network.model.EmployeeProfileResponse
import com.xeniaone.templeapp.network.model.LoginResponse
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface AuthApiService {

    @POST("api/Auth/employee/login")
    suspend fun employeeLogin(
        @Query("userName") userName: String,
        @Query("password") password: String,
        @Header("accept") accept: String = "*/*",
        @Body body: RequestBody? = null
    ): Response<LoginResponse>

    @GET("api/Employee/profile")
    suspend fun getEmployeeProfile(
        @Header("Authorization") authHeader: String,
        @Header("accept") accept: String = "text/plain"
    ): Response<EmployeeProfileResponse>
}
