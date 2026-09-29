package com.mudasir.smartledger.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * 支付地点抓取。使用系统 LocationManager（无需 Google Play Services，国内可用）。
 * 仅取最近一次已知位置（快、低耗），反查地名尽力而为；失败返回 null，不阻塞记账。
 */
object LocationHelper {

    data class Place(val latitude: Double, val longitude: Double, val name: String?)

    @SuppressLint("MissingPermission")
    fun lastPlace(context: Context): Place? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        var best: Location? = null
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                best = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: best
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                val n = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (best == null || (n != null && n.accuracy < best.accuracy)) best = n ?: best
            }
        } catch (_: SecurityException) { return null }
        if (best == null) return null
        return Place(best.latitude, best.longitude, reverseGeocode(context, best.latitude, best.longitude))
    }

    private fun reverseGeocode(context: Context, lat: Double, lng: Double): String? {
        return try {
            val geo = Geocoder(context, Locale.getDefault())
            val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 异步回调变体复杂，这里用同步弃用 API（仍可用）
                @Suppress("DEPRECATION")
                geo.getFromLocation(lat, lng, 1)
            } else {
                @Suppress("DEPRECATION")
                geo.getFromLocation(lat, lng, 1)
            }
            list?.firstOrNull()?.let { addr ->
                val parts = listOfNotNull(
                    addr.locality,
                    addr.subLocality,
                    addr.thoroughfare
                ).filter { it.isNotBlank() }
                if (parts.isNotEmpty()) parts.joinToString(" ") else addr.getAddressLine(0)
            }
        } catch (_: Exception) { null }
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
