package com.xeniaone.templeapp.di

import android.content.Context
import com.xeniaone.templeapp.network.AuthApiService
import com.xeniaone.templeapp.network.OrderApiService
import com.xeniaone.templeapp.network.RetrofitClient
import com.xeniaone.templeapp.network.socket.CatalogueSocketManager
import com.xeniaone.templeapp.repository.AuthRepository
import com.xeniaone.templeapp.repository.AuthRepositoryImpl
import com.xeniaone.templeapp.repository.OrderRepository
import com.xeniaone.templeapp.repository.OrderRepositoryImpl
import com.xeniaone.templeapp.util.AuthManager
import kotlinx.coroutines.Dispatchers

class DependencyContainer(context: Context) {
    val authManager: AuthManager by lazy { AuthManager(context.applicationContext) }
    val authApiService: AuthApiService by lazy { RetrofitClient.createAuthApiService() }
    val authRepository: AuthRepository by lazy {
        AuthRepositoryImpl(authApiService, Dispatchers.IO)
    }
    val orderApiService: OrderApiService by lazy { RetrofitClient.createOrderApiService() }
    val orderRepository: OrderRepository by lazy {
        OrderRepositoryImpl(orderApiService, Dispatchers.IO)
    }
    val catalogueSocketManager: CatalogueSocketManager by lazy {
        CatalogueSocketManager()
    }
}
