package com.hunter.screentranslator.api

import com.hunter.screentranslator.App
import com.hunter.screentranslator.util.TranslationCache

/**
 * v1.10.0 缓存装饰器：包在任意 [Translator] 外面，命中缓存就不发网络请求。
 *
 * 为什么放在这一层，而不是各调用点各写一遍：
 * 全文有 7 个翻译调用点（无障碍读屏 / 听视频 / 图片翻译 / 输入翻译 / 语音翻译 /
 * 菜单划词 / 主界面的"测试翻译"），它们全都通过 [TranslatorFactory.current] 取引擎。
 * 在这里包一层等于**一次性给所有入口加上缓存**：调用点零改动，也不会漏掉将来新增的入口。
 *
 * 注意：必须显式转发 [translateImage]。接口里它带默认实现（返回"当前引擎不支持图片"），
 * 若不转发，图片翻译会被这层包装**静默废掉**——而且报错文案会误导用户去换引擎。
 */
class CachingTranslator(
    private val delegate: Translator,
    /** 缓存作用域，形如 "deepseek|api.deepseek.com|deepseek-chat" */
    private val scope: String
) : Translator {

    override suspend fun translate(text: String, targetLang: String): Result<String> {
        // 关掉缓存时完全走原路径，不做任何多余判断（便于排查"译文不对是不是缓存串了"）
        if (!App.prefs.cacheEnabled) return delegate.translate(text, targetLang)

        val src = text.trim()
        // 空文本无意义；超长文本（整屏拼接）撑大内存且几乎不会重复命中
        if (src.isEmpty() || src.length > TranslationCache.MAX_SOURCE_CHARS) {
            return delegate.translate(text, targetLang)
        }

        TranslationCache.get(scope, targetLang, src)?.let { return Result.success(it) }

        val result = delegate.translate(text, targetLang)
        // 只缓存成功的译文。失败（断网/额度耗尽/鉴权错）绝不能进缓存，
        // 否则用户重试时会一直拿到上次的失败结果。
        val ok = result.getOrNull()
        if (ok != null && ok.isNotBlank()) {
            TranslationCache.put(scope, targetLang, src, ok)
        }
        return result
    }

    /** 图片翻译原样转发（按图片字节做 key 代价太大，不入缓存） */
    override suspend fun translateImage(
        imageBytes: ByteArray,
        mimeType: String,
        targetLang: String,
        hint: String?
    ): Result<String> = delegate.translateImage(imageBytes, mimeType, targetLang, hint)
}
