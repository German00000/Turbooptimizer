package com.turbooptimizer.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.turbooptimizer.R

object NotificationChannels {
    const val GAME = "game_booster"
    const val RECORDER = "screen_recorder"

    fun create(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(GAME, context.getString(R.string.channel_game), NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Sesión de juego activa, overlay y RAM Boost"
                    setShowBadge(false)
                },
                NotificationChannel(RECORDER, context.getString(R.string.channel_recorder), NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Grabación de pantalla en curso"
                    setShowBadge(false)
                },
            ),
        )
    }
}
