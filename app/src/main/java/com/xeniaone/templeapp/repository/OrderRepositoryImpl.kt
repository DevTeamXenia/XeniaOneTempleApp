package com.xeniaone.templeapp.repository

import android.util.Log
import com.google.gson.Gson
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

    companion object {
        private const val TAG = "OrderRepository"
        private val gson = Gson()
    }

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
            val reqJson = gson.toJson(request)
            val response = orderApiService.createKioskOrder(
                companyId = companyId,
                branchId = branchId,
                customerId = customerId,
                userId = userId,
                request = request
            )
            if (response.isSuccessful && response.body() != null) {
                val resBody = response.body()!!
                Result.success(resBody)
            } else {
                val errorBody = response.errorBody()?.string()
                val parsedMessage = try {
                    if (!errorBody.isNullOrBlank()) {
                        val summary = gson.fromJson(errorBody, OrderKioskSummaryResponse::class.java)
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
                val body = response.body()!!
                Result.success(body)
            } else {
                val errorBody = response.errorBody()?.string()
                Result.failure(Exception("HTTP ${response.code()}: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun processOrder(
        orderId: Int,
        companyId: Int,
        customerId: Int,
        settled: Boolean,
        isWhatsApp: Boolean,
        paymentRef: String?
    ): Result<OrderKioskSummaryResponse> = withContext(ioDispatcher) {
        try {
            val response = orderApiService.processOrder(orderId, companyId, customerId, settled, isWhatsApp, paymentRef)
            if (response.isSuccessful && response.body() != null) {
                val resBody = response.body()!!
                Result.success(resBody)
            } else {
                val errorBody = response.errorBody()?.string()
                val parsedMessage = try {
                    if (!errorBody.isNullOrBlank()) {
                        val summary = gson.fromJson(errorBody, OrderKioskSummaryResponse::class.java)
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

    override suspend fun checkRazorpayPaymentStatus(
        orderId: Int,
        razorpayOrderId: String
    ): Result<SibCheckPaymentResponse> = withContext(ioDispatcher) {
        try {
            val response = orderApiService.checkRazorpayPaymentStatus(orderId, razorpayOrderId)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                Result.success(body)
            } else {
                val errorBody = response.errorBody()?.string()
                Result.failure(Exception("HTTP ${response.code()}: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun checkRazorpayQrPaymentStatus(
        orderId: Int,
        easeOrderId: String?
    ): Result<SibCheckPaymentResponse> = withContext(ioDispatcher) {
        try {
            val response = orderApiService.checkRazorpayPaymentStatus(orderId, easeOrderId!!)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                Result.success(body)
            } else {
                val errorBody = response.errorBody()?.string()
                Result.failure(Exception("HTTP ${response.code()}: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getOrderDetails(
        orderId: Long
    ): Result<com.xeniaone.templeapp.network.model.OrderDetailsResponse> = withContext(ioDispatcher) {
        try {
            val response = orderApiService.getOrderDetails(orderId)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                Result.success(body)
            } else {
                val errorBody = response.errorBody()?.string()
                Result.failure(Exception("HTTP ${response.code()}: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun cancelOrder(
        orderId: Int,
        companyId: Int,
        settled: Boolean
    ): Result<OrderKioskSummaryResponse> = withContext(ioDispatcher) {
        try {
            val response = orderApiService.cancelOrder(orderId, companyId, settled)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                Result.success(body)
            } else {
                val errorBody = response.errorBody()?.string()
                Result.failure(Exception("HTTP ${response.code()}: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
