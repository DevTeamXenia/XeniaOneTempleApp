package com.xeniaone.templeapp

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.BuildConfig
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.xeniaone.templeapp.di.DependencyContainer
import com.xeniaone.templeapp.pinelabs.PineLabsManager

class TempleApp : Application() {

    lateinit var container: DependencyContainer
        private set

    var isPineLabsDevice: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()


        try {
            FirebaseApp.initializeApp(this)
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
        } catch (e: Exception) {

        }


        container = DependencyContainer(this)
        isPineLabsDevice = PineLabsManager.isPineLabsAvailable(this)
    }
}
