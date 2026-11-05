package com.mudasir.smartledger.util

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.Display
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.mudasir.smartledger.capture.CheckoutPageParser
import com.mudasir.smartledger.capture.GuardThreshold
import com.mudasir.smartledger.capture.VisualCheckoutParser
import com.mudasir.smartledger.capture.VisualText

/** Experimental opt-in OCR, limited to Taobao checkout; screen data never leaves memory. */
@RequiresApi(Build.VERSION_CODES.R)
class VisualCheckoutMonitor(private val service: AccessibilityCaptureService) {
    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private var busy = false
    private var lastAttempt = 0L
    private var checkoutSeenAt = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            val elapsed = SystemClock.elapsedRealtime() - checkoutSeenAt
            if (checkoutSeenAt == 0L || elapsed !in 0..60_000L ||
                service.rootInActiveWindow?.packageName?.toString() != "com.taobao.taobao") return
            inspect("com.taobao.taobao")
            handler.postDelayed(this, 4_000L)
        }
    }

    fun checkoutWindow() {
        checkoutSeenAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 1_000L)
    }
    fun leaveCheckout(preserveHandoff: Boolean = false) {
        checkoutSeenAt = 0L
        handler.removeCallbacks(refresh)
        if (!preserveHandoff) VisualPaymentHandoff.clear()
    }

    fun inspect(pkg: String) {
        val now = SystemClock.elapsedRealtime()
        val prefs = service.getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
        if (pkg != "com.taobao.taobao" || !prefs.getBoolean("guard_enabled", false) ||
            !prefs.getBoolean("guard_visual_enabled", false) || now - checkoutSeenAt !in 0..60_000L ||
            busy || now - lastAttempt < 2_500L) return
        busy = true
        lastAttempt = now
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onFailure(errorCode: Int) {
                    busy = false
                    VisualPaymentHandoff.clear()
                    PurchaseGuardService.recordDetection(pkg, "", "屏幕识别截图失败：$errorCode")
                }
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val hardware = screenshot.hardwareBuffer
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(hardware, screenshot.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (error: Exception) {
                        GuardDiagnosticLog.failure(error); null
                    } finally { hardware.close() }
                    if (bitmap == null) { busy = false; return }
                    val width = bitmap.width
                    val height = bitmap.height
                    try {
                        recognizer.process(InputImage.fromBitmap(bitmap, 0))
                            .addOnSuccessListener(service.mainExecutor) { result ->
                                val current = service.rootInActiveWindow?.packageName?.toString()
                                val active = service.getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
                                if (current != pkg || !active.getBoolean("guard_enabled", false) ||
                                    !active.getBoolean("guard_visual_enabled", false) || SystemClock.elapsedRealtime() - now > 8_000L) return@addOnSuccessListener
                                val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                                    val b = line.boundingBox ?: return@mapNotNull null
                                    VisualText(line.text, b.left, b.top, b.right, b.bottom)
                                }
                                val value = VisualCheckoutParser.amount(lines, width, height)
                                if (value != null && CheckoutPageParser.exceedsThreshold(value.toDouble(), GuardThreshold.read(active))) {
                                    VisualPaymentHandoff.remember(value.toDouble())
                                    PurchaseGuardService.recordDetection(pkg, "", "屏幕识别：识别到超门槛付款候选")
                                } else {
                                    VisualPaymentHandoff.clear()
                                    PurchaseGuardService.recordDetection(pkg, "", "屏幕识别：无可靠超门槛金额")
                                }
                            }
                            .addOnFailureListener(service.mainExecutor) {
                                VisualPaymentHandoff.clear()
                                GuardDiagnosticLog.failure(it)
                                PurchaseGuardService.recordDetection(pkg, "", "屏幕识别失败")
                            }
                            .addOnCompleteListener(service.mainExecutor) { bitmap.recycle(); busy = false }
                    } catch (error: Exception) {
                        bitmap.recycle(); busy = false
                        GuardDiagnosticLog.failure(error)
                    }
                }
            })
        } catch (error: Exception) {
            busy = false
            GuardDiagnosticLog.failure(error)
        }
    }

    fun close() { handler.removeCallbacks(refresh); if (recognizerInitialized()) recognizer.close() }
    private fun recognizerInitialized() = lastAttempt != 0L
}

/** Short-lived, in-memory association. A WeChat entry alone never supplies an amount. */
object VisualPaymentHandoff {
    private var amount: Double? = null
    private var at = 0L
    private var entryAt = 0L
    @Synchronized fun remember(value: Double) { amount = value; at = SystemClock.elapsedRealtime(); entryAt = 0L }
    @Synchronized fun clear() { amount = null; at = 0; entryAt = 0 }
    @Synchronized fun observe(pkg: String, cls: String): Double? {
        val now = SystemClock.elapsedRealtime()
        if (amount == null || now - at !in 0..20_000L) { clear(); return null }
        if (pkg != "com.tencent.mm") return null
        if (cls.endsWith("OrderHandlerUI")) { entryAt = now; return null }
        if (entryAt != 0L && now - entryAt in 0..10_000L && cls.endsWith("WxaLiteAppTransparentLiteUI")) {
            val result = amount; clear(); return result
        }
        return null
    }
}
