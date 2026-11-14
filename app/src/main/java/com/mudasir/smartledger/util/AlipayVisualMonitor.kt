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
        return p.getBoolean("guard_enabled", false) &&
            (if (multiSource == null) p.getBoolean("guard_visual_multi_app_enabled", false) else true) &&
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
                    val region = AlipayVisualPolicy.amountCrop(bitmap.width, bitmap.height)
                    if (region == null) { bitmap.recycle(); busy = false; retry(token); return }
                    val crop = try { Bitmap.createBitmap(bitmap, region.x, region.y, region.width, region.height) }
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
                                val evidence = AlipayVisualPolicy.cashierEvidence(lines, bitmap.height)
                                val candidates = if (evidence.pending && cropped != null)
                                    AlipayVisualPolicy.cropCandidates(cropped.text) else emptySet()
                                val amount = candidates.singleOrNull()
                                val confirmed = stable.observe(amount)
                                if (confirmed == null) {
                                    val detail = when {
                                        !fullTask.isSuccessful -> "全屏识别失败"
                                        !cropTask.isSuccessful -> "金额区域识别失败"
                                        evidence.outcome -> "识别到结果页"
                                        !evidence.paymentButton && !evidence.passwordOption -> "缺少付款按钮和密码选项"
                                        !evidence.paymentButton -> "缺少付款按钮"
                                        !evidence.passwordOption -> "缺少密码选项"
                                        candidates.isEmpty() -> "金额区域无候选"
                                        candidates.size > 1 -> "金额区域多个候选"
                                        else -> "等待第二次一致金额"
                                    }
                                    PurchaseGuardService.recordDetection(AlipayVisualPolicy.ALIPAY, "", "支付宝识别细节：$detail")
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
