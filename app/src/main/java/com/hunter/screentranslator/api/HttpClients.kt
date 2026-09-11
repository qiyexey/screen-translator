package com.hunter.screentranslator.api

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 共享 OkHttpClient 实例池。
 *
 * 背景（修复 OkHttpClient 泄漏）：
 * 修复前每个翻译引擎各自 `private val client = OkHttpClient.Builder()...build()`，
 * 而 [TranslatorFactory.current] 每次翻译都新建一个引擎实例 —— 于是每次翻译都会
 * 新建一个 OkHttpClient，同时泄漏一个 ConnectionPool（默认 5 空闲连接 / 5 分钟）
 * 和一个 Dispatcher 线程池（默认 64 线程）。WhisperClient 更严重：它在构造函数里
 * 建 client，而 VideoListenService 每个音频分段都新建一个。
 *
 * OkHttp 官方明确要求共享实例：每个 client 应共用连接池与线程池。
 * 这里按"用途"分三个池（超时不同），而不是按引擎分 —— 引擎之间可以安全共用。
 *
 * 注意：用 [OkHttpClient.newBuilder] 派生可以共享同一个连接池与线程池，
 * 因此下面的三个实例实际上共享底层资源，只是超时策略不同。
 */
object HttpClients {

    /** 通用翻译（Google / Microsoft / DeepL / 百度 / 彩云）：请求小、响应快 */
    val standard: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // callTimeout 是整次调用的总时长上限；readTimeout 只是两次读之间的间隔，
            // 不加 callTimeout 时一个慢速滴流的响应可以无限期挂着。
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** LLM（DeepSeek / OpenAI / Claude / 通义 / GLM / 豆包）：生成慢，读超时放宽 */
    val llm: OkHttpClient by lazy {
        standard.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    /** ASR（Whisper）：要上传较大的音频 body，且转写耗时 */
    val asr: OkHttpClient by lazy {
        standard.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
    }
}
