package com.mudasir.smartledger.capture

/**
 * 采集数据源白名单（纯逻辑，可单元测试）。
 *
 * v2.37.0 按用户要求收紧：只记录 微信 / 支付宝 / 银行卡（含云闪付）三类数据源。
 * 淘宝/京东/美团/拼多多等购物 App 推送不再进入采集管道 —— 广告金额噪音在源头切断。
 * （银行支付走短信通道 BankSmsReceiver，与购物 App 无关）
 */
object CaptureWhitelist {

    /** 通知通道允许采集的包名（白名单语义：不在此列一律忽略） */
    val packages = setOf(
        "com.tencent.mm",                  // 微信
        "com.eg.android.AlipayGphone",     // 支付宝
        "com.eg.android.AlipayGphone.rc",  // 支付宝变体
        "com.eg.android.AlipayGphone.lite",// 支付宝 lite
        "com.unionpay"                     // 云闪付（银行卡支付工具）
    )

    /** 已知银行 App 包名 → 页面抓取时渠道归类为「银行卡」 */
    val bankPackages = setOf(
        "com.icbc",                       // 工商银行
        "com.chinamworld.main",           // 建设银行
        "com.chinamworld.bocmbci",        // 中国银行
        "com.android.bankabc",            // 农业银行
        "com.bankcomm.Bankcomm",          // 交通银行
        "cmb.pb",                         // 招商银行
        "com.yitong.mbank.psbc",          // 邮储银行
        "com.ecitic.bank.mobile",         // 中信银行
        "com.cebbank.mobile.cemb",        // 光大银行
        "com.spdb.mobilebank.per",        // 浦发银行
        "com.cgbchina.xpt"                // 广发银行
    )

    fun isWatched(pkg: String): Boolean = pkg in packages

    /** 页面抓取渠道归类：银行 App → 银行卡（未知包名兜底「其他」，收件箱可改） */
    fun channelOf(pkg: String): String = when {
        pkg == "com.tencent.mm" -> "微信支付"
        pkg.startsWith("com.eg.android.AlipayGphone") -> "支付宝"
        pkg == "com.unionpay" -> "云闪付"
        pkg in bankPackages -> "银行卡"
        // 未收录的银行 App 启发式：包名含 bank/icbc/cmb/psbc 等
        listOf("bank", "icbc", "cmb", "psbc", "cgbchina", "cebbank", "ecitic", "spdb", "chinamworld")
            .any { pkg.lowercase().contains(it) } -> "银行卡"
        else -> "其他"
    }
}
