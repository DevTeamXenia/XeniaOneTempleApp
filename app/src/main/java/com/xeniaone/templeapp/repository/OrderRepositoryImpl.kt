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

        private fun extractErrorMessage(errorBody: String?, statusCode: Int, defaultMsg: String): String {
            if (errorBody.isNullOrBlank()) return "$defaultMsg (HTTP $statusCode)"
            return try {
                val jsonElement = com.google.gson.JsonParser.parseString(errorBody)
                if (jsonElement.isJsonObject) {
                    val obj = jsonElement.asJsonObject
                    val primaryMsg = when {
                        obj.has("Message") && !obj.get("Message").isJsonNull -> obj.get("Message").asString
                        obj.has("message") && !obj.get("message").isJsonNull -> obj.get("message").asString
                        obj.has("Exception") && !obj.get("Exception").isJsonNull -> obj.get("Exception").asString
                        obj.has("exception") && !obj.get("exception").isJsonNull -> obj.get("exception").asString
                        obj.has("Error") && !obj.get("Error").isJsonNull -> obj.get("Error").asString
                        obj.has("error") && !obj.get("error").isJsonNull -> obj.get("error").asString
                        obj.has("title") && !obj.get("title").isJsonNull -> obj.get("title").asString
                        else -> null
                    }
                    val validationErrors = if (obj.has("errors") && obj.get("errors").isJsonObject) {
                        val errorsObj = obj.getAsJsonObject("errors")
                        val sb = StringBuilder()
                        for ((_, errArr) in errorsObj.entrySet()) {
                            if (errArr.isJsonArray) {
                                for (err in errArr.asJsonArray) {
                                    if (sb.isNotEmpty()) sb.append(", ")
                                    sb.append(err.asString)
                                }
                            }
                        }
                        sb.toString().takeIf { it.isNotBlank() }
                    } else null

                    when {
                        !primaryMsg.isNullOrBlank() && !validationErrors.isNullOrBlank() -> "$primaryMsg: $validationErrors"
                        !primaryMsg.isNullOrBlank() -> primaryMsg
                        !validationErrors.isNullOrBlank() -> validationErrors
                        else -> errorBody
                    }
                } else {
                    errorBody
                }
            } catch (_: Exception) {
                errorBody
            }
        }
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
                    val errorBody = response.errorBody()?.string()
                    val errorMsg = extractErrorMessage(errorBody, response.code(), "Failed to get payment providers")
                    Result.failure(Exception(errorMsg))
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
                if (resBody.status.equals("Success", ignoreCase = true) && (resBody.orderId ?: 0) > 0) {
                    Result.success(resBody)
                } else {
                    val errorMsg = resBody.message?.takeIf { it.isNotBlank() }
                        ?: "Order summary creation failed (Status: ${resBody.status})"
                    Result.failure(Exception(errorMsg))
                }
            } else {
                val errorBody = response.errorBody()?.string()
                val errorMessage = extractErrorMessage(errorBody, response.code(), "Order summary creation failed")
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
                val errorMessage = extractErrorMessage(errorBody, response.code(), "Payment status check failed")
                Result.failure(Exception(errorMessage))
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
                if (resBody.status.equals("PAID", ignoreCase = true)) {
                    Result.success(resBody)
                } else {
                    val errorMsg = resBody.message?.takeIf { it.isNotBlank() }
                        ?: "Order placement failed (Status: ${resBody.status})"
                    Result.failure(Exception(errorMsg))
                }
            } else {
                val errorBody = response.errorBody()?.string()
                val errorMessage = extractErrorMessage(errorBody, response.code(), "Order placement failed")
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
                val errorMessage = extractErrorMessage(errorBody, response.code(), "Payment status check failed")
                Result.failure(Exception(errorMessage))
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
            val qrId = easeOrderId ?: ""
            val response = orderApiService.checkRazorpayPaymentStatus(orderId, qrId)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                Result.success(body)
            } else {
                val errorBody = response.errorBody()?.string()
                val errorMessage = extractErrorMessage(errorBody, response.code(), "Payment status check failed")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getOrderDetails(
        orderId: Long
    ): Result<com.xeniaone.templeapp.network.model.OrderDetailsResponse> = withContext(ioDispatcher) {
        try {
            android.util.Log.i("OrderRepository", "📦 [getOrderDetails API] Requesting GET api/Order/$orderId...")
            val response = orderApiService.getOrderDetails(orderId)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                android.util.Log.i("OrderRepository", "📦 [getOrderDetails API Success] orderId=$orderId, orderNo=${body.data?.orderNo}, totalAmount=${body.data?.totalAmount}, itemsCount=${body.data?.items?.size ?: 0}")
                Result.success(body)
            } else {
                val errorBody = response.errorBody()?.string()
                android.util.Log.e("OrderRepository", "❌ [getOrderDetails API Error] orderId=$orderId, code=${response.code()}, error=$errorBody")
                val errorMessage = extractErrorMessage(errorBody, response.code(), "Failed to get order details")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            android.util.Log.e("OrderRepository", "❌ [getOrderDetails Exception] orderId=$orderId, error=${e.message}", e)
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
                if (body.status.equals("Success", ignoreCase = true)) {
                    Result.success(body)
                } else {
                    val errorMsg = body.message?.takeIf { it.isNotBlank() }
                        ?: "Order cancellation failed (Status: ${body.status})"
                    Result.failure(Exception(errorMsg))
                }
            } else {
                val errorBody = response.errorBody()?.string()
                val errorMessage = extractErrorMessage(errorBody, response.code(), "Order cancellation failed")
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getSaleReport(
        startDate: String,
        endDate: String
    ): Result<com.google.gson.JsonObject> = withContext(ioDispatcher) {
        try {
            val response = orderApiService.getSaleReport(startDate = startDate, endDate = endDate)
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
