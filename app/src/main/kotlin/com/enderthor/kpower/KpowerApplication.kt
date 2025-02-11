package com.enderthor.kpower

import android.app.Application
import timber.log.Timber


class KpowerApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
         }
        Timber.plant(Timber.DebugTree())
        Timber.d("Starting KPower App")
    }
}
