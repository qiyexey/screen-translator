#!/usr/bin/env bash
# ============================================================================
# 生成 release 签名密钥（v1.18.0 新增）
#
# 为什么需要这个脚本：v1.17.0 发布的包用的是 Android **调试证书**
# （CN=Android Debug, serial=1）。原因是 build.gradle.kts 在缺少口令时会
# 静默退回 debug 签名 —— 而调试密钥是 AOSP 公开的，全网开发者同一把。
# 后果：① 上不了任何应用商店；② 任何人都能用同一把密钥签一个同包名的
# 恶意包，在已安装用户机器上被当作"正常升级"覆盖安装。
#
# 正式发布必须换成下面这把自签密钥，并且**永久备份** ——
# APK 签名密钥一旦丢失，已安装用户就无法再收到你的更新（只能让用户卸载重装）。
#
# 用法：
#   bash scripts/gen-keystore.sh                 # 交互输入口令
#   STORE_PASS=xxx KEY_PASS=yyy bash scripts/gen-keystore.sh   # 非交互（CI）
#
# 产物：
#   release.keystore        —— 密钥文件，**不要提交、不要外传**
#   keystore.properties     —— 口令，已被 .gitignore 排除
# ============================================================================
set -euo pipefail

cd "$(dirname "$0")/.."

KEYSTORE="release.keystore"
PROPS="keystore.properties"
ALIAS="${KEY_ALIAS:-screentranslator}"
# 10000 天 ≈ 27 年。Google Play 要求密钥有效期至少到 2033 年；
# 这个长度能覆盖到 2050 年以后，避免密钥过期导致无法更新。
VALIDITY_DAYS=10000
DNAME="${KEY_DNAME:-CN=Screen Translator, OU=Release, O=ScreenTranslator, L=Unknown, ST=Unknown, C=CN}"

# ---- 定位 keytool：优先用 JAVA_HOME（Termux 里 JDK 不在 PATH 上）----
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then
  KEYTOOL="$JAVA_HOME/bin/keytool"
elif command -v keytool >/dev/null 2>&1; then
  KEYTOOL="$(command -v keytool)"
else
  echo "❌ 找不到 keytool。"
  echo "   请设置 JAVA_HOME 指向 JDK 17，例如："
  echo "     export JAVA_HOME=\$HOME/txroot/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk"
  exit 1
fi
echo "使用 keytool: $KEYTOOL"

# ---- 防误覆盖：已存在就停下（覆盖等于让老用户无法升级）----
if [ -f "$KEYSTORE" ]; then
  echo "❌ $KEYSTORE 已存在，已中止。"
  echo "   覆盖它会改变签名，导致已安装用户无法覆盖升级。"
  echo "   确实要重建请先手动备份并删除该文件。"
  exit 1
fi

# ---- 取口令 ----
STORE_PASS="${STORE_PASS:-}"
KEY_PASS="${KEY_PASS:-}"
if [ -z "$STORE_PASS" ]; then
  read -r -s -p "设置 keystore 口令（至少 6 位）: " STORE_PASS; echo
  read -r -s -p "再输一次确认: " STORE_PASS2; echo
  [ "$STORE_PASS" = "$STORE_PASS2" ] || { echo "❌ 两次输入不一致"; exit 1; }
fi
if [ -z "$KEY_PASS" ]; then
  # 绝大多数场景下让两者相同，省一次输入；不同也可以，用 KEY_PASS 单独指定
  KEY_PASS="$STORE_PASS"
fi
if [ "${#STORE_PASS}" -lt 6 ]; then
  echo "❌ 口令至少 6 位"; exit 1
fi

# ---- 生成 ----
"$KEYTOOL" -genkeypair \
  -keystore "$KEYSTORE" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -sigalg SHA256withRSA \
  -validity "$VALIDITY_DAYS" \
  -storetype PKCS12 \
  -dname "$DNAME" \
  -storepass "$STORE_PASS" \
  -keypass "$KEY_PASS"

chmod 600 "$KEYSTORE" 2>/dev/null || true

# ---- 写 keystore.properties ----
cat > "$PROPS" <<EOF
# 由 scripts/gen-keystore.sh 生成 —— 不要提交到仓库（已在 .gitignore 中排除）。
# 丢失此文件等同于丢失签名能力，请与 $KEYSTORE 一起离线备份。
storeFile=$KEYSTORE
storePassword=$STORE_PASS
keyAlias=$ALIAS
keyPassword=$KEY_PASS
EOF
chmod 600 "$PROPS" 2>/dev/null || true

# ---- 自检 ----
echo
echo "✅ 已生成 $KEYSTORE 与 $PROPS"
echo
echo "证书指纹（请抄下来存好，上架时各商店都要填）："
"$KEYTOOL" -list -v -keystore "$KEYSTORE" -alias "$ALIAS" -storepass "$STORE_PASS" \
  | grep -E "SHA1:|SHA256:|Valid from|Alias name" || true
echo
echo "⚠️  接下来必须做的两件事："
echo "   1. 把 $KEYSTORE 和 $PROPS 离线备份（换电脑/清仓库后仍能找回）"
echo "   2. 确认 .gitignore 已排除它们：git status 不应出现这两个文件"
echo
echo "然后即可构建：bash \$HOME/run_gradle.sh assembleRelease"
