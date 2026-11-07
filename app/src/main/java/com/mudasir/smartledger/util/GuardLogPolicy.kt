package com.mudasir.smartledger.util

import com.mudasir.smartledger.capture.CheckoutPageParser

/** Converts diagnostics to a small allowlist; never forwards page text or exception messages. */
object GuardLogPolicy {
    private val idPattern = Regex("[A-Za-z_.$][A-Za-z0-9_.$]{0,199}")
    private val longDigits = Regex("[0-9]{4,}")
    private val evidenceNames = mapOf("节点" to "nodes", "操作" to "actions", "应付标签" to "payable_labels",
        "标签独立" to "split_labels", "合计标签" to "subtotals", "按钮含金额" to "action_amounts", "列表标签" to "order_tabs",
        "裸金额" to "standalone_numbers", "标签后数字" to "numeric_after_label", "同层邻近" to "near_sibling", "确认订单标题" to "confirm_order_titles")
    private val boolNames = mapOf("订单列表" to "order_list", "结果页" to "outcome", "attached" to "attached", "shown" to "shown")
    private val numericPatterns = evidenceNames.map { (name, key) ->
        key to Regex("(?:^|[，,；;]\\s*)${Regex.escape(name)}=(\\d{1,3})(?!\\d)")
    }
    private val booleanPatterns = boolNames.map { (name, key) ->
        key to Regex("(?:^|[，,；;]\\s*)${Regex.escape(name)}=(true|false)(?![A-Za-z])")
    }

    fun safePackage(pkg: String, ownPackage: String): String = when {
        pkg == ownPackage -> "self"
        CheckoutPageParser.isSupported(pkg) -> pkg
        else -> "other"
    }

    fun safeClass(cls: String): String = cls.takeIf {
        idPattern.matches(it) && !longDigits.containsMatchIn(it)
    } ?: ""

    /** Recognize only fixed labels, never returning the supplied text. */
    fun clickRole(texts: List<String>): String = when {
        texts.any { it.trim() in setOf("提交订单", "确认下单", "提交并支付") } -> "SUBMIT_ORDER"
        texts.any { it.trim() in setOf("确认付款", "立即付款", "继续付款", "确认支付", "立即支付", "确认并支付") } -> "PAY_ACTION"
        texts.any { it.trim() in setOf("去付款", "去支付") } -> "GO_TO_PAY"
        else -> "OTHER"
    }

    fun status(message: String): String = when {
        message.startsWith("收到无障碍窗口事件") -> "ACCESSIBILITY_WINDOW"
        message.startsWith("屏幕识别截图失败") -> "OCR_SCREENSHOT_FAILED"
        message.startsWith("支付宝屏幕识别截图失败") -> "ALIPAY_OCR_SCREENSHOT_FAILED"
        message.startsWith("支付宝屏幕识别：等待稳定付款金额") -> "ALIPAY_OCR_WAITING"
        message.startsWith("支付宝屏幕识别：金额超过门槛") -> "ALIPAY_OCR_QUALIFIED"
        message.startsWith("支付宝屏幕识别：金额未超过门槛") -> "ALIPAY_OCR_BELOW_THRESHOLD"
        message.startsWith("支付宝入口：付款操作已接收") -> "ALIPAY_ENTRY_CLICK_ACCEPTED"
        message.startsWith("支付宝入口：缺少有效京东收银台") -> "ALIPAY_ENTRY_CLICK_REJECTED"
        message.startsWith("支付宝入口：已匹配付款窗口") -> "ALIPAY_ENTRY_WINDOW_ACCEPTED"
        message.startsWith("支付宝入口：没有有效付款操作") -> "ALIPAY_ENTRY_WINDOW_REJECTED"
        message.startsWith("屏幕识别：识别到超门槛付款候选") -> "OCR_QUALIFIED"
        message.startsWith("屏幕识别：订单弹窗触发快速检查") -> "OCR_DIALOG_FAST_CHECK"
        message.startsWith("屏幕识别：通过订单列表结构启动检查") -> "OCR_ORDER_LIST_STRUCTURE"
        message.startsWith("屏幕识别：弹窗检查等待前次识别") -> "OCR_DIALOG_BUSY"
        message.startsWith("屏幕识别：弹窗截图已请求") -> "OCR_DIALOG_CAPTURE_REQUESTED"
        message.startsWith("屏幕识别：弹窗结果过期已丢弃") -> "OCR_DIALOG_RESULT_STALE"
        message.startsWith("屏幕识别：弹窗结果过期（来源应用）") -> "OCR_DIALOG_STALE_SOURCE"
        message.startsWith("屏幕识别：弹窗结果过期（微信）") -> "OCR_DIALOG_STALE_WECHAT"
        message.startsWith("屏幕识别：弹窗结果过期（其他窗口）") -> "OCR_DIALOG_STALE_OTHER"
        message.startsWith("屏幕识别：弹窗截图不属于有效付款流程") -> "OCR_DIALOG_CAPTURE_STALE"
        message.startsWith("屏幕识别：延迟结果补接已观察收银台") -> "OCR_DEFERRED_REPLAY_CASHIER"
        message.startsWith("屏幕识别：延迟结果等待微信收银台") -> "OCR_DEFERRED_WAIT_CASHIER"
        message.startsWith("屏幕识别：延迟结果等待微信入口") -> "OCR_DEFERRED_WAIT_ENTRY"
        message.startsWith("屏幕识别：延迟结果已关联付款流程") -> "OCR_DEFERRED_QUALIFIED"
        message.startsWith("屏幕识别：延迟结果没有超门槛金额") -> "OCR_DEFERRED_NOT_QUALIFIED"
        message.startsWith("结构化识别：单笔弹窗候选等待支付入口") -> "STRUCTURED_DIALOG_HANDOFF_ARMED"
        message.startsWith("订单列表：已关联所点付款按钮与实付金额") -> "ORDER_BUTTON_HANDOFF_ARMED"
        message.startsWith("订单列表：付款按钮快照已就绪") -> "ORDER_BUTTON_CACHE_READY"
        message.startsWith("订单列表：点击时没有可用快照") -> "ORDER_BUTTON_CLICK_NO_CACHE"
        message.startsWith("订单列表：点击来源不可读取") -> "ORDER_BUTTON_CLICK_NO_SOURCE"
        message.startsWith("订单列表：点击快照已过期") -> "ORDER_BUTTON_CLICK_STALE"
        message.startsWith("订单列表：点击未匹配唯一按钮") -> "ORDER_BUTTON_CLICK_NO_MATCH"
        message.startsWith("订单列表：点击金额未超过门槛") -> "ORDER_BUTTON_CLICK_BELOW_THRESHOLD"
        message.startsWith("订单列表：点击匹配成功") -> "ORDER_BUTTON_CLICK_MATCH"
        message.startsWith("屏幕识别：保留刚识别的同一付款页候选") -> "OCR_FRESH_DUPLICATE_PRESERVED"
        message.startsWith("屏幕识别：金额未超过门槛") -> "OCR_BELOW_THRESHOLD"
        message.startsWith("屏幕识别：没有可靠付款金额") -> "OCR_NO_CANDIDATE"
        message.startsWith("屏幕识别：已交接微信付款页") -> "OCR_WECHAT_HANDOFF"
        message.startsWith("屏幕识别：微信窗口尚不可用") -> "OCR_WECHAT_WINDOW_NOT_READY"
        message.startsWith("屏幕识别：已交接支付页面") -> "OCR_PAYMENT_HANDOFF"
        message.startsWith("屏幕识别：支付窗口尚不可用") -> "OCR_PAYMENT_WINDOW_NOT_READY"
        message.startsWith("屏幕识别：支付窗口未及时成为前台") -> "OCR_PAYMENT_WINDOW_TIMEOUT"
        message.startsWith("屏幕识别：无可靠超门槛金额") -> "OCR_NOT_QUALIFIED"
        message.startsWith("屏幕识别失败") -> "OCR_ERROR"
        message.startsWith("窗口候选") -> "WINDOW_CANDIDATE"
        message.startsWith("无障碍检查异常") -> "INSPECTION_ERROR"
        message.startsWith("当前活动窗口根节点不可读取") -> "NO_ACTIVE_ROOT"
        message.startsWith("页面未识别为付款确认页") -> "NOT_CHECKOUT"
        message.startsWith("当前窗口没有可读取的文字节点") -> "NO_ACCESSIBLE_TEXT"
        message.startsWith("付款页候选：未识别到明确实付金额") -> "NO_AMOUNT"
        message.startsWith("付款页候选：金额冲突") -> "AMBIGUOUS"
        message.startsWith("付款金额未超过门槛") || message.startsWith("金额未超过提醒门槛") -> "BELOW_THRESHOLD"
        message.startsWith("付款金额超过门槛") -> "QUALIFIED"
        message.startsWith("使用情况类名匹配") -> "USAGE_CLASS_MATCH"
        message.startsWith("使用情况类名未匹配") -> "USAGE_CLASS_NO_MATCH"
        message.startsWith("检测到支付应用切换") -> "PAYMENT_APP_TRANSITION"
        message.startsWith("支付页面候选") -> "USAGE_CANDIDATE"
        message.startsWith("提醒未开启") -> "GUARD_DISABLED"
        message.startsWith("后台启动服务失败") -> "SERVICE_START_ERROR"
        message.startsWith("守护服务已接收提醒请求") -> "SERVICE_REQUEST_POSTED"
        message.startsWith("尝试启动守护服务") -> "SERVICE_START_REQUESTED"
        message.startsWith("无障碍兜底：没有活动窗口") -> "FALLBACK_NO_ROOT"
        message.startsWith("无障碍兜底：开关或应用已变化") -> "FALLBACK_CONTEXT_CHANGED"
        message.startsWith("无障碍兜底：页面或金额已变化") -> "FALLBACK_PAGE_CHANGED"
        message.startsWith("无障碍兜底：已调用通知") -> "FALLBACK_NOTIFICATION_REQUESTED"
        message.startsWith("无障碍兜底失败") -> "FALLBACK_ERROR"
        message.startsWith("已沿用刚确认") -> "APPROVED_HANDOFF"
        message.startsWith("近期已放行") -> "APPROVAL_COOLDOWN"
        message.startsWith("短时去重") -> "DEDUPLICATED"
        message.startsWith("付款页面或金额已变化") -> "STALE_PAGE_CLOSED"
        message.startsWith("已进入设置") -> "SETTINGS_CLOSED_OVERLAY"
        message.startsWith("离开原应用，关闭") -> "LEFT_APP_CLOSED_OVERLAY"
        message.startsWith("离开原应用，取消") -> "LEFT_APP_CANCELLED"
        message.startsWith("页面已切换") -> "STALE_REQUEST_CANCELLED"
        message.startsWith("提醒开关或门槛已变化") -> "SETTINGS_CHANGED_CANCELLED"
        message.startsWith("无悬浮窗权限") -> "OVERLAY_PERMISSION_MISSING"
        message.startsWith("通知模式") -> "NOTIFICATION_MODE"
        message.startsWith("提醒失败") -> "PROMPT_ERROR"
        message.startsWith("系统已接受悬浮窗请求") -> "OVERLAY_ADDED"
        message.startsWith("浮层状态") -> "OVERLAY_STATE"
        message.startsWith("悬浮窗创建失败") -> "OVERLAY_ERROR"
        message.startsWith("用户点击继续") -> "USER_CONTINUE"
        message.startsWith("用户选择心愿") -> "USER_WISHLIST"
        message.startsWith("冷静计时结束") -> "COUNTDOWN_COMPLETE"
        message.startsWith("浮层超时关闭") -> "OVERLAY_TIMEOUT"
        else -> "OTHER"
    }

    fun evidence(message: String): Map<String, String> = buildMap {
        numericPatterns.forEach { (key, pattern) ->
            pattern.find(message)?.groupValues?.get(1)?.let { put(key, it) }
        }
        booleanPatterns.forEach { (key, pattern) ->
            pattern.find(message)?.groupValues?.get(1)?.let { put(key, it) }
        }
    }
}
