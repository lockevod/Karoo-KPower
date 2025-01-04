package com.enderthor.kpower.data

import android.content.Context


import com.enderthor.kpower.extension.getHeadingFlow
import com.enderthor.kpower.extension.streamCurrentWeatherData
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.cos
import timber.log.Timber

class HeadwindSpeedDataType(
    private val karooSystem: KarooSystemService,
    private val context: Context, extension: String) : DataTypeImpl(extension, "powerheadwind"){

    data class StreamData(val value: Double, val data: OpenMeteoCurrentWeatherResponse)

    override fun startStream(emitter: Emitter<StreamState>) {
        Timber.d("Starting stream for $dataTypeId")
        val job = CoroutineScope(Dispatchers.IO).launch {
            karooSystem.getHeadingFlow()
                .filterNotNull()
                .combine(context.streamCurrentWeatherData()) { value, data -> value to data }
                .map { (value, wind) -> StreamData(value, wind) }
                .collect { streamData ->
                    val windSpeed = streamData.data.current.windSpeed
                    val windDirection = streamData.data.current.windDirection
                    val bearing = streamData.value
                   // Timber.d("Wind speed: $windSpeed, wind direction: $windDirection, bearing: $bearing")
                   // val headwindSpeed = cos( (windDirection + 180) * Math.PI / 180.0) * windSpeed
                    val headwindSpeed = if (windSpeed.isNaN() || windDirection.isNaN() || bearing.isNaN()) 0.0 else (cos(Math.toRadians(windDirection - bearing + 180)) * windSpeed) // headwind positive if opposite to bearing
                    //Timber.d("Headwind speed: $headwindSpeed windSpeed: $windSpeed windDirection: $windDirection bearing: $bearing")

                    emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to headwindSpeed))))
                }

        }

        emitter.setCancellable {
            job.cancel()
        }
    }
}