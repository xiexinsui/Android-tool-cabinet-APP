# third_party —— 内置远程桌面的原生依赖

这里放的是 `freerdpcore/`（FreeRDP 官方 Android 客户端 core 的原生层）交叉编译所需的**源码与构建脚本**，以及少量“Build 工具补齐”。
编出来的静态库在 `prebuilt/<abi>/`，与 `build/`、源码包一样都不进仓库（见根目录 `.gitignore`）。

## 版本与来源

| 组件 | 版本 | 来源 | 校验 |
| --- | --- | --- | --- |
| FreeRDP | 3.28.0 | `https://github.com/FreeRDP/FreeRDP`，`--depth 1 --branch 3.28.0` | git tag |
| OpenSSL | 3.5.9 (LTS) | `https://github.com/openssl/openssl/releases/download/openssl-3.5.9/` | SHA-256 `603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a`（脚本每次校验） |
| Perl 模块 | 见下 | Perl 5.42 官方仓库 / CPAN | 纯 Perl，仅构建期使用 |

FreeRDP 与 OpenSSL 的源码**不随仓库提交**：`build-android.sh` 发现缺源码会按上表的固定版本
拉取，再校验哈希。要离线构建就手动把源码放到本目录（`freerdp/` 与 `openssl-3.5.9.tar.gz`）。

## 为什么需要 perl-lib

Windows 上 OpenSSL 的 `Configure` 需要 perl，而 Git for Windows 自带的 perl 是精简版，
缺了一串**核心**模块，第一次跑会依次报：

- `Locale/Maketext/Simple.pm` → 从 Perl 官方仓库 `Perl/perl5`（maint-5.42 分支）取
  `dist/Locale-Maketext/lib/Locale/Maketext.pm` 与 `cpan/Locale-Maketext-Simple/lib/.../Simple.pm`
- `ExtUtils/MakeMaker.pm` → CPAN `ExtUtils-MakeMaker-7.70`
- `Pod/Usage.pm`、`Pod/Simple.pm`、`Pod/Text.pm`、`Pod/Escapes.pm` → CPAN `Pod-Usage-2.05`、
  Perl 官方仓库 `cpan/Pod-Simple`、CPAN `podlators-v6.1.1`、CPAN `Pod-Escapes-1.07`

脚本用 `PERL5LIB=third_party/perl-lib` 指过去，**不动系统 perl**。这些模块都是纯 Perl，
许可证随 Perl 本体（Artistic License 1.0 或 GPLv1+，见各文件头部）。

## 构建

前置（本机已具备，换机器要装）：

- Android SDK + **NDK 29.0.13113456**（`D:/Android/Sdk/ndk/...`）
- **CMake 3.31.5 + Ninja**：`sdkmanager "cmake;3.31.5"`
- Git Bash 自带的 perl（加上面补齐的模块）
- NDK 自带 `make.exe` 与 `yasm.exe`（`ndk/<版本>/prebuilt/windows-x86_64/bin/`，交叉编译 OpenSSL 用）

```bash
# 两个 ABI 都编（arm64-v8a 与 armeabi-v7a）
third_party/build-android.sh
# 只编一个
third_party/build-android.sh arm64-v8a
```

产物：`prebuilt/<abi>/lib/{libfreerdp3,libfreerdp-client3,libwinpr3,libssl,libcrypto}.a`
与 `prebuilt/<abi>/include/{freerdp3,winpr3,openssl}`。之后 `./gradlew :app:assembleDebug`
会由 `:freerdpcore` 的 `externalNativeBuild` 把这些静态库链进 `libfreerdp-android.so`。

## 关键构建决定（踩过的坑）

1. **TLS 用 OpenSSL，不用 MbedTLS**。FreeRDP 顶层 CMake 把 OpenSSL 写成了 `REQUIRED`，
   `WITH_MBEDTLS` 只是“额外再找一个”，没有 OpenSSL 连 configure 都过不去；而 MbedTLS 在
   FreeRDP 里仍标注为 experimental。1.x 认为“安卓上编 OpenSSL 必死”，实际上缺的只是
   GNU make 与几个 perl 模块——NDK 自带 make，perl 模块已补齐。
2. **`no-asm`**：省掉 perlasm/nasm 分支，局域网远程桌面的 TLS 吞吐用软件实现够用；
   代价是握手与加密略慢。
3. **`no-shared` 静态库**：APK 里只带一个 `libfreerdp-android.so`，不需要在运行时找插件目录。
4. **`WITH_MBEDTLS=OFF`、`WITH_SWSCALE=OFF`**：后者默认 ON 会强制找 FFmpeg。
5. **通道取舍**：开 `rdpsnd`（声音，OpenSL ES 后端）、`rdpdr`、`drdynvc`、`rdpgfx`、`rdpdisp`、
   `cliprdr`、`rail`、`rdpei`、`audin`（FreeRDP 官方 Android 客户端会引用 rail/rdpei/cliprdr）；
   关 `remdesk`（`*-common` 不进安装产物，静态链接会缺符号）、驱动/打印/串口/智能卡/摄像头等。
6. **API 24**：声音走 OpenSL ES（API 9 起），不像 1.x 那样为了 AAudio 被迫提到 26。
7. **OpenSSL 只支持干净的 out-of-source 构建**：源码树里残留 `configdata.pm`/`Makefile`
   会让 configure 报 `libcommon.a is given as source ... but no such library is built`，
   脚本会先清理再配置；传给 `Configure` 的源码路径要用 `E:/...` 形式（Windows make 不认 `/e/...`）。

## 许可

- FreeRDP：Apache-2.0（`freerdp/LICENSE`）；其官方 Android 客户端 core（`freerdpcore/` 的来源）为 MPL-2.0
- OpenSSL：Apache-2.0（源码包内 `LICENSE.txt`）
- Perl 模块：Artistic/GPL（随 Perl 本体）

这些库以静态方式链入 `libfreerdp-android.so`，分发 APK 时请保留各自的许可声明；README 的
「开源项目与致谢」一节会一并列出。
