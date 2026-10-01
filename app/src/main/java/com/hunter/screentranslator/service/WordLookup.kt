package com.hunter.screentranslator.service

import android.util.Log
import com.hunter.screentranslator.App
import com.hunter.screentranslator.api.DictEntry
import com.hunter.screentranslator.api.TranslatorFactory
import com.hunter.screentranslator.util.HistoryStore
import kotlin.coroutines.cancellation.CancellationException

/**
 * 单词查词模式的入口（v1.29.0），无障碍服务与菜单划词共用。
 *
 * 只在三件事同时成立时查词：开关开着、选中的是单个单词、当前引擎能查词。
 * 查词失败（必应词典没收录、模型没按格式回答…）**不报错**，返回 false 让调用方
 * 照常翻译 —— 用户要的是"知道这个词什么意思"，给个译文也比给个报错强。
 */
object WordLookup {

    private const val TAG = "ScreenTranslator"

    /** 处理了就返回 true（面板已显示词条）；返回 false 时调用方走普通翻译 */
    suspend fun tryShow(text: String, mode: String): Boolean {
        if (!App.prefs.dictionaryMode) return false
        val word = text.trim()
        if (!DictEntry.isSingleWord(word)) return false
        val dict = TranslatorFactory.dictionary() ?: return false

        OverlayService.update(word, "正在翻译…")
        val result = dict.lookup(word, App.prefs.targetLang, App.prefs.sourceLang)
        val entry = result.getOrElse { e ->
            if (e is CancellationException) throw e
            Log.i(TAG, "$mode 查词没结果，改走翻译：${e.message}")
            return false
        }
        OverlayService.showDict(entry)
        runCatching {
            HistoryStore.add(
                source = word,
                translated = entry.toPlainText(),
                mode = "$mode[查词]",
                targetLang = App.prefs.targetLang,
                engine = App.prefs.engine
            )
        }
        Log.i(TAG, "$mode 查词完成：$word（${entry.senses.size} 个词性）")
        return true
    }
}
