package com.xeniaone.templeapp.repository

import com.xeniaone.templeapp.network.OrderApiService
import com.xeniaone.templeapp.network.model.OrderKioskRequest
import com.xeniaone.templeapp.network.model.OrderKioskSummaryResponse
import com.xeniaone.templeapp.network.model.PaymentProviderItem
import com.xeniaone.templeapp.network.model.SibCheckPaymentResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class OrderRepositoryImpl(
    private val orderApiService: OrderApiService,
    private val ioDispatcher: CoroutineDispatcher
) : OrderRepository {

    override suspend fun getPaymentProviders(companyId: Int): Result<List<PaymentProviderItem>> =
        withContext(ioDispatcher) {
            try {
                val response = orderApiService.getPaymentProviders(companyId)
                if (response.isSuccessful) {
                    val body = response.body()
                    val providers = body?.data ?: emptyList()
                    Result.success(providers)
                } else {
                    Result.failure(Exception("HTTP error ${response.code()}: ${response.message()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun createKioskOrder(
        companyId: Int,
        branchId: Int,
        customerId: Int,
        userId: Int?,
        request: OrderKioskRequest
    ): Result<OrderKioskSummaryResponse> = withContext(ioDispatcher) {
        try {
            val response = orderApiService.createKioskOrder(
                companyId = companyId,
                branchId = branchId,
                customerId = customerId,
                userId = userId,
                request = request
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val errorBody = response.errorBody()?.string()
                val parsedMessage = try {
                    if (!errorBody.isNullOrBlank()) {
                        val summary = com.google.gson.Gson().fromJson(errorBody, OrderKioskSummaryResponse::class.java)
                        summary?.message
                    } else null
                } catch (_: Exception) {
                    null
                }
                val errorMessage = if (!parsedMessage.isNullOrBlank()) {
                    parsedMessage
                } else {
                    "HTTP ${response.code()}: $errorBody"
                }
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun checkSibPaymentStatus(
        orderId: Int,
    ): Result<SibCheckPaymentResponse> = withContext(ioDispatcher) {
        try {
            val fullUrl = "https://oneapi.xeniapos.com/api/Order/sib/checkPayment/$orderId"
            val response = orderApiService.checkSibPaymentStatus(fullUrl)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val errorBody = response.errorBody()?.string()
                Result.failure(Exception("HTTP ${response.code()}: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
