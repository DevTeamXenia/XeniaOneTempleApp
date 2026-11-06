package com.xeniaone.templeapp.network

import com.xeniaone.templeapp.network.model.OrderKioskRequest
import com.xeniaone.templeapp.network.model.OrderKioskSummaryResponse
import com.xeniaone.templeapp.network.model.PaymentProviderResponse
import com.xeniaone.templeapp.network.model.SibCheckPaymentResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

interface OrderApiService {

    @GET("api/PaymentProvider/company/{companyId}")
    suspend fun getPaymentProviders(
        @Path("companyId") companyId: Int
    ): Response<PaymentProviderResponse>

    @POST("api/Order/kiosk/summary")
    suspend fun createKioskOrder(
        @Query("companyId") companyId: Int,
        @Query("branchId") branchId: Int,
        @Query("customerId") customerId: Int = 0,
        @Query("userId") userId: Int? = null,
        @Body request: OrderKioskRequest
    ): Response<OrderKioskSummaryResponse>

    @GET
    suspend fun checkSibPaymentStatus(
        @Url fullUrl: String
    ): Response<SibCheckPaymentResponse>
}
