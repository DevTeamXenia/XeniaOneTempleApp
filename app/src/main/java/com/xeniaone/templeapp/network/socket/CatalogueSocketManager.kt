package com.xeniaone.templeapp.network.socket

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.xeniaone.templeapp.network.model.ProductResponse
import com.xeniaone.templeapp.network.model.SocketProduct
import com.google.gson.Gson
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.HubConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.pow

class CatalogueSocketManager(
    private val hubUrl: String = "https://oneapi.xeniapos.com/catalogueHub"
//          private val hubUrl: String = "https://catalogueapi.xeniacatalogue.info/catalogueHub"
) {

    companion object {
        private const val TAG = "CatalogueSocketManager"
    }

    private var hubConnection: HubConnection? = null
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var companyId: Int = 11
    private var branchId: Int = 3
    private var tableId: Int? = null
    private var retryCount = 0
    private var isReconnecting = false

    private val _connectionState = MutableStateFlow(HubConnectionState.DISCONNECTED)
    val connectionState: StateFlow<HubConnectionState> = _connectionState.asStateFlow()

    private val _productsFlow = MutableStateFlow<List<SocketProduct>>(emptyList())
    val productsFlow: StateFlow<List<SocketProduct>> = _productsFlow.asStateFlow()

    fun connect(companyId: Int, branchId: Int, tableId: Int? = null) {
        this.companyId = companyId
        this.branchId = branchId
        this.tableId = tableId
        Log.i(TAG, "🔌 [SignalR Connect] Initiating connection to $hubUrl with companyId=$companyId, branchId=$branchId, tableId=$tableId")

        if (hubConnection != null && hubConnection?.connectionState == HubConnectionState.CONNECTED) {
            Log.d(TAG, "ℹ️ Already connected, requesting fresh products")
            requestProducts()
            return
        }

        try {
            hubConnection = HubConnectionBuilder
                .create(hubUrl)
                .build()

            hubConnection?.on(
                "ReceiveCatalogueUpdate",
                { type: String, payload: Any? ->
                    handleCatalogueUpdate(type, payload)
                },
                String::class.java,
                Any::class.java
            )

            hubConnection?.onClosed { error ->
                Log.w(TAG, "⚠️ [SignalR Closed] Connection closed: ${error?.message}")
                _connectionState.value = HubConnectionState.DISCONNECTED
                reconnect()
            }

            startConnection()
        } catch (e: Exception) {
            Log.e(TAG, "❌ [SignalR Init Error] Error initializing SignalR hub: ${e.message}", e)
        }
    }

    private fun startConnection() {
        Log.d(TAG, "🚀 [SignalR Start] Attempting to start hub connection...")
        hubConnection?.start()?.subscribe(
            {
                Log.i(TAG, "✅ [SignalR Connected] Connected successfully to $hubUrl")
                _connectionState.value = HubConnectionState.CONNECTED
                retryCount = 0
                isReconnecting = false

                // Join the company group
                safeSend("JoinCompanyGroup", companyId)

                // Directly request products
                requestProducts()
            },
            { error ->
                Log.e(TAG, "❌ [SignalR Start Error] start error: ${error.message}", error)
                _connectionState.value = HubConnectionState.DISCONNECTED
                reconnect()
            }
        )
    }

    fun requestProducts(tableId: Int? = this.tableId) {
        Log.d(TAG, "📦 [SignalR Request] Requesting products via socket: companyId=$companyId, branchId=$branchId, tableId=$tableId")
        safeSend(
            "SendCatalogueUpdate",
            "PRODUCT",
            companyId,
            branchId,
            null,
            null,
            1,
            36,
            "all",
            null,
            null,
            null,
            tableId
        )
    }

    private fun safeSend(method: String, vararg args: Any?): Boolean {
        return try {
            val conn = hubConnection ?: run {
                Log.w(TAG, "⚠️ [SignalR Send] Cannot send '$method', hubConnection is null")
                return false
            }
            if (conn.connectionState != HubConnectionState.CONNECTED) {
                Log.w(TAG, "⚠️ [SignalR Send] Cannot send '$method', connection state is ${conn.connectionState}")
                if (!isReconnecting) reconnect()
                return false
            }
            val argsJson = gson.toJson(args)
            logLongJson(TAG, "📤 [SignalR Outbound Request] method='$method', args=$argsJson")
            conn.send(method, *args)
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ [SignalR Send Error] failed for method $method: ${e.message}", e)
            false
        }
    }

    private fun handleCatalogueUpdate(type: String, payload: Any?) {
        Log.i(TAG, "📥 [SignalR Receive] ReceiveCatalogueUpdate triggered with type='$type'")
        if (payload == null) {
            Log.w(TAG, "⚠️ [SignalR Receive] payload is null for type=$type")
            return
        }
        try {
            val json = gson.toJson(payload)
            logLongJson(TAG, "📥 [SignalR Full Response] type=$type, size=${json.length}\n$json")

            if (type.equals("PRODUCT", ignoreCase = true)) {
                val response = gson.fromJson(json, ProductResponse::class.java)
                val list = response.products?.products ?: emptyList()
                Log.i(TAG, "✅ [SignalR Products] Parsed ${list.size} products from Socket!")
                _productsFlow.value = list
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ [SignalR Error] parsing catalogue update for type=$type: ${e.message}", e)
        }
    }

    private fun logLongJson(tag: String, message: String) {
        val maxLogSize = 3000
        if (message.length > maxLogSize) {
            var i = 0
            while (i < message.length) {
                val end = (i + maxLogSize).coerceAtMost(message.length)
                Log.d(tag, message.substring(i, end))
                i += maxLogSize
            }
        } else {
            Log.d(tag, message)
        }
    }

    private fun reconnect() {
        if (isReconnecting) return
        isReconnecting = true

        scope.launch {
            try {
                retryCount++
                val delayTime = (1000L * 2.0.pow(retryCount.toDouble()))
                    .toLong()
                    .coerceAtMost(10000L)
                Log.d(TAG, "🔄 [SignalR Reconnect] Reconnecting SignalR in ${delayTime}ms (Attempt #$retryCount)")
                delay(delayTime)

                if (hubConnection?.connectionState == HubConnectionState.CONNECTED) {
                    isReconnecting = false
                    return@launch
                }
                startConnection()
            } catch (e: Exception) {
                Log.e(TAG, "❌ [SignalR Reconnect Error] Reconnect failed: ${e.message}", e)
            } finally {
                isReconnecting = false
            }
        }
    }

    fun disconnect() {
        try {
            Log.i(TAG, "🔌 [SignalR Disconnect] Disconnecting hub connection...")
            hubConnection?.stop()
            _connectionState.value = HubConnectionState.DISCONNECTED
        } catch (e: Exception) {
            Log.e(TAG, "❌ [SignalR Disconnect Error] Error disconnecting: ${e.message}", e)
        }
    }
}
