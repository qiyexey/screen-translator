package com.hunter.screentranslator.api

import com.hunter.screentranslator.App
import com.hunter.screentranslator.util.TranslationCache

/**
 * v1.10.0 缓存装饰器：包在任意 [Translator] 外面，命中缓存就不发网络请求。
 *
 * 为什么放在这一层，而不是各调用点各写一遍：
 * 多个翻译调用点（无障碍读屏 / 图片翻译 / 输入翻译 / 语音翻译 /
 * 菜单划词 / 主界面的"测试翻译"），它们全都通过 [TranslatorFactory.current] 取引擎。
 * 在这里包一层等于**一次性给所有入口加上缓存**：调用点零改动，也不会漏掉将来新增的入口。
 *
 * 注意：必须显式转发 [translateImage]。接口里它带默认实现（返回"当前引擎不支持图片"），
 * 若不转发，图片翻译会被这层包装**静默废掉**——而且报错文案会误导用户去换引擎。
 *
 * v1.20.0：转发链多了一维「源语言」。接口上带默认值，所以 [CachingTranslator]
 * 不重新声明默认值也能编译（`override` 不得重复父类默认值语义，写了反而要报错
 * "an overriding function is not allowed to specify default values"），
 * 调用方在编译期看到的默认值来自接口声明。
 */
class CachingTranslator(
    private val delegate: Translator,
    /** 缓存作用域，形如 "deepseek|api.deepseek.com|deepseek-chat" */
    private val scope: String
) : Translator {

    override suspend fun translate(
        text: String,
        targetLang: String,
        sourceLang: String
    ): Result<String> {
        // 关掉缓存时完全走原路径，不做任何多余判断（便于排查"译文不对是不是缓存串了"）
        if (!App.prefs.cacheEnabled) return delegate.translate(text, targetLang, sourceLang)

        val src = text.trim()
        // 空文本无意义；超长文本（整屏拼接）撑大内存且几乎不会重复命中
        if (src.isEmpty() || src.length > TranslationCache.MAX_SOURCE_CHARS) {
            return delegate.translate(text, targetLang, sourceLang)
        }

        // v1.20.0：缓存键必须带上源语言。
        // `TranslationCache.get/put` 只有「作用域 + 目标语言 + 原文」三个维度，
        // 而源语言同样是**改变译文的语义输入**：同一句 "の" 在 auto 下可能被识别成
        // 中文译成「的」，在 ja 下应当原样返回。若不并入，用户从 auto 切到 ja 后
        // 仍会命中 auto 时存下的旧译文。
        //
        // 这里用 [scopeKey] 把源语言塞进"作用域"那一维，而不是改 TranslationCache
        // 的键结构 —— 后者要动表结构/迁移，而作用域是现成的复合字符串，
        // 本来就是为"引擎 + 端点 + 模型"这类会影响译文的因素准备的。
        val scoped = scopeKey(sourceLang)

        TranslationCache.get(scoped, targetLang, src)?.let { return Result.success(it) }

        val result = delegate.translate(text, targetLang, sourceLang)
        // 只缓存成功的译文。失败（断网/额度耗尽/鉴权错）绝不能进缓存，
        // 否则用户重试时会一直拿到上次的失败结果。
        val ok = result.getOrNull()
        if (ok != null && ok.isNotBlank()) {
            TranslationCache.put(scoped, targetLang, src, ok)
        }
        return result
    }

    /** 图片翻译原样转发（按图片字节做 key 代价太大，不入缓存） */
    override suspend fun translateImage(
        imageBytes: ByteArray,
        mimeType: String,
        targetLang: String,
        hint: String?,
        sourceLang: String
    ): Result<String> =
        delegate.translateImage(imageBytes, mimeType, targetLang, hint, sourceLang)

    /**
     * 把源语言并入缓存作用域。
     *
     * [SOURCE_AUTO] 时**原样返回 [scope]**：`auto` 是绝大多数用户的常态，
     * 让它沿用 v1.19.0 已经写好的缓存条目，升级后不会凭空多出一份冷缓存。
     * 只有用户显式指定了源语言才另开一个作用域。
     */
    private fun scopeKey(sourceLang: String): String =
        if (sourceLang == SOURCE_AUTO) scope else "$scope|src=$sourceLang"
}
