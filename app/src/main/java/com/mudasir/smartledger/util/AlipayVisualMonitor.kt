package com.mudasir.smartledger.util

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import androidx.annotation.RequiresApi
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.mudasir.smartledger.capture.AlipayVisualPolicy
import com.mudasir.smartledger.capture.CheckoutPageParser
import com.mudasir.smartledger.capture.GuardThreshold
import com.mudasir.smartledger.capture.StableAlipayAmount
import com.mudasir.smartledger.capture.VisualText
import com.mudasir.smartledger.capture.VisualGuardTargets

/** Opt-in, bounded local OCR for a known JD-to-Alipay payment handoff. */
@RequiresApi(Build.VERSION_CODES.R)
class AlipayVisualMonitor(private val service: AccessibilityCaptureService) {
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private var generation = 0L
    private var busy = false
    private var startedAt = 0L
    private var attempts = 0
    private var stable = StableAlipayAmount()
    private var multiSource: String? = null

    fun start(source: String? = null) {
        cancel()
        multiSource = source
        if (!enabled()) return
        startedAt = SystemClock.elapsedRealtime()
        attempts = 0
        stable = StableAlipayAmount()
        val token = generation
        handler.postDelayed({ capture(token) }, 400L)
    }

    fun cancel() { generation++; handler.removeCallbacksAndMessages(null) }

    private fun enabled(): Boolean {
        val p = service.getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
        return p.getBoolean("guard_enabled", false) && p.getBoolean("guard_visual_multi_app_enabled", false) &&
            p.getBoolean("guard_visual_alipay_enabled", false) && (multiSource == null ||
                (p.getBoolean("guard_visual_alipay_multi_enabled", false) && VisualGuardTargets.sourceAllowed(multiSource!!,
                    p.getBoolean("guard_visual_order_pages_enabled", false), p.getBoolean("guard_visual_multi_app_enabled", false))))
    }

    private fun alive(token: Long): Boolean = token == generation && enabled() &&
        SystemClock.elapsedRealtime() - startedAt in 0..10_000L
    private fun current(token: Long): Boolean = alive(token) &&
        service.rootInActiveWindow?.packageName?.toString() == AlipayVisualPolicy.ALIPAY

    private fun retry(token: Long) {
        if (alive(token) && attempts < 6) handler.postDelayed({ capture(token) }, 900L)
    }

    private fun capture(token: Long) {
        if (!alive(token)) return
        if (busy) { retry(token); return }
        attempts++
        if (!current(token)) { retry(token); return }
        busy = true
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onFailure(errorCode: Int) {
                    busy = false
                    if (current(token)) {
                        stable.observe(null)
                        PurchaseGuardService.recordDetection(AlipayVisualPolicy.ALIPAY, "", "支付宝屏幕识别截图失败：$errorCode")
                        retry(token)
                    }
                }
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    val bitmap = try { Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false) }
                    catch (_: Exception) { null } finally { buffer.close() }
                    if (bitmap == null) { busy = false; retry(token); return }
                    val crop = try { Bitmap.createBitmap(bitmap, bitmap.width / 5, bitmap.height * 27 / 100,
                        bitmap.width * 3 / 5, bitmap.height * 10 / 100) }
                    catch (_: Exception) { bitmap.recycle(); busy = false; retry(token); return }
                    try {
                        val fullTask = recognizer.process(InputImage.fromBitmap(bitmap, 0))
                        val cropTask = recognizer.process(InputImage.fromBitmap(crop, 0))
                        Tasks.whenAllComplete(fullTask, cropTask).addOnCompleteListener(service.mainExecutor) {
                            try {
                                if (!current(token)) return@addOnCompleteListener
                                val full = if (fullTask.isSuccessful) fullTask.result else null
                                val cropped = if (cropTask.isSuccessful) cropTask.result else null
                                val lines = full?.textBlocks?.flatMap { it.lines }?.mapNotNull { line ->
                                    val b = line.boundingBox ?: return@mapNotNull null
                                    VisualText(line.text, b.left, b.top, b.right, b.bottom)
                                }.orEmpty()
                                val amount = if (AlipayVisualPolicy.isPendingCashier(lines, bitmap.height))
                                    cropped?.text?.let(AlipayVisualPolicy::cropAmount) else null
                                val confirmed = stable.observe(amount)
                                if (confirmed == null) {
                                    PurchaseGuardService.recordDetection(AlipayVisualPolicy.ALIPAY, "", "支付宝屏幕识别：等待稳定付款金额")
                                    retry(token)
                                } else {
                                    val p = service.getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
                                    if (CheckoutPageParser.exceedsThreshold(confirmed.toDouble(), GuardThreshold.read(p))) {
                                        PurchaseGuardService.recordDetection(AlipayVisualPolicy.ALIPAY, "", "支付宝屏幕识别：金额超过门槛")
                                        PurchaseGuardService.onVisualPaymentHandoff(service.applicationContext, AlipayVisualPolicy.ALIPAY, confirmed.toDouble(), multiSource)
                                    } else PurchaseGuardService.recordDetection(AlipayVisualPolicy.ALIPAY, "", "支付宝屏幕识别：金额未超过门槛")
                                    cancel()
                                }
                            } finally { crop.recycle(); bitmap.recycle(); busy = false }
                        }
                    } catch (_: Exception) { crop.recycle(); bitmap.recycle(); busy = false; retry(token) }
                }
            })
        } catch (_: Exception) { busy = false; retry(token) }
    }

    fun close() { cancel(); recognizer.close() }
}
