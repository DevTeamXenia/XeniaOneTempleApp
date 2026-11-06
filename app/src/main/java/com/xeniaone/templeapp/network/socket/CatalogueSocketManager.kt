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
    private val hubUrl: String = "https://catalogueapi.xeniacatalogue.info/catalogueHub"
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
    private var retryCount = 0
    private var isReconnecting = false

    private val _connectionState = MutableStateFlow(HubConnectionState.DISCONNECTED)
    val connectionState: StateFlow<HubConnectionState> = _connectionState.asStateFlow()

    private val _productsFlow = MutableStateFlow<List<SocketProduct>>(emptyList())
    val productsFlow: StateFlow<List<SocketProduct>> = _productsFlow.asStateFlow()

    fun connect(companyId: Int, branchId: Int) {
        this.companyId = companyId
        this.branchId = branchId

        if (hubConnection != null && hubConnection?.connectionState == HubConnectionState.CONNECTED) {
            Log.d(TAG, "Already connected, requesting fresh products")
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
                Log.w(TAG, "SignalR Connection closed: ${error?.message}")
                _connectionState.value = HubConnectionState.DISCONNECTED
                reconnect()
            }

            startConnection()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing SignalR hub: ${e.message}", e)
        }
    }

    private fun startConnection() {
        hubConnection?.start()?.subscribe(
            {
                Log.i(TAG, "✅ SignalR Socket Connected successfully to $hubUrl")
                _connectionState.value = HubConnectionState.CONNECTED
                retryCount = 0
                isReconnecting = false

                // Join the company group
                safeSend("JoinCompanyGroup", companyId)

                // Directly request products (no category UI needed as per kiosk spec)
                requestProducts()
            },
            { error ->
                Log.e(TAG, "❌ SignalR start error: ${error.message}")
                _connectionState.value = HubConnectionState.DISCONNECTED
                reconnect()
            }
        )
    }

    fun requestProducts() {
        Log.d(TAG, "Requesting products via socket: companyId=$companyId, branchId=$branchId")
        // Request products directly without category filter
        safeSend(
            "SendCatalogueUpdate",
            "PRODUCT",
            companyId,
            branchId,
            null,
            null, // Category is null / all
            1,
            50,   // page size
            "all",
            null,
            null,
            null
        )
    }

    private fun safeSend(method: String, vararg args: Any?): Boolean {
        return try {
            val conn = hubConnection ?: return false
            if (conn.connectionState != HubConnectionState.CONNECTED) {
                if (!isReconnecting) reconnect()
                return false
            }
            conn.send(method, *args)
            true
        } catch (e: Exception) {
            Log.e(TAG, "safeSend failed for $method: ${e.message}")
            false
        }
    }

    private fun handleCatalogueUpdate(type: String, payload: Any?) {
        if (payload == null) return
        try {
            val json = gson.toJson(payload)
            Log.d(TAG, "Received update type=$type, payload size=${json.length}")

            if (type.equals("PRODUCT", ignoreCase = true)) {
                val response = gson.fromJson(json, ProductResponse::class.java)
                val list = response.products?.products ?: emptyList()
                Log.i(TAG, "Received ${list.size} products from Socket!")
                _productsFlow.value = list
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing catalogue update: ${e.message}", e)
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
                Log.d(TAG, "Reconnecting SignalR in ${delayTime}ms (Attempt #$retryCount)")
                delay(delayTime)

                if (hubConnection?.connectionState == HubConnectionState.CONNECTED) {
                    isReconnecting = false
                    return@launch
                }
                startConnection()
            } catch (e: Exception) {
                Log.e(TAG, "Reconnect failed: ${e.message}")
            } finally {
                isReconnecting = false
            }
        }
    }

    fun disconnect() {
        try {
            hubConnection?.stop()
            _connectionState.value = HubConnectionState.DISCONNECTED
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting: ${e.message}")
        }
    }
}
