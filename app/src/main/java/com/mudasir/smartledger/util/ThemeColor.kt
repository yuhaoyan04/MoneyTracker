package com.mudasir.smartledger.util

import android.content.Context
import android.util.TypedValue

/** 主题色解析助手，用于在图表中获取适配深色模式的文本色。 */
object ThemeColor {
    fun onSurface(context: Context): Int = resolveAttr(context, android.R.attr.textColorPrimary)

    private fun resolveAttr(context: Context, attr: Int): Int {
        val tv = TypedValue()
        val ok = context.theme.resolveAttribute(attr, tv, true)
        return if (ok) tv.data else android.graphics.Color.DKGRAY
    }
}
