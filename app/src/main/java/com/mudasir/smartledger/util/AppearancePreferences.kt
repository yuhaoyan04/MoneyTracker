package com.mudasir.smartledger.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object AppearancePreferences {
    private const val PREFS = "appearance_prefs"
    fun selected(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt("theme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        .takeIf { it in setOf(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES) }
        ?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM

    fun apply(context: Context) = AppCompatDelegate.setDefaultNightMode(selected(context))

    fun save(context: Context, mode: Int) {
        require(mode in setOf(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("theme", mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
