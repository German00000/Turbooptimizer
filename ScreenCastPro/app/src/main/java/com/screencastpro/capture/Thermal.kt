package com.screencastpro.capture

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.screencastpro.core.ThermalLevel

/**
 * Estado térmico. Android 10+ expone el estado oficial del sistema.
 * Android 5–9 no tiene API térmica pública: se usa la temperatura de batería como aproximación.
 */
object Thermal {
    fun current(context: Context): ThermalLevel {
        if (Build.VERSION.SDK_INT >= 29) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
                PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalLevel.CRITICAL
                else -> ThermalLevel.NORMAL
            }
        }
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // EXTRA_TEMPERATURE se expresa en décimas de grado Celsius.
        val celsius = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        return when {
            celsius >= 50.0 -> ThermalLevel.CRITICAL
            celsius >= 45.0 -> ThermalLevel.SEVERE
            celsius >= 42.0 -> ThermalLevel.MODERATE
            else -> ThermalLevel.NORMAL
        }
    }
}
