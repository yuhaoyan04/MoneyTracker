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
import com.mudasir.smartledger.capture.PendingOcrClear
import com.mudasir.smartledger.capture.DeferredTaobaoCapture

/** Experimental opt-in OCR for allowed shopping apps; screen data stays in memory. */
@RequiresApi(Build.VERSION_CODES.R)
class VisualCheckoutMonitor(private val service: AccessibilityCaptureService) {
    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private var busy = false
    private var lastAttempt = 0L
    private var checkoutSeenAt = 0L
    private var sourcePackage = ""
    private var sourceClass = ""
    private var lastQualifiedAt = 0L
    private var verificationSource = ""
    private var generation = 0L
    private val pendingOcrClear = PendingOcrClear()
    private val deferredCapture = DeferredTaobaoCapture()
    private var dialogTicket = 0L
    private var deferredPending = false
    private var dialogOpenedAt = 0L
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
        val now = SystemClock.elapsedRealtime()
        if (VisualGuardTargets.isFreshDuplicateCheckout(pkg, cls, sourcePackage, sourceClass, lastQualifiedAt, now)) {
            PurchaseGuardService.recordDetection(pkg, "", "屏幕识别：保留刚识别的同一付款页候选")
            return
        }
        generation++
        deferredCapture.invalidate()
        deferredPending = false
        dialogOpenedAt = 0L
        if (verificationSource != pkg || !VisualGuardTargets.isJdOrderAfterVerification(pkg, cls)) VisualPaymentHandoff.clear()
        verificationSource = ""
        sourcePackage = pkg
        sourceClass = cls
        lastQualifiedAt = 0L
        checkoutSeenAt = now
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 1_000L)
    }
    fun ensureCheckoutWindow(pkg: String, cls: String) {
        if (pkg != sourcePackage || checkoutSeenAt == 0L || SystemClock.elapsedRealtime() - checkoutSeenAt > 4_000L) checkoutWindow(pkg, cls)
    }
    fun ensureTaobaoOrderList() {
        val pkg = VisualGuardTargets.TAOBAO
        val validClass = sourceClass == "taobao_order_list_nodes" || sourceClass.endsWith("TBOrderListActivity")
        if (pkg != sourcePackage || !validClass || checkoutSeenAt == 0L || SystemClock.elapsedRealtime() - checkoutSeenAt > 60_000L) {
            checkoutWindow(pkg, "taobao_order_list_nodes")
            PurchaseGuardService.recordDetection(pkg, "", "屏幕识别：通过订单列表结构启动检查")
        }
    }
    fun pauseForVerification(pkg: String) {
        generation++
        deferredCapture.invalidate()
        dialogOpenedAt = 0L
        verificationSource = if (pkg == sourcePackage) pkg else ""
        checkoutSeenAt = 0L
        handler.removeCallbacks(refresh)
        if (verificationSource.isEmpty()) VisualPaymentHandoff.clear()
    }
    fun leaveCheckout(preserveHandoff: Boolean = false) {
        generation++
        if (!preserveHandoff) { deferredCapture.invalidate(); deferredPending = false }
        dialogOpenedAt = 0L
        checkoutSeenAt = 0L
        sourcePackage = ""
        sourceClass = ""
        lastQualifiedAt = 0L
        verificationSource = ""
        handler.removeCallbacks(refresh)
        if (!preserveHandoff) VisualPaymentHandoff.clear()
    }

    /** A Taobao order-list dialog can appear and disappear between the regular OCR polls. */
    fun taobaoDialogOpened(pkg: String) {
        if (pkg != VisualGuardTargets.TAOBAO || pkg != sourcePackage || checkoutSeenAt == 0L) return
        val ticket = generation
        dialogOpenedAt = SystemClock.uptimeMillis()
        dialogTicket = deferredCapture.open(dialogOpenedAt)
        PurchaseGuardService.recordDetection(pkg, "", "屏幕识别：订单弹窗触发快速检查")
        listOf(200L, 600L, 1000L).forEach { delay ->
            handler.postDelayed({ if (ticket == generation) inspect(pkg, fast = true) }, delay)
        }
    }

    /** Retain only an explicit, unambiguous structured result from this single dialog. */
    fun rememberStructuredDialog(pkg: String, value: Double) {
        val prefs = service.getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
        if (!VisualGuardTargets.isRecentTaobaoOrderDialog(pkg, sourceClass, dialogOpenedAt, SystemClock.uptimeMillis()) ||
            sourcePackage != pkg || !prefs.getBoolean("guard_enabled", false) ||
            !prefs.getBoolean("guard_visual_order_pages_enabled", false) ||
            !CheckoutPageParser.exceedsThreshold(value, GuardThreshold.read(prefs))) return
        VisualPaymentHandoff.remember(pkg, value, 5_000L)
        deferredPending = true
        PurchaseGuardService.recordDetection(pkg, "", "结构化识别：单笔弹窗候选等待支付入口")
    }

    fun observedWindow(pkg: String, cls: String, at: Long, foreground: Boolean) {
        deferredCapture.window(pkg, cls, at, foreground)
        if (deferredPending && foreground && ((pkg != VisualGuardTargets.TAOBAO && pkg != VisualGuardTargets.WECHAT) ||
                (pkg == VisualGuardTargets.WECHAT && cls.endsWith("LauncherUI")))) {
            deferredPending = false
            VisualPaymentHandoff.clear()
        }
    }

    fun inspect(pkg: String, fast: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val prefs = service.getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
        val skuMode = VisualGuardTargets.isTaobaoSkuDialog(sourcePackage, sourceClass)
        if (VisualGuardTargets.skipBackgroundOrderListOcr(pkg, sourceClass, fast)) return
        if (pkg != sourcePackage || service.rootInActiveWindow?.packageName?.toString() != pkg ||
            !prefs.getBoolean("guard_enabled", false) ||
            (skuMode && !prefs.getBoolean("guard_visual_taobao_sku_enabled", false)) ||
            !VisualGuardTargets.sourceAllowed(pkg, prefs.getBoolean("guard_visual_order_pages_enabled", false),
                prefs.getBoolean("guard_visual_multi_app_enabled", false)) || now - checkoutSeenAt !in 0..60_000L) return
        if (busy) {
            if (fast) PurchaseGuardService.recordDetection(pkg, "", "屏幕识别：弹窗检查等待前次识别")
            return
        }
        if (now - lastAttempt < (if (fast) 650L else 2_500L)) return
        busy = true
        lastAttempt = now
        if (fast) PurchaseGuardService.recordDetection(pkg, "", "屏幕识别：弹窗截图已请求")
        val capturedGeneration = generation
        val capturedDialogTicket = dialogTicket
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onFailure(errorCode: Int) {
                    busy = false
                    if (generation == capturedGeneration) {
                        pendingOcrClear.cancel()
                        VisualPaymentHandoff.clear()
                        PurchaseGuardService.recordDetection(pkg, "", "屏幕识别截图失败：$errorCode")
                    }
                }
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val captureTime = screenshot.timestamp
                    val hardware = screenshot.hardwareBuffer
                    val current = service.rootInActiveWindow?.packageName?.toString()
                    if ((generation != capturedGeneration || current != pkg) &&
                        (!fast || pkg != VisualGuardTargets.TAOBAO || deferredCapture.accept(
                            capturedDialogTicket, captureTime, SystemClock.uptimeMillis(), current, allowSource = true) == null)) {
                        hardware.close(); busy = false
                        if (fast) PurchaseGuardService.recordDetection(pkg, "", "屏幕识别：弹窗截图不属于有效付款流程")
                        return
                    }
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
                                val deferred = if (fast && pkg == VisualGuardTargets.TAOBAO)
                                    deferredCapture.accept(capturedDialogTicket, captureTime, SystemClock.uptimeMillis(), current, allowSource = true) else null
                                if (((generation != capturedGeneration || current != pkg) && deferred == null) || !active.getBoolean("guard_enabled", false) ||
                                    (skuMode && !active.getBoolean("guard_visual_taobao_sku_enabled", false)) ||
                                    !VisualGuardTargets.sourceAllowed(pkg, active.getBoolean("guard_visual_order_pages_enabled", false),
                                        active.getBoolean("guard_visual_multi_app_enabled", false)) || SystemClock.elapsedRealtime() - now > 8_000L) {
                                    if (fast) PurchaseGuardService.recordDetection(pkg, "", when (current) {
                                        pkg -> "屏幕识别：弹窗结果过期（来源应用）"
                                        VisualGuardTargets.WECHAT -> "屏幕识别：弹窗结果过期（微信）"
                                        else -> "屏幕识别：弹窗结果过期（其他窗口）"
                                    })
                                    return@addOnSuccessListener
                                }
                                val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                                    val b = line.boundingBox ?: return@mapNotNull null
                                    VisualText(line.text, b.left, b.top, b.right, b.bottom)
                                }
                                val value = if (skuMode) VisualCheckoutParser.amountForTaobaoSku(lines, width, height)
                                    else VisualCheckoutParser.amount(lines, width, height)
                                if (deferred != null) {
                                    if (value != null && CheckoutPageParser.exceedsThreshold(value.toDouble(), GuardThreshold.read(active))) {
                                        VisualPaymentHandoff.remember(pkg, value.toDouble(), 5_000L)
                                        deferredPending = !deferred.cashier
                                        if (deferred.entry) VisualPaymentHandoff.observe(VisualGuardTargets.WECHAT, "OrderHandlerUI")
                                        if (deferred.cashier) VisualPaymentHandoff.observe(VisualGuardTargets.WECHAT, "WxaLiteAppTransparentLiteUI")?.let {
                                            PurchaseGuardService.onVisualPaymentHandoff(service.applicationContext, VisualGuardTargets.WECHAT, it)
                                        }
                                        PurchaseGuardService.recordDetection(pkg, "", when {
                                            deferred.cashier -> "屏幕识别：延迟结果补接已观察收银台"
                                            deferred.entry -> "屏幕识别：延迟结果等待微信收银台"
                                            else -> "屏幕识别：延迟结果等待微信入口"
                                        })
                                        PurchaseGuardService.recordDetection(pkg, "", "屏幕识别：延迟结果已关联付款流程")
                                    } else PurchaseGuardService.recordDetection(pkg, "", "屏幕识别：延迟结果没有超门槛金额")
                                    return@addOnSuccessListener
                                }
                                if (value != null && CheckoutPageParser.exceedsThreshold(value.toDouble(), GuardThreshold.read(active))) {
                                    pendingOcrClear.cancel()
                                    VisualPaymentHandoff.remember(pkg, value.toDouble())
                                    lastQualifiedAt = SystemClock.elapsedRealtime()
                                    PurchaseGuardService.recordDetection(pkg, "", "屏幕识别：识别到超门槛付款候选")
                                } else if (value != null) {
                                    pendingOcrClear.cancel()
                                    VisualPaymentHandoff.clear()
                                    lastQualifiedAt = 0L
                                    PurchaseGuardService.recordDetection(pkg, "", "屏幕识别：金额未超过门槛")
                                } else {
                                    lastQualifiedAt = 0L
                                    // A blank transition frame may arrive just before the cashier activity.
                                    // Keep the candidate only if that activity transition changes the generation
                                    // within 500 ms; otherwise clear it, preventing stale amounts on this page.
                                    val ticket = pendingOcrClear.schedule()
                                    handler.postDelayed({
                                        if (pendingOcrClear.shouldClear(ticket, generation == capturedGeneration)) {
                                            VisualPaymentHandoff.clear()
                                        }
                                    }, 500L)
                                    PurchaseGuardService.recordDetection(pkg, "", "屏幕识别：没有可靠付款金额")
                                }
                            }
                            .addOnFailureListener(service.mainExecutor) {
                                if (generation == capturedGeneration) {
                                    pendingOcrClear.cancel()
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

    fun close() { deferredCapture.invalidate(); handler.removeCallbacks(refresh); if (recognizerInitialized()) recognizer.close() }
    private fun recognizerInitialized() = lastAttempt != 0L
}

/** Short-lived, in-memory association. A WeChat entry alone never supplies an amount. */
object VisualPaymentHandoff {
    private val state = VisualHandoffState()
    @Synchronized fun remember(pkg: String, value: Double, validForMillis: Long = 20_000L) = state.remember(pkg, value, SystemClock.elapsedRealtime(), validForMillis)
    @Synchronized fun clear() = state.clear()
    @Synchronized fun observe(pkg: String, cls: String): Double? = state.observe(pkg, cls, SystemClock.elapsedRealtime())
}
