package com.mudasir.smartledger.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

object LocationHelper {

    data class Place(val latitude: Double, val longitude: Double, val name: String?)

    /** 是否拥有前台（使用期间）定位权限。 */
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** 是否拥有后台定位权限（支付发生时监听器在后台，抓取位置必需）。 */
    fun hasBackgroundPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * 支付时刻定位：主动请求一次新鲜定位（NETWORK 优先，GPS 兜底，并行请求取最快），
     * 超时或失败回退 lastKnown。在协程（IO）中调用，最多阻塞 [timeoutMs] 毫秒。
     *
     * 注意：从后台（通知监听/短信接收）调用需要 ACCESS_BACKGROUND_LOCATION，
     * 否则系统直接抛 SecurityException（回退链路会拿到 null）。
     */
    suspend fun freshPlace(context: Context, timeoutMs: Long = 8000L): Place? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return lastPlace(context)
        val fresh = withTimeoutOrNull(timeoutMs) { awaitFreshLocation(lm) }
        val loc = fresh ?: lastKnown(lm) ?: return null
        // 逆地理编码失败（如无 GMS 的国产 ROM）时用坐标兜底，保证位置信息始终可见
        val name = reverseGeocode(context, loc.latitude, loc.longitude)
            ?: String.format(Locale.getDefault(), "%.4f, %.4f", loc.latitude, loc.longitude)
        return Place(loc.latitude, loc.longitude, name)
    }

    /** 等待一次新鲜定位：同时监听 NETWORK + GPS，谁先返回用谁。 */
    private suspend fun awaitFreshLocation(lm: LocationManager): Location? =
        suspendCancellableCoroutine { cont ->
            val main = Handler(Looper.getMainLooper())
            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    runCatching { lm.removeUpdates(this) }
                    if (cont.isActive) cont.resume(loc)
                }
            }
            try {
                var requested = false
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, listener, main.looper)
                    requested = true
                }
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, main.looper)
                    requested = true
                }
                if (!requested) {
                    if (cont.isActive) cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                cont.invokeOnCancellation {
                    runCatching { lm.removeUpdates(listener) }
                }
            } catch (e: Exception) {
                runCatching { lm.removeUpdates(listener) }
                if (cont.isActive) cont.resume(null)
            }
        }

    /** 立即返回最近已知位置（可能过期或为 null），前台快速路径。 */
    @SuppressLint("MissingPermission")
    fun lastPlace(context: Context): Place? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val best = lastKnown(lm) ?: return null
        return Place(best.latitude, best.longitude, reverseGeocode(context, best.latitude, best.longitude))
    }

    /** 取各 provider 的 lastKnown 中精度最高（accuracy 最小）的一个。 */
    private fun lastKnown(lm: LocationManager): Location? = try {
        listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).mapNotNull { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()
        }.minByOrNull { it.accuracy }
    } catch (e: SecurityException) {
        null
    }

    private fun reverseGeocode(context: Context, lat: Double, lng: Double): String? {
        return try {
            // 前置检查：无地理编码服务的设备（多数国产无 GMS ROM）直接跳过，
            // 避免阻塞到网络超时（曾导致记一笔页面卡死 ANR）
            if (!Geocoder.isPresent()) return null
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
}
