package com.turbooptimizer

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.turbooptimizer.util.NotificationChannels

class TurboApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationChannels.create(this)
        container.start()
    }

    /** Coil con soporte de miniaturas de vídeo (vista previa de duplicados). */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .crossfade(true)
        .build()
}
