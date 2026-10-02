package com.mudasir.smartledger.util

import android.content.Context
import android.view.ViewGroup

/**
 * 广告管理器接口（Stub 实现）
 *
 * 当前为接口预留状态 —— 广告位 ID、横幅/插屏方法均已定义，
 * 但尚未接入任何广告 SDK。当有广告商合作时：
 *
 *   1. 在 build.gradle.kts 添加广告 SDK 依赖
 *      （如 AdMob: com.google.android.gms:play-services-ads）
 *   2. 在 initialize() 中初始化 SDK
 *   3. 在 loadBannerInto() 中创建 AdView 并加载到容器
 *   4. 在 loadInterstitial()/showInterstitial() 中实现插屏广告
 *   5. 在布局中添加 FrameLayout 容器，调用 loadBannerInto()
 *
 * 广告合作联系方式：yizhilaotian@gmail.com
 */
object AdManager {

    const val CONTACT_EMAIL = "yizhilaotian@gmail.com"

    private const val PREFS = "ad_prefs"
    private const val KEY_BANNER_UNIT = "banner_ad_unit_id"
    private const val KEY_ENABLED = "ad_enabled"

    /**
     * 广告是否已启用（默认关闭，有广告商接入后开启）。
     */
    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * 获取配置的横幅广告位 ID。
     * 未配置时返回空字符串。
     */
    fun getBannerUnitId(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BANNER_UNIT, "") ?: ""
    }

    fun setBannerUnitId(context: Context, unitId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_BANNER_UNIT, unitId).apply()
    }

    // ===== SDK 初始化（Stub — 接入时实现） =====

    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        // TODO: 接入广告 SDK 后在此初始化
        // com.google.android.gms.ads.MobileAds.initialize(context) { }
        initialized = true
    }

    // ===== 横幅广告（Stub — 接入时实现） =====

    /**
     * 将横幅广告加载到指定容器中。
     * 当前为 Stub：不显示任何内容，容器隐藏。
     *
     * 接入广告 SDK 后：
     *   val adView = AdView(context)
     *   adView.setAdSize(AdSize.BANNER)
     *   adView.adUnitId = getBannerUnitId(context)
     *   adView.loadAd(AdRequest.Builder().build())
     *   container.addView(adView)
     */
    fun loadBannerInto(container: ViewGroup, context: Context) {
        container.removeAllViews()
        container.visibility = android.view.View.GONE
    }

    // ===== 插屏广告（Stub — 接入时实现） =====

    fun loadInterstitial(context: Context) {
        // TODO: 接入广告 SDK 后实现插屏广告预加载
    }

    fun showInterstitial(activity: android.app.Activity) {
        // TODO: 接入广告 SDK 后实现插屏广告展示
    }
}
