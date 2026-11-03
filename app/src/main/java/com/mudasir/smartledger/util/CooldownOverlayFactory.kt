package com.mudasir.smartledger.util

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import com.mudasir.smartledger.R

/** Inflate independently of the service's theme and of Material Components widgets. */
object CooldownOverlayFactory {
    fun create(context: Context): View {
        val themed = ContextThemeWrapper(context, android.R.style.Theme_Material_NoActionBar)
        return LayoutInflater.from(themed).inflate(R.layout.view_cooldown_overlay, null)
    }
}
