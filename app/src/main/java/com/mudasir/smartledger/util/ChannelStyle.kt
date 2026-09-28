package com.mudasir.smartledger.util

import android.content.Context
import androidx.core.content.ContextCompat
import com.mudasir.smartledger.R

/** 渠道 → 颜色映射，用于列表头像与统计标签。 */
object ChannelStyle {

    fun color(context: Context, channel: String): Int {
        val resId = when {
            channel.contains("微信") -> R.color.channel_wechat
            channel.contains("支付宝") || channel.contains("花呗") || channel.contains("蚂蚁") -> R.color.channel_alipay
            channel.contains("京东") -> R.color.channel_jd
            channel.contains("淘宝") || channel.contains("天猫") -> R.color.channel_taobao
            channel.contains("银行") || channel.contains("卡") -> R.color.channel_bank
            channel.contains("现金") -> R.color.channel_cash
            else -> R.color.teal_main
        }
        return ContextCompat.getColor(context, resId)
    }

    fun initial(channel: String): String {
        return when {
            channel.contains("微信") -> "微"
            channel.contains("支付宝") -> "付"
            channel.contains("京东") -> "东"
            channel.contains("淘宝") || channel.contains("天猫") -> "淘"
            channel.contains("银行") || channel.contains("卡") -> "行"
            channel.contains("现金") -> "现"
            else -> channel.firstOrNull()?.toString() ?: "·"
        }
    }
}
