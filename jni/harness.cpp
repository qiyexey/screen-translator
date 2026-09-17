// v1.17.0 验收程序：用与 APK 完全相同的 wrapper + 静态库，验证
//  1) applyChatTemplate 产出的 prompt 是否被重复加 BOS
//  2) 官方提示词 + 该模板下的真实译文质量
#include "llama_context_wrapper.h"
#include "llama.h"
#include <cstdio>
#include <string>
#include <vector>
#include <chrono>

using llamaandroid::LlamaConfig;
using llamaandroid::LlamaContextWrapper;

// 与 Kotlin 侧自建 escaper 保持一致：只产出原生手写解析器认识的转义
static std::string jsonEscape(const std::string& s) {
    std::string o;
    for (unsigned char c : s) {
        switch (c) {
            case '"':  o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += "\\n";  break;
            case '\t': o += "\\t";  break;
            default:   o += (char)c;
        }
    }
    return o;
}

static const std::string BOS_T = "\x3c\xef\xbd\x9c" "hy_begin" "\xe2\x96\x81" "of" "\xe2\x96\x81" "sentence" "\xef\xbd\x9c\x3e";
static const std::string U_T = "\x3c\xef\xbd\x9c" "hy_User" "\xef\xbd\x9c\x3e";
static const std::string A_T = "\x3c\xef\xbd\x9c" "hy_Assistant" "\xef\xbd\x9c\x3e";

static std::string manualFor(const std::string& line) {
    return BOS_T + U_T + "将以下文本翻译为 中文，注意只需要输出翻译后的结果，不要额外解释：\n\n" + line + A_T;
}

static const char* LINES[] = {
    "Hello, how are you?",
    "Battery low. Please connect the charger.",
    "Are you sure you want to delete this file?",
    "昨日の会議は中止になりました。",
    "설정에서 알림을 끌 수 있습니다.",
    "Connection timed out. Please try again later.",
    "Loading... 45%",
    "Press and hold to record a voice message",
    "Are you sure you want to delete this file?",
    "Sign in to continue",
    "Your order has been shipped.",
    "Terms of Service and Privacy Policy",
    "本商品は返品できません。",
};

int main(int argc, char** argv) {
    std::string modelPath = argv[1];

    // ---------- 1) BOS 检查（直接用 llama.h，看模板产物的分词） ----------
    {
        llama_backend_init();
        llama_model_params mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        llama_model* m = llama_model_load_from_file(modelPath.c_str(), mp);
        if (!m) { printf("BOS 检查：模型加载失败\n"); return 1; }
        const llama_vocab* v = llama_model_get_vocab(m);
        const char* tmpl = llama_model_chat_template(m, nullptr);
        printf("=== 模板前 80 字符 ===\n%.80s\n", tmpl ? tmpl : "(none)");

        std::string user = "将以下文本翻译为 中文，注意只需要输出翻译后的结果，不要额外解释：\n\nHello";
        std::string esc = jsonEscape(user);
        std::string json = "[{\"role\":\"user\",\"content\":\"" + esc + "\"}]";
        llama_chat_message msg{"user", user.c_str()};
        std::vector<char> buf(8192);
        int n = llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), (int)buf.size());
        std::string templated(buf.data(), n > 0 ? n : 0);
        // 用可见转义打印前 160 字节，并逐 token 反解前 12 个，确认 <|hy_User|> 这类特殊 token 是否在
        printf("=== 套模板后（前 160 字节，逐字节可见）===\n");
        for (size_t i = 0; i < templated.size() && i < 160; i++) {
            unsigned char c = templated[i];
            if (c >= 0x20 && c < 0x7f) putchar(c); else printf("<%02x>", c);
        }
        printf("\n");

        // 手工构造官方格式：BOS + <|hy_User|> + 正文 + <|hy_Assistant|>
        std::string manual = BOS_T + U_T + user + A_T;
        printf("=== 手工官方格式（前 40 字节）===\n");
        for (size_t i = 0; i < manual.size() && i < 40; i++) {
            unsigned char c = manual[i];
            if (c >= 0x20 && c < 0x7f) putchar(c); else printf("<%02x>", c);
        }
        printf("\n");
        {
            std::vector<llama_token> t2(manual.size() + 32);
            int k2 = llama_tokenize(v, manual.c_str(), manual.size(), t2.data(), t2.size(), true, true);
            if (k2 < 0) { t2.resize(-k2); k2 = llama_tokenize(v, manual.c_str(), manual.size(), t2.data(), t2.size(), true, true); }
            printf("手工格式 → %d tokens, 前 6 个 id:", k2);
            for (int i = 0; i < k2 && i < 6; i++) printf(" %d", t2[i]);
            printf("  (末 3 个 id:");
            for (int i = std::max(0, k2 - 3); i < k2; i++) printf(" %d", t2[i]);
            printf(")\n");
        }

        for (bool addSpecial : {false, true}) {
            std::vector<llama_token> toks(templated.size() + 32);
            int k = llama_tokenize(v, templated.c_str(), templated.size(), toks.data(), toks.size(), addSpecial, true);
            if (k < 0) { toks.resize(-k); k = llama_tokenize(v, templated.c_str(), templated.size(), toks.data(), toks.size(), addSpecial, true); }
            toks.resize(k);
            int bos = llama_vocab_bos(v);
            int bosCount = 0;
            for (auto t : toks) if (t == bos) bosCount++;
            printf("add_special=%s → %d tokens, 其中 BOS(%d) 出现 %d 次, 前 6 个 id:", addSpecial ? "true " : "false", k, bos, bosCount);
            for (int i = 0; i < k && i < 6; i++) printf(" %d", toks[i]);
            printf("\n");
        }
        llama_model_free(m);
        llama_backend_free();
    }

    // ---------- 2) 走 wrapper（= APK 内的路径）跑真实译文 ----------
    LlamaConfig cfg;
    cfg.contextSize = 2048; cfg.batchSize = 256;
    cfg.threads = 6; cfg.threadsBatch = 6;
    cfg.temperature = 0.7f; cfg.topP = 0.6f; cfg.topK = 20; cfg.repeatPenalty = 1.05f;
    cfg.maxTokens = 256; cfg.useMmap = true; cfg.useMlock = false; cfg.gpuLayers = 0; cfg.seed = -1;

    LlamaContextWrapper w;
    if (!w.loadModel(modelPath, cfg)) { printf("wrapper loadModel 失败: %s\n", w.getLastError().c_str()); return 1; }
    printf("\n=== wrapper 路径实测（12 项中的 8 项）===\n");
    double total = 0;
    int cnt = 0;
    for (const char* line : LINES) {
        std::string user = std::string("将以下文本翻译为 中文，注意只需要输出翻译后的结果，不要额外解释：\n\n") + line;
        std::string json = "[{\"role\":\"user\",\"content\":\"" + jsonEscape(user) + "\"}]";
        std::string templated = w.applyChatTemplate(json, true);
        if (templated.empty()) { printf("applyChatTemplate 返回空: %s\n", w.getLastError().c_str()); continue; }
        auto trim = [](std::string o) {
            while (!o.empty() && (o.back() == '\n' || o.back() == ' ')) o.pop_back();
            return o;
        };
        // A：老解析器（wrapper 的 applyChatTemplate）
        auto t0 = std::chrono::steady_clock::now();
        std::string outA = trim(w.generate(templated, &cfg));
        double msA = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        // B：手工官方格式
        auto t1 = std::chrono::steady_clock::now();
        std::string outB = trim(w.generate(manualFor(line), &cfg));
        double msB = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t1).count();
        total += msA; cnt++;
        bool same = (outA == outB);
        printf("%-44s\n   A(老解析器 %4.0fms): %s\n   B(官方格式 %4.0fms): %s   %s\n",
               line, msA, outA.c_str(), msB, outB.c_str(), same ? "[一致]" : "[不同]");
    }
    printf("平均值: %.0f ms/句 (%d 句)\n", cnt ? total / cnt : 0, cnt);
    return 0;
}
