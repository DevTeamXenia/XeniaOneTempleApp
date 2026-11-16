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
                Log.d(TAG, "📤 [GET PaymentProviders] Request for companyId=$companyId")
                val response = orderApiService.getPaymentProviders(companyId)
                if (response.isSuccessful) {
                    val body = response.body()
                    val providers = body?.data ?: emptyList()
                    Log.i(TAG, "📥 [GET PaymentProviders] Success: ${providers.size} provider(s) fetched")
                    Result.success(providers)
                } else {
                    Log.w(TAG, "⚠️ [GET PaymentProviders] HTTP error ${response.code()}: ${response.message()}")
                    Result.failure(Exception("HTTP error ${response.code()}: ${response.message()}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ [GET PaymentProviders] Exception: ${e.message}", e)
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
            Log.d(TAG, "📤 [POST createKioskOrder] companyId=$companyId, branchId=$branchId, userId=$userId")
            Log.d(TAG, "📤 [POST createKioskOrder Payload]: $reqJson")

            val response = orderApiService.createKioskOrder(
                companyId = companyId,
                branchId = branchId,
                customerId = customerId,
                userId = userId,
                request = request
            )
            if (response.isSuccessful && response.body() != null) {
                val resBody = response.body()!!
                Log.i(TAG, "📥 [POST createKioskOrder Success]: Status=${resBody.status}, OrderId=${resBody.orderId}, Msg=${resBody.message}")
                Log.d(TAG, "📥 [POST createKioskOrder Response JSON]: ${gson.toJson(resBody)}")
                Result.success(resBody)
            } else {
                val errorBody = response.errorBody()?.string()
                Log.w(TAG, "⚠️ [POST createKioskOrder Error ${response.code()}]: $errorBody")
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
            Log.e(TAG, "❌ [POST createKioskOrder Exception]: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun checkSibPaymentStatus(
        orderId: Int,
    ): Result<SibCheckPaymentResponse> = withContext(ioDispatcher) {
        try {
            val fullUrl = "https://oneapi.xeniapos.com/api/Order/sib/checkPayment/$orderId"
            Log.d(TAG, "📤 [GET checkSibPaymentStatus] FullUrl=$fullUrl")
            val response = orderApiService.checkSibPaymentStatus(fullUrl)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                Log.i(TAG, "📥 [GET checkSibPaymentStatus Success]: Status=${body.status}, Msg=${body.message}")
                Result.success(body)
            } else {
                val errorBody = response.errorBody()?.string()
                Log.w(TAG, "⚠️ [GET checkSibPaymentStatus Error ${response.code()}]: $errorBody")
                Result.failure(Exception("HTTP ${response.code()}: $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ [GET checkSibPaymentStatus Exception]: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun processOrder(
        orderId: Int,
        companyId: Int,
        customerId: Int,
        settled: Boolean,
        isWhatsApp: Boolean
    ): Result<OrderKioskSummaryResponse> = withContext(ioDispatcher) {
        try {
            Log.d(TAG, "📤 [POST processOrder] OrderId=$orderId, companyId=$companyId, settled=$settled, isWhatsApp=$isWhatsApp")
            val response = orderApiService.processOrder(orderId, companyId, customerId, settled, isWhatsApp)
            if (response.isSuccessful && response.body() != null) {
                val resBody = response.body()!!
                Log.i(TAG, "📥 [POST processOrder Success]: Status=${resBody.status}, OrderId=${resBody.orderId}, Msg=${resBody.message}")
                Log.d(TAG, "📥 [POST processOrder Response JSON]: ${gson.toJson(resBody)}")
                Result.success(resBody)
            } else {
                val errorBody = response.errorBody()?.string()
                Log.w(TAG, "⚠️ [POST processOrder Error ${response.code()}]: $errorBody")
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
            Log.e(TAG, "❌ [POST processOrder Exception]: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun checkRazorpayPaymentStatus(
        orderId: Int,
        razorpayOrderId: String
    ): Result<SibCheckPaymentResponse> = withContext(ioDispatcher) {
        try {
            Log.d(TAG, "📤 [GET checkRazorpayPaymentStatus] OrderId=$orderId, razorpayOrderId=$razorpayOrderId")
            val response = orderApiService.checkRazorpayPaymentStatus(orderId, razorpayOrderId)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                Log.i(TAG, "📥 [GET checkRazorpayPaymentStatus Success]: Status=${body.status}, Msg=${body.message}")
                Result.success(body)
            } else {
                val errorBody = response.errorBody()?.string()
                Log.w(TAG, "⚠️ [GET checkRazorpayPaymentStatus Error ${response.code()}]: $errorBody")
                Result.failure(Exception("HTTP ${response.code()}: $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ [GET checkRazorpayPaymentStatus Exception]: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun checkRazorpayQrPaymentStatus(
        orderId: Int,
        easeOrderId: String?
    ): Result<SibCheckPaymentResponse> = withContext(ioDispatcher) {
        try {
            Log.d(TAG, "📤 [GET checkRazorpayQrPaymentStatus] OrderId=$orderId, easeOrderId=$easeOrderId")
            val response = orderApiService.checkRazorpayPaymentStatus(orderId, easeOrderId!!)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                Log.i(TAG, "📥 [GET checkRazorpayQrPaymentStatus Success]: Status=${body.status}, Msg=${body.message}")
                Log.d(TAG, "📥 [GET checkRazorpayQrPaymentStatus Response JSON]: ${gson.toJson(body)}")
                Result.success(body)
            } else {
                val errorBody = response.errorBody()?.string()
                Log.w(TAG, "⚠️ [GET checkRazorpayQrPaymentStatus Error ${response.code()}]: $errorBody")
                Result.failure(Exception("HTTP ${response.code()}: $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ [GET checkRazorpayQrPaymentStatus Exception]: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun getOrderDetails(
        orderId: Long
    ): Result<com.xeniaone.templeapp.network.model.OrderDetailsResponse> = withContext(ioDispatcher) {
        try {
            Log.d(TAG, "📤 [GET getOrderDetails] OrderId=$orderId")
            val response = orderApiService.getOrderDetails(orderId)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                Log.i(TAG, "📥 [GET getOrderDetails Success]: Status=${body.status}, OrderNo=${body.data?.orderNo}, PaymentRef=${body.data?.PaymentRef}")
                Log.d(TAG, "📥 [GET getOrderDetails Response JSON]: ${gson.toJson(body)}")
                Result.success(body)
            } else {
                val errorBody = response.errorBody()?.string()
                Log.w(TAG, "⚠️ [GET getOrderDetails Error ${response.code()}]: $errorBody")
                Result.failure(Exception("HTTP ${response.code()}: $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ [GET getOrderDetails Exception]: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun cancelOrder(
        orderId: Int,
        companyId: Int,
        settled: Boolean
    ): Result<OrderKioskSummaryResponse> = withContext(ioDispatcher) {
        try {
            Log.d(TAG, "📤 [POST cancelOrder] OrderId=$orderId, companyId=$companyId, settled=$settled")
            val response = orderApiService.cancelOrder(orderId, companyId, settled)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                Log.i(TAG, "📥 [POST cancelOrder Success]: Status=${body.status}, Msg=${body.message}")
                Result.success(body)
            } else {
                val errorBody = response.errorBody()?.string()
                Log.w(TAG, "⚠️ [POST cancelOrder Error ${response.code()}]: $errorBody")
                Result.failure(Exception("HTTP ${response.code()}: $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ [POST cancelOrder Exception]: ${e.message}", e)
            Result.failure(e)
        }
    }
}
