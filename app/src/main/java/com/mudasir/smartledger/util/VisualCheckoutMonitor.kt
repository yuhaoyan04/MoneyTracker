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
import com.mudasir.smartledger.capture.VisualGuardTargets
import com.mudasir.smartledger.capture.VisualHandoffState

/** Experimental opt-in OCR for allowed shopping apps; screen data stays in memory. */
@RequiresApi(Build.VERSION_CODES.R)
class VisualCheckoutMonitor(private val service: AccessibilityCaptureService) {
    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private var busy = false
    private var lastAttempt = 0L
    private var checkoutSeenAt = 0L
    private var sourcePackage = ""
    private var verificationSource = ""
    private var generation = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            val elapsed = SystemClock.elapsedRealtime() - checkoutSeenAt
            if (checkoutSeenAt == 0L || elapsed !in 0..60_000L ||
                service.rootInActiveWindow?.packageName?.toString() != sourcePackage) return
            inspect(sourcePackage)
            handler.postDelayed(this, 4_000L)
        }
    }

    fun checkoutWindow(pkg: String, cls: String = "") {
        generation++
        if (verificationSource != pkg || !VisualGuardTargets.isJdOrderAfterVerification(pkg, cls)) VisualPaymentHandoff.clear()
        verificationSource = ""
        sourcePackage = pkg
        checkoutSeenAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 1_000L)
    }
    fun ensureCheckoutWindow(pkg: String, cls: String) {
        if (pkg != sourcePackage || checkoutSeenAt == 0L || SystemClock.elapsedRealtime() - checkoutSeenAt > 4_000L) checkoutWindow(pkg, cls)
    }
    fun pauseForVerification(pkg: String) {
        generation++
        verificationSource = if (pkg == sourcePackage) pkg else ""
        checkoutSeenAt = 0L
        handler.removeCallbacks(refresh)
        if (verificationSource.isEmpty()) VisualPaymentHandoff.clear()
    }
    fun leaveCheckout(preserveHandoff: Boolean = false) {
        generation++
        checkoutSeenAt = 0L
        sourcePackage = ""
        verificationSource = ""
        handler.removeCallbacks(refresh)
        if (!preserveHandoff) VisualPaymentHandoff.clear()
    }

    fun inspect(pkg: String) {
        val now = SystemClock.elapsedRealtime()
        val prefs = service.getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
        if (pkg != sourcePackage || !prefs.getBoolean("guard_enabled", false) ||
            !VisualGuardTargets.sourceAllowed(pkg, prefs.getBoolean("guard_visual_order_pages_enabled", false),
                prefs.getBoolean("guard_visual_multi_app_enabled", false)) || now - checkoutSeenAt !in 0..60_000L ||
            busy || now - lastAttempt < 2_500L) return
        busy = true
        lastAttempt = now
        val capturedGeneration = generation
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onFailure(errorCode: Int) {
                    busy = false
                    if (generation == capturedGeneration) {
                        VisualPaymentHandoff.clear()
                        PurchaseGuardService.recordDetection(pkg, "", "屏幕识别截图失败：$errorCode")
                    }
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
                                if (generation != capturedGeneration || current != pkg || !active.getBoolean("guard_enabled", false) ||
                                    !VisualGuardTargets.sourceAllowed(pkg, active.getBoolean("guard_visual_order_pages_enabled", false),
                                        active.getBoolean("guard_visual_multi_app_enabled", false)) || SystemClock.elapsedRealtime() - now > 8_000L) return@addOnSuccessListener
                                val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                                    val b = line.boundingBox ?: return@mapNotNull null
                                    VisualText(line.text, b.left, b.top, b.right, b.bottom)
                                }
                                val value = VisualCheckoutParser.amount(lines, width, height)
                                if (value != null && CheckoutPageParser.exceedsThreshold(value.toDouble(), GuardThreshold.read(active))) {
                                    VisualPaymentHandoff.remember(pkg, value.toDouble())
                                    PurchaseGuardService.recordDetection(pkg, "", "屏幕识别：识别到超门槛付款候选")
                                } else if (value != null) {
                                    VisualPaymentHandoff.clear()
                                    PurchaseGuardService.recordDetection(pkg, "", "屏幕识别：金额未超过门槛")
                                } else {
                                    VisualPaymentHandoff.clear()
                                    PurchaseGuardService.recordDetection(pkg, "", "屏幕识别：没有可靠付款金额")
                                }
                            }
                            .addOnFailureListener(service.mainExecutor) {
                                if (generation == capturedGeneration) {
                                    VisualPaymentHandoff.clear()
                                    GuardDiagnosticLog.failure(it)
                                    PurchaseGuardService.recordDetection(pkg, "", "屏幕识别失败")
                                }
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
    private val state = VisualHandoffState()
    @Synchronized fun remember(pkg: String, value: Double) = state.remember(pkg, value, SystemClock.elapsedRealtime())
    @Synchronized fun clear() = state.clear()
    @Synchronized fun observe(pkg: String, cls: String): Double? = state.observe(pkg, cls, SystemClock.elapsedRealtime())
}
