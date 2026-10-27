#!/bin/bash
# 构建 ComicConv（漫画转换器）APK
#
# 不依赖 Gradle / AndroidX，全程只有四步：
#   javac -> d8 -> aapt -> zipalign -> apksigner
#
# 所有路径都可以用环境变量覆盖，默认值对应 Debian + 手装 Android SDK 的布局。
set -e

PROJ="$(cd "$(dirname "$0")" && pwd)"

SDK="${ANDROID_SDK:-/opt/andsdk}"
ANDROID_JAR="${ANDROID_JAR:-$SDK/android-36/android.jar}"
D8_JAR="${D8_JAR:-$(ls -1 "$SDK"/android-*/lib/d8.jar 2>/dev/null | sort -V | tail -1)}"
# aapt1 处理资源需要一个 framework-res.apk；直接用 android.jar 会解析错资源表
FW="${FRAMEWORK_RES:-/usr/share/android-framework-res/framework-res.apk}"
LIBS="${LIBS_DIR:-$PROJ/libs}"

AAPT="${AAPT:-aapt}"
ZIPALIGN="${ZIPALIGN:-zipalign}"
APKSIGNER="${APKSIGNER:-apksigner}"
JAVA="${JAVA:-java}"
KEYTOOL="${KEYTOOL:-keytool}"

BUILD="$PROJ/build"
DIST="$PROJ/dist"
MIN_API=21
APP_NAME=ComicConv

# ---- 依赖检查 ----
for v in ANDROID_JAR D8_JAR FW; do
    eval "p=\$$v"
    if [ ! -f "$p" ]; then
        echo "缺少 $v：$p" >&2
        echo "请用环境变量指定，例如： ANDROID_JAR=/path/to/android.jar ./build.sh" >&2
        exit 1
    fi
done
for j in commons-compress.jar junrar.jar xz.jar commons-logging.jar; do
    if [ ! -f "$LIBS/$j" ]; then
        echo "缺少依赖 $LIBS/$j（版本要求见 README）" >&2
        exit 1
    fi
done

CP="$ANDROID_JAR:$LIBS/commons-compress.jar:$LIBS/junrar.jar:$LIBS/xz.jar:$LIBS/commons-logging.jar"

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$DIST"

echo "[1/6] javac"
javac --release 11 -encoding UTF-8 -nowarn -cp "$CP" \
    -d "$BUILD/classes" $(find "$PROJ/src" -name '*.java')

echo "[2/6] d8（应用 + 三方库 -> classes.dex）"
"$JAVA" -Xmx2g -cp "$D8_JAR" com.android.tools.r8.D8 \
    --min-api $MIN_API \
    --lib "$ANDROID_JAR" \
    --output "$BUILD/dex" \
    $(find "$BUILD/classes" -name '*.class') \
    "$LIBS/commons-compress.jar" "$LIBS/junrar.jar" \
    "$LIBS/xz.jar" "$LIBS/commons-logging.jar" 2>&1 | grep -vE '^Warning|^\s+' || true

ls -l "$BUILD/dex/classes.dex"

echo "[3/6] aapt package（manifest + res）"
"$AAPT" package -f \
    -M "$PROJ/AndroidManifest.xml" \
    -S "$PROJ/res" \
    -I "$FW" \
    -F "$BUILD/base.apk" 2>&1 | grep -vE 'ResourceType|^$' || true

echo "[4/6] 加入 classes.dex"
( cd "$BUILD/dex" && "$AAPT" add -f "$BUILD/base.apk" classes.dex >/dev/null )

echo "[5/6] zipalign"
"$ZIPALIGN" -f -p 4 "$BUILD/base.apk" "$BUILD/aligned.apk"

echo "[6/6] 签名"
# 签名信息一律从环境变量来，仓库里不放任何密钥。
#   CC_KEYSTORE  密钥库路径
#   CC_STOREPASS 密钥库密码
#   CC_KEYPASS   密钥密码（PKCS12 下通常与 storepass 相同）
#   CC_ALIAS     别名
KS="${CC_KEYSTORE:-$PROJ/release.jks}"
KS_PASS="${CC_STOREPASS:-}"
KEY_PASS="${CC_KEYPASS:-$KS_PASS}"
KS_ALIAS="${CC_ALIAS:-comicconv}"

if [ ! -f "$KS" ]; then
    if [ -z "$KS_PASS" ]; then
        # 没给密码就现场生成一个随机密码，只存在本机这个文件里
        PASS_FILE="$KS.pass"
        if [ -f "$PASS_FILE" ]; then
            KS_PASS=$(cat "$PASS_FILE")
        else
            KS_PASS=$(head -c 18 /dev/urandom | base64 | tr -d '/+=\n')
            printf '%s' "$KS_PASS" > "$PASS_FILE"
            chmod 600 "$PASS_FILE"
        fi
        KEY_PASS="$KS_PASS"
        echo "  没有指定密钥，正在生成自签名密钥 $KS（密码存于 $PASS_FILE，勿提交）"
    fi
    "$KEYTOOL" -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KEY_PASS" \
        -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10950 \
        -dname "CN=ComicConv, O=Manga, C=CN" 2>/dev/null
fi

if [ -z "$KS_PASS" ]; then
    echo "密钥库已存在但没提供密码，请设置 CC_STOREPASS" >&2
    exit 1
fi

"$APKSIGNER" sign --ks "$KS" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
    --min-sdk-version $MIN_API \
    --out "$DIST/$APP_NAME.apk" "$BUILD/aligned.apk"

echo
echo "=== 验证 ==="
"$APKSIGNER" verify -v --min-sdk-version $MIN_API "$DIST/$APP_NAME.apk"
"$AAPT" dump badging "$DIST/$APP_NAME.apk" 2>/dev/null \
    | grep -E "^package|^sdkVersion|^targetSdkVersion|^application-label" || true
echo
ls -lh "$DIST/$APP_NAME.apk"
