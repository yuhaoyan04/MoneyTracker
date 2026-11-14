package com.mudasir.smartledger.util

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.Display
import androidx.annotation.RequiresApi
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mudasir.smartledger.capture.CheckoutPageParser
import com.mudasir.smartledger.capture.GuardThreshold
import com.mudasir.smartledger.capture.VisualCheckoutParser
import com.mudasir.smartledger.capture.VisualText
import com.mudasir.smartledger.capture.VisualGuardTargets
import com.mudasir.smartledger.capture.VisualHandoffState
import com.mudasir.smartledger.capture.PendingOcrClear
import com.mudasir.smartledger.capture.DeferredTaobaoCapture
import com.mudasir.smartledger.capture.AlipayVisualPolicy
import com.mudasir.smartledger.capture.StableAlipayAmount

/** Experimental opt-in OCR for allowed shopping apps; screen data stays in memory. */
@RequiresApi(Build.VERSION_CODES.R)
class VisualCheckoutMonitor(private val service: AccessibilityCaptureService) {
    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val latinRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var latinStarted = false
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
    private var jdCashierStable = StableAlipayAmount()
    private var lastJdStatsAt = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            val elapsed = SystemClock.elapsedRealtime() - checkoutSeenAt
            if (checkoutSeenAt == 0L || elapsed !in 0..60_000L ||
                service.rootInActiveWindow?.packageName?.toString() != sourcePackage) return
            inspect(sourcePackage)
            handler.postDelayed(this, if (AlipayVisualPolicy.isJdCashier(sourcePackage, sourceClass)) 1_200L else 4_000L)
        }
    }

    fun checkoutWindow(pkg: String, cls: String = "") {
        val now = SystemClock.elapsedRealtime()
        if (VisualGuardTargets.isFreshDuplicateCheckout(pkg, cls, sourcePackage, sourceClass, lastQualifiedAt, now)) {
            PurchaseGuardService.recordDetection(pkg, "", "屏幕识别：保留刚识别的同一付款页候选")
            return
        }
        generation++
        jdCashierStable = StableAlipayAmount()
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
        handler.postDelayed(refresh, if (AlipayVisualPolicy.isJdCashier(pkg, cls)) 400L else 1_000L)
    }
    /** Changing payment selection invalidates any amount read before the click. */
    fun onJdCashierClick(isPayAction: Boolean) {
        if (!AlipayVisualPolicy.isJdCashier(sourcePackage, sourceClass) || isPayAction) return
        generation++
        jdCashierStable = StableAlipayAmount()
        lastQualifiedAt = 0L
        lastAttempt = 0L
        VisualPaymentHandoff.clear()
        PurchaseGuardService.recordDetection(sourcePackage, "", "京东收银台：付款选项变化，清除金额候选")
        val ticket = generation
        listOf(300L, 1_200L).forEach { delay -> handler.postDelayed({
            if (generation == ticket && sourcePackage == AlipayVisualPolicy.JD) inspect(sourcePackage)
        }, delay) }
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

    fun rememberOrderButton(pkg: String, value: Double) {
        VisualPaymentHandoff.remember(pkg, value, 5_000L)
        deferredPending = true
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
        val jdCashierMode = AlipayVisualPolicy.isJdCashier(sourcePackage, sourceClass)
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
        if (now - lastAttempt < (if (fast || jdCashierMode) 650L else 2_500L)) return
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
                        if (!deferredPending) VisualPaymentHandoff.clear()
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
                    val jdCrop = if (jdCashierMode) runCatching {
                        val base = Bitmap.createBitmap(bitmap, width / 5, height * 8 / 100,
                            width * 3 / 5, height * 20 / 100)
                        try { Bitmap.createScaledBitmap(base, base.width * 2, base.height * 2, true) }
                        finally { base.recycle() }
                    }.getOrNull() else null
                    try {
                        val fullTask = recognizer.process(InputImage.fromBitmap(bitmap, 0))
                        val cropTask = jdCrop?.let { recognizer.process(InputImage.fromBitmap(it, 0)) }
                        val latinTask = jdCrop?.let { latinStarted = true; latinRecognizer.process(InputImage.fromBitmap(it, 0)) }
                        val both = if (cropTask != null && latinTask != null) Tasks.whenAllComplete(fullTask, cropTask, latinTask) else Tasks.whenAllComplete(fullTask)
                        both.addOnSuccessListener(service.mainExecutor) {
                                if (!fullTask.isSuccessful) {
                                    fullTask.exception?.let(GuardDiagnosticLog::failure)
                                    jdCashierStable = StableAlipayAmount()
                                    VisualPaymentHandoff.clear()
                                    PurchaseGuardService.recordDetection(pkg, "", "屏幕识别失败")
                                    return@addOnSuccessListener
                                }
                                val result = fullTask.result
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
                                val priceText = if (cropTask?.isSuccessful == true) cropTask.result.text else null
                                val latinPriceText = if (latinTask?.isSuccessful == true) latinTask.result.text else null
                                val latinScreenLines = if (latinTask?.isSuccessful == true && jdCrop != null) {
                                    val cropLeft = width / 5; val cropTop = height * 8 / 100
                                    val cropWidth = width * 3 / 5; val cropHeight = height * 20 / 100
                                    latinTask.result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                                        val b = line.boundingBox ?: return@mapNotNull null
                                        VisualText(line.text,
                                            cropLeft + b.left * cropWidth / jdCrop.width,
                                            cropTop + b.top * cropHeight / jdCrop.height,
                                            cropLeft + b.right * cropWidth / jdCrop.width,
                                            cropTop + b.bottom * cropHeight / jdCrop.height)
                                    }
                                } else emptyList()
                                if (jdCashierMode && SystemClock.elapsedRealtime() - lastJdStatsAt >= 3_000L) {
                                    lastJdStatsAt = SystemClock.elapsedRealtime()
                                    GuardDiagnosticLog.jdPriceCropStats(priceText, cropTask?.isSuccessful == true)
                                    GuardDiagnosticLog.jdPriceCropStats(latinPriceText, latinTask?.isSuccessful == true, "latin")
                                    GuardDiagnosticLog.jdPriceLineStats(lines.filter { it.top in (height * 8 / 100)..(height * 28 / 100) }, width, height, "full")
                                    if (latinTask?.isSuccessful == true && jdCrop != null) {
                                        val cropLines = latinTask.result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                                            val b = line.boundingBox ?: return@mapNotNull null
                                            VisualText(line.text, b.left, b.top, b.right, b.bottom)
                                        }
                                        GuardDiagnosticLog.jdPriceLineStats(cropLines, jdCrop.width, jdCrop.height)
                                    }
                                }
                                val jdResult = if (jdCashierMode) VisualCheckoutParser.inspectJdCashier(lines, width, height, priceText, latinPriceText, latinScreenLines) else null
                                val rawValue = when {
                                    skuMode -> VisualCheckoutParser.amountForTaobaoSku(lines, width, height)
                                    jdCashierMode -> jdResult?.amount
                                    else -> VisualCheckoutParser.amount(lines, width, height)
                                }
                                val value = if (jdCashierMode) {
                                    val stable = jdCashierStable.observe(rawValue)
                                    if (stable == null) {
                                        VisualPaymentHandoff.clear()
                                        PurchaseGuardService.recordDetection(pkg, "", if (rawValue == null)
                                            "京东收银台诊断：${jdResult?.status?.name ?: "NO_PAGE"}" else "京东收银台：等待金额再次确认")
                                    }
                                    stable
                                } else rawValue
                                if (jdCashierMode && value == null) return@addOnSuccessListener
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
                                    if (jdCashierMode) VisualPaymentHandoff.rememberWechatOnly(pkg, value.toDouble())
                                    else VisualPaymentHandoff.remember(pkg, value.toDouble())
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
                                        if (!deferredPending && pendingOcrClear.shouldClear(ticket, generation == capturedGeneration)) {
                                            VisualPaymentHandoff.clear()
                                        }
                                    }, 500L)
                                    PurchaseGuardService.recordDetection(pkg, "", "屏幕识别：没有可靠付款金额")
                                }
                            }
                            .addOnFailureListener(service.mainExecutor) {
                                if (generation == capturedGeneration) {
                                    pendingOcrClear.cancel()
                                    if (!deferredPending) VisualPaymentHandoff.clear()
                                    GuardDiagnosticLog.failure(it)
                                    PurchaseGuardService.recordDetection(pkg, "", "屏幕识别失败")
                                }
                            }
                            .addOnCompleteListener(service.mainExecutor) { jdCrop?.recycle(); bitmap.recycle(); busy = false }
                    } catch (error: Exception) {
                        jdCrop?.recycle(); bitmap.recycle(); busy = false
                        GuardDiagnosticLog.failure(error)
                    }
                }
            })
        } catch (error: Exception) {
            busy = false
            GuardDiagnosticLog.failure(error)
        }
    }

    fun close() { deferredCapture.invalidate(); handler.removeCallbacks(refresh); if (recognizerInitialized()) recognizer.close(); if (latinStarted) latinRecognizer.close() }
    private fun recognizerInitialized() = lastAttempt != 0L
}

/** Short-lived, in-memory association. A WeChat entry alone never supplies an amount. */
object VisualPaymentHandoff {
    private val state = VisualHandoffState()
    @Synchronized fun remember(pkg: String, value: Double, validForMillis: Long = 20_000L) = state.remember(pkg, value, SystemClock.elapsedRealtime(), validForMillis)
    @Synchronized fun rememberWechatOnly(pkg: String, value: Double) = state.remember(pkg, value, SystemClock.elapsedRealtime(), 5_000L, onlyWechat = true)
    @Synchronized fun clear() = state.clear()
    @Synchronized fun observe(pkg: String, cls: String): Double? = state.observe(pkg, cls, SystemClock.elapsedRealtime())
}
