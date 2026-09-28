package com.mudasir.smartledger.util

import android.app.Activity
import android.content.Intent
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.mudasir.smartledger.R
import com.mudasir.smartledger.activity.CaptureInboxActivity
import com.mudasir.smartledger.activity.HomeActivity
import com.mudasir.smartledger.activity.SettingsActivity
import com.mudasir.smartledger.activity.StatsActivity

/** 统一底部导航切换逻辑（基于 Activity）。 */
object BottomNavHelper {

    fun setup(activity: Activity, bottomNav: BottomNavigationView, currentId: Int) {
        if (bottomNav.selectedItemId != currentId) {
            bottomNav.selectedItemId = currentId
        }
        bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId == currentId) return@setOnItemSelectedListener true
            val target = when (item.itemId) {
                R.id.nav_tab_home -> HomeActivity::class.java
                R.id.nav_tab_inbox -> CaptureInboxActivity::class.java
                R.id.nav_tab_stats -> StatsActivity::class.java
                R.id.nav_tab_settings -> SettingsActivity::class.java
                else -> return@setOnItemSelectedListener false
            }
            val intent = Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            activity.startActivity(intent)
            activity.overridePendingTransition(0, 0)
            true
        }
    }
}
