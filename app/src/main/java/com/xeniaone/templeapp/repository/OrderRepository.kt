package com.xeniaone.templeapp.repository

import com.xeniaone.templeapp.network.model.OrderKioskRequest
import com.xeniaone.templeapp.network.model.OrderKioskSummaryResponse
import com.xeniaone.templeapp.network.model.PaymentProviderItem
import com.xeniaone.templeapp.network.model.SibCheckPaymentResponse

interface OrderRepository {
    suspend fun getPaymentProviders(companyId: Int): Result<List<PaymentProviderItem>>

    suspend fun createKioskOrder(
        companyId: Int,
        branchId: Int,
        customerId: Int,
        userId: Int?,
        request: OrderKioskRequest
    ): Result<OrderKioskSummaryResponse>

    suspend fun checkSibPaymentStatus(
        orderId: Int,
    ): Result<SibCheckPaymentResponse>



    suspend fun processOrder(
        orderId: Int,
        companyId: Int,
        customerId: Int = 0,
        settled: Boolean,
        isWhatsApp: Boolean
    ): Result<OrderKioskSummaryResponse>


    suspend fun checkRazorpayPaymentStatus(
        orderId: Int,
        razorpayOrderId: String
    ): Result<SibCheckPaymentResponse>

    suspend fun getOrderDetails(
        orderId: Long
    ): Result<com.xeniaone.templeapp.network.model.OrderDetailsResponse>

    suspend fun cancelOrder(
        orderId: Int,
        companyId: Int,
        settled: Boolean = false
    ): Result<OrderKioskSummaryResponse>
}
