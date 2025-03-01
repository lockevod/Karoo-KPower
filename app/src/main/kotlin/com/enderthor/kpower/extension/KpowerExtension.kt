package com.enderthor.kpower.extension



import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import com.enderthor.kpower.vdevice.EstimatedPowerSource
import com.enderthor.kpower.BuildConfig
import kotlinx.coroutines.flow.transformLatest
import com.enderthor.kpower.data.GpsCoordinates
import com.enderthor.kpower.data.HeadwindStats
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retry

import timber.log.Timber
import kotlin.math.absoluteValue
import kotlin.time.Duration.Companion.minutes



class KpowerExtension : KarooExtension("kpower", BuildConfig.VERSION_NAME)
{

    lateinit var karooSystem: KarooSystemService
    private var serviceJob: Job? = null
    private var updateLastKnownGpsJob: Job? = null



    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    override fun onCreate() {
        super.onCreate()

        Timber.d("Service created")
        karooSystem = KarooSystemService(applicationContext)

        karooSystem.connect { connected ->
            if (connected) {
                Timber.d("Connected to Karoo system")
            }
        }

        updateLastKnownGpsJob = CoroutineScope(Dispatchers.IO).launch {
            karooSystem.updateLastKnownGps(this@KpowerExtension)
        }


        serviceJob = CoroutineScope(Dispatchers.IO).launch{

            val gpsFlow = karooSystem
                .getGpsCoordinateFlow(this@KpowerExtension)
                .distinctUntilChanged { old, new ->
                    if (old != null && new != null) {
                        old.distanceTo(new).absoluteValue < 0.001
                    } else {
                        old == new
                    }
                }
                .debounce(10000L)
                .transformLatest { value: GpsCoordinates? ->
                    while(true){
                        emit(value)
                        delay(15.minutes)
                    }
                }

            loadPreferencesFlow()
                .combine(gpsFlow) { preferences, gps -> preferences to gps }
                .map { (preferences,gps) ->

                    val lastKnownStats = try {
                        streamStats().first()
                    } catch(e: Exception){
                        Timber.e("Failed to read stats $e")
                        HeadwindStats()
                    }

                    if (gps == null){
                        error("No GPS coordinates available")
                    }


                    val response = karooSystem.makeOpenMeteoHttpRequest(gps,preferences[0].isOpenWeather, preferences[0].apikey)
                    if (response.error != null){
                        try {
                            val stats = lastKnownStats.copy(failedWeatherRequest = System.currentTimeMillis())
                            launch { saveStats(this@KpowerExtension, stats) }
                        } catch(e: Exception){
                            Timber.e( "Failed to write stats $e")
                        }
                        error("HTTP request failed: ${response.error}")
                    } else {
                        try {
                            val stats = lastKnownStats.copy(
                                lastSuccessfulWeatherRequest = System.currentTimeMillis(),
                                lastSuccessfulWeatherPosition = gps
                            )
                            launch { saveStats(this@KpowerExtension, stats) }
                        } catch(e: Exception){
                            Timber.e("Failed to write stats $e")
                        }
                    }

                    response
                }
                .retry(Long.MAX_VALUE) { delay(1.minutes); true }
                .collect { response ->
                    try {
                        val responseString = String(response.body ?: ByteArray(0))
                        Timber.d("Got response: $responseString")
                        val data = parseWeatherResponse(responseString)
                            //jsonWithUnknownKeys.decodeFromString<OpenMeteoCurrentWeatherResponse>(responseString)
                        saveCurrentData(applicationContext, data)
                    } catch(e: Exception){
                        Timber.e("Failed to read current weather data $e")
                    }
                }

        }
    }

    override fun startScan(emitter: Emitter<Device>) {
        // Find estimated Power source
        val job = CoroutineScope(Dispatchers.IO).launch {
            delay(2000L)
            Timber.d("Start scan")
            emitter.onNext(EstimatedPowerSource(extension, 2000, karooSystem, applicationContext).source)
        }
        emitter.setCancellable {
            job.cancel()
        }
    }

    override fun connectDevice(uid: String, emitter: Emitter<DeviceEvent>) {
        Timber.d("Connect Device")
        EstimatedPowerSource.Companion.fromUid(extension, uid, karooSystem,  applicationContext)?.connect(emitter,extension)
    }

    override fun onDestroy() {
        karooSystem.disconnect()
        super.onDestroy()
    }
}