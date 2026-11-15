package com.mudasir.smartledger.util

import android.content.Context
import android.graphics.drawable.Drawable

object ThemeDrawables {
    /** TypedArray implements AutoCloseable only on newer Android versions. */
    fun selectableBackground(context: Context): Drawable? {
        val attributes = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        return try { attributes.getDrawable(0) } finally { attributes.recycle() }
    }
}
