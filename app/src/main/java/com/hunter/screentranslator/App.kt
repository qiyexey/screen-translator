package com.hunter.screentranslator

import android.app.Application
import com.hunter.screentranslator.util.HistoryStore
import com.hunter.screentranslator.util.Prefs
import com.hunter.screentranslator.util.TranslationCache

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        // v1.8.0：初始化翻译历史存储（需要 Context 定位 filesDir）
        HistoryStore.init(this)
        // v1.10.0：初始化翻译缓存并后台预载（避免首次翻译时在调用线程上读盘）
        TranslationCache.init(this)

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
