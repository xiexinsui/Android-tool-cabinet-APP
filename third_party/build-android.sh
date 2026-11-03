#!/usr/bin/env bash
# 交叉编译内置远程桌面的原生依赖：OpenSSL + FreeRDP → 每个 ABI 一套静态库。
#
# 产物落在 third_party/prebuilt/<abi>/（lib/*.a + include/），是编译产物、不进仓库，
# 与 1.x 时代 librdpcore.so 的处理方式一致。rdpcore/CMakeLists.txt 直接把这套 .a 链进
# librdpcore.so，AGP 那边只负责编我们的 JNI 层，不被 OpenSSL/FreeRDP 的构建逻辑污染。
#
# ─── 为什么 1.x 的 FreeRDP 路线卡死、这次通了 ────────────────────────────────
# 卡死点是 OpenSSL：它的 Configure 要 perl + GNU make，而且 Git for Windows 里那个精简
# perl 缺 Locale::Maketext、Pod::Usage、Pod::Simple、ExtUtils::MakeMaker 一片核心模块。
# 这次补齐了三样东西，路线才成立：
#   1. GNU make 与 llvm-* —— NDK 自带（prebuilt/windows-x86_64/bin/make.exe），不用另装；
#   2. NDK 的 clang 包装脚本（aarch64-linux-android24-clang）放进 PATH，OpenSSL 的
#      android 模板就是靠 which("clang") 反查 NDK 与宿主目录的；
#   3. 缺的 perl 模块补进 third_party/perl-lib（纯 Perl，来自 Perl/perl5 官方仓库与 CPAN），
#      用 PERL5LIB 指过去，不动系统 perl。
# 1.x 选 MbedTLS 的想法在这里行不通：FreeRDP 顶层 CMake 把 OpenSSL 写成 REQUIRED，
# WITH_MBEDTLS 只是"额外再找一个"，不配 OpenSSL 连 configure 都过不去。
#
# ─── 用法（Git Bash）────────────────────────────────────────────────────────
#   ./build-android.sh              # 两个 ABI 都编
#   ./build-android.sh arm64-v8a    # 只编一个
# 前置：third_party/openssl-<版本>.tar.gz 与 freerdp/ 源码就位（版本见下面常量）。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NDK_WIN="${NDK:-D:/Android/Sdk/ndk/29.0.13113456}"
CMAKE="${CMAKE:-D:/Android/Sdk/cmake/3.31.5/bin/cmake.exe}"
NINJA="${NINJA:-D:/Android/Sdk/cmake/3.31.5/bin/ninja.exe}"
# OpenSSL 源码版本与官方 SHA256（换版本时两个都要改，校验不过就直接停）
OPENSSL_VERSION="${OPENSSL_VERSION:-3.5.9}"
OPENSSL_SHA256="${OPENSSL_SHA256:-603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a}"
JOBS="${JOBS:-8}"
# API 24 = 与 app 的 minSdk 一致。声音走 FreeRDP 自带的 OpenSL ES 后端（libOpenSLES 从
# API 9 就有），不需要 AAudio，因此不像 1.x 那样被迫提到 26。
ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-24}"
API_LEVEL=24

# MSYS 的路径转换会把 D:/... 变成相对路径，交叉编译这些命令行一律关掉它
export MSYS_NO_PATHCONV=1
# OpenSSL 的 android 模板用 which("clang") 的结果反查 NDK 根，两边必须是同一种写法，
# 所以这里给 MSYS 风格（/d/...）而 CMake 那边给 Windows 风格（D:/...）
export ANDROID_NDK_ROOT="$(cygpath -u "$NDK_WIN")"
LLVM_BIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/windows-x86_64/bin"
NDK_BIN="$ANDROID_NDK_ROOT/prebuilt/windows-x86_64/bin"
export PATH="$LLVM_BIN:$NDK_BIN:$PATH"
export PERL5LIB="$HERE/perl-lib"

[ -d "$HERE/perl-lib/Pod" ] || { echo "缺 $HERE/perl-lib/Pod —— OpenSSL Configure 的 perl 依赖没补齐"; exit 1; }
[ -x "$LLVM_BIN/clang.exe" ] || { echo "找不到 NDK clang：$LLVM_BIN"; exit 1; }

# 源码不进仓库（FreeRDP 一个 clone 就 86MB，OpenSSL 源码包 53MB）。缺了就按固定版本拉，
# 版本号与校验值都写死在上面，换版本必须同时改两处。
FREERDP_VERSION="${FREERDP_VERSION:-3.28.0}"
ensure_sources() {
    if [ ! -f "$HERE/freerdp/CMakeLists.txt" ]; then
        echo "拉取 FreeRDP $FREERDP_VERSION …"
        git clone --depth 1 --branch "$FREERDP_VERSION" \
            https://github.com/FreeRDP/FreeRDP.git "$HERE/freerdp" || exit 1
    fi
    local tarball="$HERE/openssl-$OPENSSL_VERSION.tar.gz"
    if [ ! -f "$tarball" ]; then
        echo "拉取 OpenSSL $OPENSSL_VERSION …"
        curl -L --fail --retry 3 -o "$tarball" \
            "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz" || exit 1
    fi
    echo "$(printf '%s  %s' "$OPENSSL_SHA256" "$tarball")" | (cd "$HERE" && sha256sum -c -) >/dev/null || {
        echo "OpenSSL 源码校验失败：$tarball"; exit 1; }
}
ensure_sources

# 失败时直接把日志尾巴打到终端：set -e 加"输出全进日志文件"会让脚本静默退出，
# 而 configure 的报错只有那几行才有价值。
step() {
    local name="$1" log="$2"
    shift 2
    if ! "$@" >"$log" 2>&1; then
        echo "!! $name 失败。$log 最后 40 行："
        tail -40 "$log"
        exit 1
    fi
}

step_in() {
    local dir="$1" name="$2" log="$3"
    shift 3
    if ! ( cd "$dir" && "$@" ) >"$log" 2>&1; then
        echo "!! $name 失败。$log 最后 40 行："
        tail -40 "$log"
        exit 1
    fi
}

ABIS=("$@")
if [ ${#ABIS[@]} -eq 0 ]; then
    ABIS=(arm64-v8a armeabi-v7a)
fi

# OpenSSL 的 android 目标名按 ABI 走
ossl_target() {
    case "$1" in
        arm64-v8a)   echo "android-arm64" ;;
        armeabi-v7a) echo "android-arm"   ;;
        *) echo "未知 ABI: $1" >&2; return 1 ;;
    esac
}

FREERDP_OPTS=(
    -DFREERDP_UNIFIED_BUILD=ON
    -DBUILD_SHARED_LIBS=OFF
    # FreeRDP 在编译器支持时默认开 ThinLTO。LTO 把 __thread 的降低推迟到链接期，
    # 32 位 ARM 上 -femulated-tls 因此不生效、又回到 __tls_get_addr（bionic 到 API 30
    # 才导出）。关掉 LTO，每个对象在编译期就把 TLS 模型定死，链接才稳定。
    -DCMAKE_INTERPROCEDURAL_OPTIMIZATION=OFF
    -DBUILD_TESTING=OFF
    -DWITH_SERVER=OFF
    -DWITH_SAMPLE=OFF
    -DWITH_CLIENT=OFF
    -DWITH_CLIENT_COMMON=ON
    -DWITH_GUI=OFF
    -DWITH_MANPAGES=OFF
    # 外部编解码与智能卡：安卓上要么编不动、要么用不上，全关
    -DWITH_FFMPEG=OFF
    -DWITH_SWSCALE=OFF
    -DWITH_CAIRO=OFF
    -DWITH_OPENH264=OFF
    -DWITH_AOM=OFF
    -DWITH_DAV1D=OFF
    -DWITH_SOXR=OFF
    -DWITH_YUV=OFF
    -DWITH_GSM=OFF
    -DWITH_LAME=OFF
    -DWITH_FAAC=OFF
    -DWITH_FAAD2=OFF
    -DWITH_PCSC=OFF
    -DWITH_MEDIACODEC=OFF
    -DWITH_DEBUG_ALL=OFF
    # TLS 用 OpenSSL（FreeRDP 每个版本都实测的后端），MbedTLS 明确关掉
    -DWITH_MBEDTLS=OFF
    # OpenSSL 3 把 MD4/RC4 挪进了 legacy provider，而静态链接的 libcrypto 默认不带
    # 可加载的 provider 模块（legacy.so 是个独立文件）——NLA 会在算 NT 哈希时失败。
    # WinPR 自带这三样的实现，官方 Android 构建也是这么开的（见 client/Android/cmake）。
    -DWITH_INTERNAL_MD4=ON
    -DWITH_INTERNAL_RC4=ON
    -DWITH_INTERNAL_MD5=ON
    -DWITH_CHANNELS=ON
    -DWITH_OPENSLES=ON
    # 只留远程桌面本体用得上的通道：声音 + 画质扩展（GFX/位图缓存）+ 显示与剪贴板
    -DCHANNEL_ECHO_CLIENT=OFF
    -DCHANNEL_ECHO_SERVER=OFF
    -DCHANNEL_DRIVE_CLIENT=OFF
    -DCHANNEL_PRINT_CLIENT=OFF
    -DCHANNEL_SERIAL_CLIENT=OFF
    -DCHANNEL_SMARTCARD_CLIENT=OFF
    -DCHANNEL_TSMF_CLIENT=OFF
    -DCHANNEL_URBDX_CLIENT=OFF
    -DCHANNEL_ENCOMSP_CLIENT=OFF
    # remdesk（远程协助）的 *-common 不进安装产物，静态链接会缺符号；手机上看桌面也用不到
    -DCHANNEL_REMDESK_CLIENT=OFF
    # rail（RemoteApp）要保留：FreeRDP 官方 Android 客户端（android_rail.c）直接引用它的入口
    -DCHANNEL_RAIL_CLIENT=ON
    -DCHANNEL_GEOMETRY_CLIENT=OFF
    -DCHANNEL_AIPIPE_CLIENT=OFF
    -DCHANNEL_AINPUT_CLIENT=OFF
    -DCHANNEL_CREDITCARD_CLIENT=OFF
    -DCHANNEL_VIDEO_CLIENT=OFF
)

for ABI in "${ABIS[@]}"; do
    echo "=============================================================="
    echo " $ABI"
    echo "=============================================================="
    PREFIX="$HERE/prebuilt/$ABI"
    BUILD="$HERE/build/$ABI"
    # 不删构建目录：CMake 会就地重新配置，改了开关也只重编受影响的目标。真要全量重来，
    # 手动删 third_party/build/<abi> 即可。
    mkdir -p "$PREFIX" "$BUILD"

    # ---- OpenSSL ---------------------------------------------------
    # 静态、无汇编（no-asm：省掉 perlasm 与 nasm 这条分支，局域网远程桌面的 TLS 吞吐
    # 用软件实现完全够）、不装命令行程序与文档。
    # 每个 ABI 在各自目录里 out-of-source 构建：源码树共用一份，目标文件互不串。
    OSSL_SRC="$HERE/openssl-$OPENSSL_VERSION"
    if [ ! -d "$OSSL_SRC" ]; then
        TARBALL="$HERE/openssl-$OPENSSL_VERSION.tar.gz"
        [ -f "$TARBALL" ] || { echo "缺 $TARBALL"; exit 1; }
        echo "$(printf '%s  %s' "$OPENSSL_SHA256" "$TARBALL")" | (cd "$HERE" && sha256sum -c -) || {
            echo "OpenSSL 源码校验失败"; exit 1; }
        tar xzf "$TARBALL" -C "$HERE"
    fi
    if [ -f "$PREFIX/lib/libcrypto.a" ]; then
        echo "  OpenSSL 已有产物，跳过（删掉 $PREFIX/lib/libcrypto.a 可强制重编）"
    else
        # OpenSSL 只支持"干净源码树"的 out-of-source 构建：源码树里只要残留一份
        # configdata.pm/Makefile（比如第一次手工 in-source 配置过），configure 就会报
        # "libcommon.a is given as source ... but no such library is built"
        if [ -f "$OSSL_SRC/configdata.pm" ] || [ -f "$OSSL_SRC/Makefile" ]; then
            echo "  清理 OpenSSL 源码树里的残留配置"
            ( cd "$OSSL_SRC" && { [ -f Makefile ] && make clean >/dev/null 2>&1 || true; } \
                && rm -f Makefile configdata.pm )
        fi
        OSSL_BUILD="$BUILD/openssl"
        mkdir -p "$OSSL_BUILD"
        step_in "$OSSL_BUILD" "OpenSSL configure" "$BUILD/openssl.configure.log" \
            perl "$(cygpath -m "$OSSL_SRC")/Configure" "$(ossl_target "$ABI")" \
            no-shared no-asm no-tests no-apps no-docs \
            "-D__ANDROID_API__=$API_LEVEL" \
            "--prefix=$PREFIX" "--openssldir=$PREFIX/ssl"
        step_in "$OSSL_BUILD" "OpenSSL build" "$BUILD/openssl.build.log" \
            make -j"$JOBS"
        step_in "$OSSL_BUILD" "OpenSSL install" "$BUILD/openssl.install.log" \
            make install_sw
        echo "  OpenSSL → $(ls "$PREFIX"/lib/*.a 2>/dev/null | wc -l) 个 .a"
    fi

    # ---- FreeRDP ---------------------------------------------------
    # WINPR 提供 pthreads/critical 这些 Windows 兼容层，NLA/CredSSP 要的 NTLM(MD4/RC4/HMAC)
    # 与 TLS 全在里面；工具与测试在安卓上没有意义。
    # CMake 是 Windows 原生程序：脚本里一律用 MSYS 路径，传给它的要转回 D:/ 形式
    # 32 位 ARM 的 __thread 在安卓上要显式换成模拟 TLS：原生 TLS 会引用
    # __tls_get_addr，而 bionic 的 libc 到 API 30 才导出这个符号（我们 minSdk 24），
    # 链接时直接报 undefined symbol。模拟 TLS 的运行时由 clang 的 compiler-rt 提供。
    TLS_OPTS=()
    if [ "$ABI" = "armeabi-v7a" ]; then
        TLS_OPTS+=(-DCMAKE_C_FLAGS=-femulated-tls)
    fi

    step "FreeRDP configure" "$BUILD/freerdp.configure.log" \
        "$CMAKE" -S "$(cygpath -w "$HERE/freerdp")" -B "$(cygpath -w "$BUILD/freerdp")" -G Ninja \
        -DCMAKE_MAKE_PROGRAM="$(cygpath -w "$NINJA")" \
        -DCMAKE_TOOLCHAIN_FILE="$(cygpath -w "$NDK_WIN/build/cmake/android.toolchain.cmake")" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
        -DCMAKE_INSTALL_PREFIX="$(cygpath -w "$PREFIX")" \
        "${TLS_OPTS[@]}" \
        -DCMAKE_PREFIX_PATH="$(cygpath -w "$PREFIX")" \
        -DOPENSSL_ROOT_DIR="$(cygpath -w "$PREFIX")" \
        -DOPENSSL_INCLUDE_DIR="$(cygpath -w "$PREFIX/include")" \
        -DOPENSSL_CRYPTO_LIBRARY="$(cygpath -w "$PREFIX/lib/libcrypto.a")" \
        -DOPENSSL_SSL_LIBRARY="$(cygpath -w "$PREFIX/lib/libssl.a")" \
        -DWINPR_APPXLOADER=OFF \
        -DWITH_WINPR_TOOLS=OFF \
        "${FREERDP_OPTS[@]}"
    step "FreeRDP build" "$BUILD/freerdp.build.log" \
        "$CMAKE" --build "$(cygpath -w "$BUILD/freerdp")"
    step "FreeRDP install" "$BUILD/freerdp.install.log" \
        "$CMAKE" --install "$(cygpath -w "$BUILD/freerdp")"
    echo "  FreeRDP → $(ls "$PREFIX"/lib/*.a 2>/dev/null | wc -l) 个 .a 总计"
done

echo
echo "完成。静态库在 third_party/prebuilt/<abi>/lib，rdpcore/CMakeLists.txt 会从这里取。"
