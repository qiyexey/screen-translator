package com.hunter.screentranslator.api

import com.hunter.screentranslator.util.Glossary

/**
 * 给不接受指令的引擎（传统机翻 / 必应 / 本地模型）套上术语表（v1.29.0）。
 *
 * 做法是送出前把原文里的术语直接换成指定译文，见 [Glossary.preReplace]。
 * AI 引擎不走这里 —— 它们把术语写进提示词，效果更好（能顺带调整语序和格位）。
 *
 * 图片翻译原样转发：图片里的字送出前还没认出来，没法替换。
 */
class GlossaryTranslator(
    private val delegate: Translator,
    private val entries: List<Glossary.Entry>
) : Translator {

    override suspend fun translate(
        text: String,
        targetLang: String,
        sourceLang: String
    ): Result<String> = delegate.translate(Glossary.preReplace(text, entries), targetLang, sourceLang)

    override suspend fun translateImage(
        imageBytes: ByteArray,
        mimeType: String,
        targetLang: String,
        hint: String?,
        sourceLang: String
    ): Result<String> = delegate.translateImage(imageBytes, mimeType, targetLang, hint, sourceLang)
}
