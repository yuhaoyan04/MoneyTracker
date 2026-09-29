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

object LocationHelper {

    data class Place(val latitude: Double, val longitude: Double, val name: String?)

    @SuppressLint("MissingPermission")
    fun lastPlace(context: Context): Place? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        var best: Location? = null
        try {
            // 优先用 FINE 获取更精准位置
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                best = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
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
            @Suppress("DEPRECATION")
            val list = geo.getFromLocation(lat, lng, 5)
            list?.firstOrNull()?.let { addr ->
                // 尽量取最精准的地名：建筑/店铺名 > 门牌+街道 > 社区 > 区 > 市
                val specific = listOfNotNull(
                    addr.premises,
                    addr.featureName,
                    addr.thoroughfare
                ).filter { it.isNotBlank() && it.length > 1 }
                if (specific.isNotEmpty()) {
                    specific.take(2).joinToString(" ")
                } else {
                    val area = listOfNotNull(
                        addr.subLocality,
                        addr.locality
                    ).filter { it.isNotBlank() }
                    if (area.isNotEmpty()) area.joinToString(" ") else addr.getAddressLine(0)?.substringAfter(" ")
                }
            }
        } catch (_: Exception) { null }
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
