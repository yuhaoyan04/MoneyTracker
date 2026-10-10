package com.mudasir.smartledger.capture

/** 严格识别可接受的银行/钱包通知来源，避免放开所有应用后产生误记。 */
object FinancialNotificationPolicy {
    private val institutionWords = listOf(
        "银行", "信用社", "农信", "农商", "邮储", "银联", "云闪付",
        "工商", "工行", "建设", "建行", "农业", "农行", "中国银行", "中行",
        "交通银行", "交行", "招商", "招行", "民生", "浦发", "兴业", "光大",
        "华夏", "平安", "中信", "广发", "浙商", "渤海", "恒丰", "徽商",
        "北京银行", "上海银行", "江苏银行", "南京银行", "宁波银行", "成都银行",
        "数字人民币", "钱包", "Huawei Pay", "华为支付"
    )

    private val packageSignals = listOf(
        "bank", "icbc", "ccb", "abchina", "boc", "bankcomm", "cmbchina", "cmbc",
        "spdb", "cib", "cebbank", "hxb", "pingan", "citic", "cgbchina", "psbc",
        "unionpay", "ecny", "huawei.wallet"
    )

    fun isFinancialSource(packageName: String, appLabel: String?): Boolean {
        val pkg = packageName.lowercase()
        if (packageSignals.any { pkg.contains(it) }) return true
        val identity = appLabel.orEmpty()
        return institutionWords.any { identity.contains(it, ignoreCase = true) }
    }

    fun hasTransactionAmount(text: String): Boolean = listOf(
        Regex("[¥￥]\\s*[0-9][0-9,]*(?:\\.[0-9]{1,2})?"),
        Regex("[0-9][0-9,]*(?:\\.[0-9]{1,2})?\\s*(?:元|人民币|CNY|RMB)", RegexOption.IGNORE_CASE),
        Regex("(?:金额|实付|消费|支出|入账|到账|扣款|支付|转账)[:：\\s]*(?:[¥￥]|人民币|CNY|RMB)?\\s*[0-9][0-9,]*(?:\\.[0-9]{1,2})?", RegexOption.IGNORE_CASE)
    ).any { it.containsMatchIn(text) }
}
