package com.xeniaone.templeapp

import android.app.Application
import com.xeniaone.templeapp.di.DependencyContainer

class TempleApp : Application() {

    lateinit var container: DependencyContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DependencyContainer(this)
    }
}
