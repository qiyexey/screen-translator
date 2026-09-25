package com.hunter.screentranslator

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Bundle
import com.hunter.screentranslator.api.HyMtRuntime
import com.hunter.screentranslator.service.OverlayService
import com.hunter.screentranslator.util.CrashLog
import com.hunter.screentranslator.util.HistoryStore
import com.hunter.screentranslator.util.Prefs
import com.hunter.screentranslator.util.TranslationCache

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // v1.18.0：**最先**装崩溃兜底。放在所有其它初始化之前 ——
        // 它要捕获的恰恰就是"初始化阶段崩了"这种情况，晚一行就少一行的覆盖。
        CrashLog.init(this)

        instance = this
        prefs = Prefs(this)

        // v1.21.0：悬浮球只服务于“跨应用翻译”，本应用自己的页面前台时自动隐藏。
        // 用 started Activity 计数而不是在 BaseActivity.onResume/onPause 里直接切：
        // A Activity 打开 B Activity 时会交错调用生命周期，直接切会在两页之间闪一下；
        // started 计数在内部页面跳转时始终 > 0，只有整个应用退到后台才恢复悬浮球。
        var startedActivities = 0
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                if (startedActivities == 1) OverlayService.setOwnAppForeground(true)
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) OverlayService.setOwnAppForeground(false)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        // v1.8.0：初始化翻译历史存储（需要 Context 定位 filesDir）
        HistoryStore.init(this)
        // v1.10.0：初始化翻译缓存并后台预载（避免首次翻译时在调用线程上读盘）
        TranslationCache.init(this)

        // v1.18.0：把 v1.17.0 及以前留在旧 prefs 里的明文 API Key 迁到加密存储。
        // 幂等；失败也只是这次没迁成，不会丢数据（见 Prefs.migrateSecrets）。
        runCatching { prefs.migrateSecrets() }
            .onFailure { android.util.Log.w("App", "secret migration skipped", it) }

        // v1.2.0 一次性迁移：全屏自动翻译改为默认关（干扰大、费 API）
        if (!prefs.migratedV12) {
            prefs.migratedV12 = true
            prefs.autoTranslate = false
        }

        // v1.15.10 一次性迁移：实时屏幕翻译的"画面变化阈值"默认值定错了。
        // 实测整句对白换掉时 Δ 只有 2~5，而旧默认是 8 —— 等于永远判"没变"。
        // 老用户已经存下 8，不重置他们会一直卡住，且界面上看不出原因。
        if (!prefs.migratedV11510) {
            prefs.migratedV11510 = true
            prefs.liveDiffThreshold = Prefs.DEFAULT_LIVE_DIFF
        }

        // v1.2.1 一次性迁移：划词翻译也默认关。
        // 原因：输入框/搜索框获取焦点时系统自动全选文字，同样发选区事件，导致
        // 用户没选任何文字也在各页面触发翻译。需要划词的用户可在设置里重新打开。
        if (!prefs.migratedV121) {
            prefs.migratedV121 = true
            prefs.selectionTranslate = false
        }
    }

    /**
     * v1.17.0：本地大模型（Hy-MT2）加载后常驻约 1.5GB。系统内存吃紧时**主动卸掉它**。
     *
     * 为什么必须这么做：本 App 的悬浮窗、读屏服务、实时翻译都跑在这个进程里，
     * 若因为一个"可以重新加载"的模型而被 LMK 杀掉，用户失去的是正在用的翻译能力 ——
     * 卸载模型只损失几秒的重新加载时间，取舍很明显。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            HyMtRuntime.unloadAsync()
        }
    }

    companion object {
        @Volatile
        lateinit var instance: App
            private set
        @Volatile
        lateinit var prefs: Prefs
            private set

        /** Application Context 快捷访问（TTS 等单例需要它做引擎探测） */
        val appContext: android.content.Context
            get() = instance.applicationContext
    }
}
