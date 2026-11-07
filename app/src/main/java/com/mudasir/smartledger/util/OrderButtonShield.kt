package com.mudasir.smartledger.util

import android.graphics.Color
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.mudasir.smartledger.capture.OrderButtonShieldPolicy
import com.mudasir.smartledger.capture.OrderPayCandidate
import java.math.BigDecimal

/** Small opt-in accessibility windows covering only verified high-value order buttons. */
class OrderButtonShield(private val service: AccessibilityCaptureService,
                        private val onTap: (OrderPayCandidate) -> Unit) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val views = linkedMapOf<OrderPayCandidate, View>()
    private var pending: OrderPayCandidate? = null
    private var pendingAt = 0L
    private var approved: OrderPayCandidate? = null
    private var approvedUntil = 0L

    fun update(candidates: List<OrderPayCandidate>, threshold: BigDecimal, enabled: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!enabled) { reset(); return }
        if (pending != null && (now - pendingAt < 2_000L || PurchaseGuardService.isOrderButtonPromptActive())) {
            return // Keep consuming the original button until the full reminder is ready.
        }
        pending = null
        val wanted = OrderButtonShieldPolicy.eligible(candidates, threshold, approved, approvedUntil, now).toSet()
        views.keys.toList().filterNot { it in wanted }.forEach(::remove)
        wanted.filterNot { it in views }.forEach(::add)
    }

    fun suspendFor(candidate: OrderPayCandidate) {
        pending = candidate; pendingAt = SystemClock.uptimeMillis()
    }

    fun approved(amount: Double) {
        val candidate = pending ?: return
        if (kotlin.math.abs(candidate.amount.toDouble() - amount) < 0.005) {
            approved = candidate; approvedUntil = SystemClock.uptimeMillis() + 20_000L
        }
        pending = null
    }

    fun cancelPending() { pending = null; removeViews() }
    fun hideForChange() { if (pending == null) removeViews() }
    fun removeViews() { views.keys.toList().forEach(::remove) }
    fun reset() { removeViews(); pending = null; approved = null; approvedUntil = 0L }

    private fun remove(candidate: OrderPayCandidate) {
        views.remove(candidate)?.let { runCatching { wm.removeViewImmediate(it) } }
    }

    private fun add(candidate: OrderPayCandidate) {
        val width = candidate.right - candidate.left
        val height = candidate.bottom - candidate.top
        if (width <= 0 || height <= 0) return
        val view = FrameLayout(service).apply {
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "高额付款，先想一想"
            addView(TextView(service).apply {
                text = "想一想"; textSize = 10f; setTextColor(Color.WHITE)
                setBackgroundColor(0xB3222733.toInt()); setPadding(6, 2, 6, 2)
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        }
        var downX = 0f; var downY = 0f; var moved = false
        val slop = ViewConfiguration.get(service).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> { if (kotlin.math.abs(event.x - downX) > slop || kotlin.math.abs(event.y - downY) > slop) moved = true; true }
                MotionEvent.ACTION_UP -> {
                    if (pending == null && !moved && event.x >= 0 && event.y >= 0 && event.x < width && event.y < height) {
                        runCatching { onTap(candidate) }.onFailure {
                            GuardDiagnosticLog.failure(it); reset()
                            android.widget.Toast.makeText(service, "提醒暂不可用，请重新点击", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    true
                }
                else -> true
            }
        }
        val params = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = candidate.left; y = candidate.top
            title = "MoneyTracker order reminder"
        }
        runCatching {
            wm.addView(view, params); views[candidate] = view
            PurchaseGuardService.recordDetection("com.taobao.taobao", "", "订单按钮保护：已添加按钮浮层")
        }
            .onFailure { GuardDiagnosticLog.failure(it); PurchaseGuardService.recordDetection("com.taobao.taobao", "", "订单按钮保护：无法添加浮层") }
    }
}
