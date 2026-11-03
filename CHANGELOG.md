# 更新日志

每次推送在顶部追加一条，分类用「新增 / 修改 / 修复 / 移除」。提交信息主题与本文件对应条目保持一致。

## 文档修订 — 2026-11-03（不产出 APK，`version.properties` 保持 2.0.71）

把 README 逐节拉到代码上核对了一遍：改掉 10 余处与实现不符的表述、填上一整节空白的致谢、清掉 1.x 曾经专门清理过的真实地址，并顺手让 `.gitignore` 真正对上 README 承诺的入库范围。**代码逻辑一行没改**——核文档时查出来的 14 条真问题全部如实写进 README 新增的「已知问题」小节，等下一轮按清单收尾。

### 修复（隐私：这一条必须排在最前）

- 2.0 新写的 RDP 验证记录把**那台测试机的真实内网地址**（`192.168.5.x` 那一段里的一个具体主机）写回来了（CHANGELOG 16 处 + README 1 处），自己的 **dynv6 DDNS 域名**（字面含用户名，比内网地址更该避讳）与公网 IPv6 的 `/64` 前缀也进了 CHANGELOG 的验证段。1.x 提交 `9b48e84` 专门清过这类信息并定下占位写法（`192.168.1.100` / `PC-HOME` / `NAS-HOME` / `C8:BF:4C:XX:XX:XX`），本次照它改齐。**本条目本身也不复述原始值**——记录"清掉了什么"不需要把原值再抄一遍
- **顺序上的讲究**：先改文档、后建 git 历史，且只用一个 commit。脱敏若放在第二个提交里，真实地址会永久留在已推送的历史中，补一条"清理"提交撤不干净

### 修复（README 与代码不符）

- **权限清单**：原文写「权限只有三项」，实际 `AndroidManifest.xml:4-27` 有 10 项（`CHANGE_WIFI_MULTICAST_STATE`、`FOREGROUND_SERVICE(_DATA_SYNC)`、`POST_NOTIFICATIONS`、`READ_MEDIA_*`、`READ_EXTERNAL_STORAGE maxSdk=32`），freerdpcore 再带入 `MODIFY_AUDIO_SETTINGS` 与 `ACCESS_WIFI_STATE`；「运行期只弹一次麦克风」也不对——通知权限和相册备份的媒体权限都会弹。同一节里「下载的文件在应用私有目录、卸载即消失」与本文件另一处「下载默认落在公共 下载/工具箱」自相矛盾，按 `DownloadStore.kt:12-21,77,96-105` 改齐：**Android 10+ 走 MediaStore 写公共目录、卸载不带走；≤9 才退回私有目录**
- **概述三处**：主页是 **5 个**可用入口不是四类（多出「已下载 / 最近打开」）；用 Compose 的是**四页**不是两页（浏览、预览、解压、已下载）；`freerdpcore` 不是「默认不参与构建」，`settings.gradle` 无条件 `include ':freerdpcore'`，缺静态库时 CMake 直接报错
- **环境与构建**：「platform 34、build-tools 34.0.0」与同文档「compileSdk 37 + build-tools 36.0.0」打架——按 `app/build.gradle:39`、`freerdpcore/build.gradle:14-15` 写成 Platform 37 / NDK 29.0.13113456 / CMake 3.31.5，并说明**工程没声明 `buildToolsVersion`**，36.0.0 是 AGP 9.4.0 的默认值、不是钉住的。「命令行构建用 JDK 21」也不对：`gradle/gradle-daemon-jvm.properties` 把守护进程钉成 **JDK 25（JetBrains 构建）**，`compileOptions 17` 只管源码级别。镜像也分两处：Maven 走阿里云、**Gradle 发行包走腾讯云**（`gradle-wrapper.properties:4`）
- **RDP 参数清单按 `LibFreeRDP` 重写**：补上实际每次都发的 `/gdi:sw`、`/scale:`、`/kbd:unicode:on`、`/log-level:`；`/rfx`、`/gfx`、`/sound` 是条件项（各带一条 `/network:auto`）；`/sec:`、`/tls:`、`/cert-name:`、`/client-hostname:`、`/drive:`、`/printer:`、`/microphone`、`/dvc:rdpecam` **在工具箱里永远发不出去**——对应的开关没人能打开
- **「1.x 那套自绘状态行、连接详情对话框在这版被重新接回来了」是半错**：`CHANGELOG:368` 自己就写着会话屏不再有这个；freerdpcore 里 grep 不到状态行，只有 `RdpActivity.java:200,219` 与 `LanScanActivity.java:485` 的**连接簿/扫描页**状态行，会话页那个信息面板是 `SessionActivity.showSessionInfo()`（:847-931）。接回来的只有输入手感那一层（指针加速、触摸板推光标、软跟随）
- **把 1.x 的实测当成 2.0 的一条**：「轻点/双击/长按拖动、软键盘与锁存修饰键面板、Ctrl+Alt+Del 都实测有效」是 1.x 自绘会话页的结论；2.0 已按本轮真机记录改写，并明确标出扩展键面板现在没入口、双指/三指手势仍未验证
- **域名解析**长按菜单是 **4 项**，第一项是「远程连接」（`DnsActivity.java:236-270`），原文只写了三项
- **许可证写错的一处**：`com.android.tools:desugar_jdk_libs:2.1.5` 的 POM 里 `<licenses>` 声明的是 **GPLv2 + Classpath Exception**，原文写成「Android SDK」。这张表的许可现在逐个读的是 `~/.gradle/caches/.../*.pom`，不是照抄旧值

### 新增

- **「原生层依赖」这一节此前是空的**（`### 原生层依赖` 与下一节之间没有任何内容），填上：NDK 29（含它自带的 `make.exe`/`yasm.exe`——1.x 认定"安卓上编 OpenSSL 必死"，缺的其实就是这个 make）、CMake 3.31.5 + Ninja、Git for Windows 的 perl/tar/curl/sha256sum/cygpath、补进 `perl-lib` 的六个模块（`Locale::Maketext(+Simple)`、`ExtUtils::MakeMaker 7.70`、`Pod::Usage 2.05`、`Pod::Simple`、`Pod::Text(podlators 6.1.1)`、`Pod::Escapes 1.07`）、foojay-resolver 1.0.0、JetBrains JDK 25、Node（`gen_icons.js`）、以及链接期的 `log jnigraphics OpenSLES z m dl atomic`（`CMakeLists.txt:42-51`）
- **致谢里 FreeRDP 被列了两次**，而第二条（"没有链接它的代码，RDPSND 协商顺序、滚轮与指针事件语义以它为对照"）描述的其实是 **1.x 的 IronRDP**——改成 IronRDP 条目并说明 2.0 已不再链接它，另立一条 aFreeRDP。依赖表补齐 `freerdpcore/build.gradle:41-45` 的五个 AndroidX（appcompat/preference/recyclerview/lifecycle-viewmodel/livedata）与 `slf4j-api`、`bcprov-jdk18on`、`net.engio:mbassador 1.3.2`、`com.hierynomus:asn-one 0.6.0`、`kotlin-stdlib` 这些传递依赖
- **README 新增「已知问题（只记录、未修改）」小节**，14 条按严重度排。其中三条是会咬人的：① `TransferHub.kt:237` 的取消只挡状态跃迁、任务体 `:267` 照跑，所以「停止全部」之后排队的下载**全都照样传完**，还会在 `:244/:292` 给已放弃的任务补写续传存档；② `TransferHub.kt:304-310` 的 `pin()` 用 `runCatching` 包住 `SmbHub.acquire`，失败也计数，之后 `unpin` 会把**浏览页正在用的连接扣穿关掉**；③ `SmbStreamService.kt:117-122` 只要代理活着就回旧 URL、不比 host/path，5 分钟空闲窗内换文件会**播成上一个**
- **两处代码死路写进文档**：`ApplicationSettingsActivity` 没在任何 manifest 声明（freerdpcore 那份只有 `SessionActivity` + `ClipboardImageProvider`），而 `SessionDialogs.java:119→230` 会 `startActivity` 它——走到「实验特性」拦截（`SessionActivity.java:1158`）就是一个 `ActivityNotFoundException`；连带 `clientName / hideStatusBar / keepScreenOn / swapMouseButtons / invertScrolling / useBackAsAltf4 / nightMode / disconnectTimeout / experimental.*` 这些**被读却没人能改**的偏好。`/cert:ignore` 在 `LibFreeRDP.java:543` **硬编码**，于是 `OnVerifiyCertificateEx`/`OnVerifyChangedCertificateEx`（`SessionActivity:1118-1134`）与 `SessionDialogs.verifyCertificate`（:180,202）整条证书告警链走不到——如实写成「证书校验被无条件忽略」，而不是像原文那样完全不提这件事
- **「适配屏幕分辨率」转屏不生效的真正原因**：不是"重建会话要回到应用侧"，是判断条件恒为假——`SessionActivity.java:481-489` 只看 `isFitScreen()`，而启动器一律 `setResolution("custom", 宽, 高)`（`RdpSessionActivity.java:75`），这个标志永远不会是 true。两条修法都不必重连（MS-RDPEDISP 当场生效已实测）
- **扩展键面板（Fn/小键盘/方向键）没有入口**：`toggleExtendedKeyboard()` 唯一调用点是 `SessionInputManager:513` 的 `onTouchPointerToggleExtKeyboard()`，而那个 `TouchPointerView` 在 `SessionActivity.java:307` 被永久 `GONE`
- 补齐代码里有、文档零提及的入口与数值：主页「已下载」菜单的**「清空最近打开」**、分贝仪的**「刷新设备」**（`MainActivity.java:89-94`）、局域网条目菜单的「设置登录账号 / 删除已存凭据 / 从设备列表移除」；`SmbSettings` 的 `cache_mb` **有 setter 但没有 UI 调用点**所以改不了；一节的「各处写死的上限」（`MAX_JOBS=12`、`RecentFiles=20`、`SmbImages` 8/64 MB 与 4 并行、`SmbZip` 24 MB/66 KB、SSDP 8 条/64 KB、扫描 48 线程、续播 8 s/95%、搜索 400/20000/50、回环 256 KB 与 5 分钟、试连 4 条 ×1.5 s）
- **目录结构**补上 `RdpProbe.java`、`RdpSettings.java`、`RecentFiles.kt`、`SmbExternalPlay.kt`、`PlaybackPositions.kt` 与根目录的 `.gitattributes`、`version.properties`、`gradle/gradle-daemon-jvm.properties`、`build-shim/`、`_verify/`；freerdpcore 从"5 个类"扩到实际的 28 个 java，并如实标出裁剪留下的零引用资源（`layout/{home, activity_bookmark, activity_about, activity_help, activity_shortcuts, bookmark_list_item}.xml`、`menu/` 三个、`xml/{advanced, gateway, screen, performance_flags, debug}_settings.xml`）与死类（`ExternalDisplayManager`、`KeystoreHelper`）
- 顺手纠正一条子代理给的结论：它说 `DoubleGestureDetector` 是死代码，实际 `SessionView.java:171/442/1230` 一直在用——**双指手势仍走它**，只有 `utils/GestureDetector.java` 的检测主体没人实例化（只剩 `DoubleGestureDetector:18` 引用它的 `OnGestureListener` 接口）。文档按核实后的写

### 修改

- `.gitignore` 补三条，让 README「入库范围」那张表不再有名无实：`/build-shim/`（1.x 自写 shim 的 `librdpcore.so` 产物，源码线已删但本机还留 16 MB）、`/third_party/ExtUtils-MakeMaker-7.70/`（CPAN 原始包，内容已整理进 `perl-lib`，`perl-lib` 本身**必须入库**——`build-android.sh:47` 的 `PERL5LIB` 指它、`:49` 还检查它在不在）。「仓库与提交规范」那张表按实际 `.gitignore` 重写，并新加一段「入库前必看一眼的东西」把上面那套隐私占位写成规则
- 根目录的 `g1.png`/`g2.png`（1.8 MB 真机截图）与 `i2.html`/`idx.html`（别的任务留下的 Ubuntu WSL 下载页存档）移进已被忽略的 `_verify/`，是移动不是删除
- **`.gitignore` 的尾斜杠坑**：写文档时按"`/third_party/openssl-*/` 同时盖住源码包和它的 `.sha256`"落笔，实跑 `git add -A --dry-run` 才发现 `openssl-3.5.9.tar.gz.sha256` 在候选列表里——带结尾斜杠的规则**只匹配目录**，那个 `.sha256` 是文件，而 `/third_party/*.tar.gz` 也不匹配 `.tar.gz.sha256` 这种双后缀。校验值本身没有隐私、对复现构建还有用，所以决定**让它入库**并把 README 两处（目录结构与入库范围表）改成事实。教训：忽略规则不能靠读 `.gitignore` 推断，要用 `git add -A --dry-run` 和 `git check-ignore -v` 各过一遍
- `LocalFileProvider` 那条「工程不引 AndroidX」的理由、以及 `IspActivity.java:584` 的同款注释，都随 2.0 引入 Compose/appcompat 失效——README 改写成"历史原因、现有实现工作正常所以留着"，代码注释列入已知问题等下一轮

- **`.gitattributes` 少钉了几类换行符**：原来只钉了 `gradlew`、`*.java`、`*.xml`、`*.gradle`、`*.properties`、`*.md`、`.gitignore`，没钉 `*.sh`、`*.kt`、`*.c`/`*.h`、`*.pm`/`*.pod`。本机 `core.autocrlf=true`（Git for Windows 装机默认）下，`* text=auto` 只保证**库内**是 LF、**检出到工作树会转成 CRLF**——新克隆的那台机器上 `./third_party/build-android.sh` 会因行尾 `\r` 报 `bad interpreter`，`perl-lib` 的 `.pm` 也会带脏参数，而这脚本正是 README「换机器时按这张表装东西」要求别人跑的。现在把这几类都钉成 `eol=lf`，复核方式 `git ls-files --eol`（看 `i/lf` 与 `attr/text eol=lf`）
- **入库前把候选清单实跑了一遍**（`git add -A --dry-run` + `git check-ignore -v`）：222 个新增、17 个删除、13 个修改；`build-shim/`、`third_party/{freerdp,openssl-3.5.9,build,prebuilt}/`、`ExtUtils-MakeMaker-7.70/`、`_verify/`、`local.properties` 均确认被排除，敏感后缀（`.jks/.keystore/.p12/.pfx/.pem/.env/secrets*/token/passwords*`）零命中，>500 KB 的入库文件零个（最大的入库二进制仍是既有的图标 png 与 `gradle-wrapper.jar`）

### 验证

- 隐私：`grep -rnE '192\.168\.5\.[0-9]|<原来那个 DDNS 域名的字面量>|<原来那个 IPv6 前缀>' README.md CHANGELOG.md` → 0 命中（命令里的字面量按本机实际值填，本文档不复述）；MAC 形态（`([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}`）→ 0 命中；机器名（1.x 清过的那三个）→ 0 命中；**并且这条检查也跑在已提交的对象上**（`git show <commit>:CHANGELOG.md | grep -c …` → 0），只查工作树是不够的——本次就是提交之后才在 blob 里查出脱敏条目自己带着原值，随后重写了那个尚未推送的提交。`192.168.1.0/24` 那组示例与 `飞牛 NAS`、`Mi 10 Pro` 这类产品名按 1.x 的既有约定保留
- 每条改动都带 `文件:行`，行号来自本轮实测：`TransferHub.kt:234-301`、`pin()` 与 `unpin()`、`SmbDownloadsActivity.kt:321/343-345` 对照 `DownloadStore.kt:165`、`SmbStreamService.kt:117-122`、`LibFreeRDP.java` 全部 `args.add` 逐行过、`SessionActivity.java:307/481-489/1118-1134`、`RdpSessionActivity.java:75`、`SessionDialogs.java:119/230`、`DnsActivity.java:236-270`、`MainActivity.java:89-94`、`IspActivity.java:598/614/864/640`、`SmbBrowseActivity.kt:1387/1233`、`SmbSettings.java:177-181`、`freerdpcore/build.gradle:14-15,41-45`、`CMakeLists.txt:42-51`、`gradle-wrapper.properties:4`、`gradle-daemon-jvm.properties`
- 许可证逐个开 `~/.gradle/caches/modules-2/files-2.1/<group>/.../*.pom` 读 `<licenses>`：slf4j-api=MIT、bcprov="Bouncy Castle Licence"（原文写 MIT，已改）、mbassador 1.3.2=MIT、asn-one 0.6.0=Apache-2.0、smbj=Apache-2.0、jcifs 3.0.4=LGPL-2.1、**desugar_jdk_libs=GPLv2+CE**（原文写「Android SDK」）
- 传递依赖的具体版本不写死：本机缓存里 bcprov 有 1.80.2/1.85/1.85.2 三份，无法从构建产物断定本工程解析到哪一版，所以那一列只写"由 jcifs-ng / SMBJ 的 POM 传递决定、本工程未钉"
- **未做**：本轮不产出 APK，所以没有装机回归；`version.properties` 保持 114 / 2.0.71。**注意别为了"验一下文档"去跑构建**——`assemble*`/`install*`/`bundle*`/`package*` 会在配置阶段把版本号 +1，让日志与已发布条目对不上（判定在 `app/build.gradle:16-28`）
- 本轮唯一"运行"的验证是静态核对：代码与文档两处都读，凡两处不一致就改文档、并把属于代码的那一侧写进「已知问题」

## v2.0.71 — 2026-10-31

「能不能用 UDP」的结论：**不能，不是配置问题**。顺带把信息面板的措辞写准。

### 修改

- 协议行里传输方式改成直接写 `TCP` / `UDP`，并在服务端声明了 UDP 而实际走 TCP 时补一句「服务端支持 UDP，FreeRDP 客户端未实现，实际走 TCP」。原来写的是「UDP 可协商未启用」——读起来像"把开关打开就行"，其实那条路根本不存在

### 调查结论（为什么不能用 UDP）

- `libfreerdp/core/multitransport.c:194` 客户端侧的处理器就叫 **`multitransport_no_udp()`**：服务端发来 UDP 引导请求（`INITIATE_REQUEST_PROTOCOL_UDPFECR`）时**直接回 `E_ABORT`**。`rdp.c` 里虽然按 `UDPFECL|UDPFECR` 建了 multitransport 对象，但真正处理请求的就是这个 no-udp 分支
- `RDPUDP` 相关代码只出现在**服务端**（`peer.c`、`multitransport_server_request`）和协议常量里，客户端没有 UDP 数据面
- `/multitransport` 命令行开关只是把 `TRANSPORT_TYPE_UDP_FECR|FECL|PREFERRED` 写进能力集，通道建不起来，所以加了也没用
- mstsc 那张图里的「传输协议 UDP」是微软客户端实现了 MS-RDPEMT 的 UDP 数据面；要用只能换客户端（mstsc / Windows App），或者等 FreeRDP 补上这块

### 验证

- 代码路径已核对（`multitransport_no_udp` 的调用点在 `multitransport_new()` 的客户端分支）；构建通过
- **未装机验证**：改这一版时手机前台被别的应用占了，为免误点没有继续往设备发触摸事件

## v2.0.68 — 2026-10-31

工具栏可以**长按拖动**了，收起后的把手同样能拖；松手吸附到最近的一侧。

### 新增

- 长按 500 ms 进入移动（震一下），之后手指带着整条走，松手横向吸附到最近的一边、纵向留在原地，带 140 ms 收尾动画。短按仍然是点击
- 每个按钮都挂同一套触摸监听（`DragTouch`）：自己吃掉事件、短按转发 `performClick()`，这样长按后的 MOVE 才收得到
- 收起 / 展开时如果已经手动挪过位置，**重新贴回最近的一侧**——只剩把手时宽度小了一截，不重贴会浮在半空离边一截

### 修复

- **父级抢手势**：这排按钮在 `HorizontalScrollView` 里，长按刚到手一动，父级 `onInterceptTouchEvent` 就把手势抢走并丢来一个 `CANCEL`，拖动永远进不去（日志实测：DOWN → armed → 立刻 CANCEL，一个 MOVE 都没有）。按下时 `requestDisallowInterceptTouchEvent(true)`、抬起时放开
- **吸附位置停在 0**：落位时只写 `leftMargin` 不行——之前调过 `setMarginStart()`，RelativeLayout 解析时取的是 start 边距，于是横向吸附总回到 x=0。现在 start/left 一起写
- 手动挪过之后 `setInsets()` 只更新可拖动边界（转屏后把位置夹回可见范围），不再改 margin，否则一转屏又被打回原位

### 验证

- Mi 10 Pro → 192.168.1.100（NLA），竖屏 1080×2340：工具栏初始 `17,228-853,295`，长按「收起」拖到 (820,1200) 松手后变成 `227,1162-1063,1229`——横向吸附到右边（`1080-836-17=227` 正好）、纵向跟着走；短按「信息」仍能弹出对话框，说明点击没被拖动逻辑吃掉
- 未验证：收起后把手的拖动、收起/展开时的重新贴边（测到一半手机被切到别的应用，就没继续发触摸事件）。这两个分支和已验证的走同一套代码，差别只在尺寸变化后补一次 `snapToNearestSide`

## v2.0.63 — 2026-10-31

鼠标模式下，**画面四周的黑边也能推光标**了。

### 修复

- 画面铺不满视口时（比如 1920×1080 桌面放在 2.15:1 的横屏视口里），四周那圈黑边属于 `ScrollView2D` 而不是 `SessionView`，落在黑边上的手势根本到不了单指状态机——鼠标模式下在边上怎么划都没反应
- `ScrollView2D.setOnTouchListener` 把未被 `SessionView` 消费的事件转给 `SessionView.handleExternalTouch()`，后者只在鼠标模式接、直接走同一套状态机。触摸板只吃相对位移，坐标系差一个常量偏移无所谓，所以不用做坐标换算；直控模式下点黑边本来就没有意义，直接不接，本地平移照常
- 状态机主体从 `onTouchEvent` 抽成 `dispatchFinger()`，两条入口共用

### 验证

- Mi 10 Pro → 192.168.1.100（NLA），横屏，内容 1857×1045 居中于 2250×1045 视口（左右各约 196 px 黑边）：在**左黑边** (150, 700) 按下拖到 (600, 400)，画面里的白色光标出现并移动——会话刚建立时还没有光标（位置是 -1），所以这次出现本身就是黑边生效的证据
- 未验证：黑边上的轻点/拖拽其它分支（长按右键、tap-and-a-half）手感，以及直控模式下黑边不响应（代码里按模式返回 false，没在真机上逐项点）

## v2.0.62 — 2026-10-31

跟随回到 1.x 的原样：**光标一进边缘带就把画面推到位，光标停在带子边、画面继续跟着手指滑**。前一版加的缓动和"松手补一次"都是自创的，前者不跟手、后者每次松手都滑一下，正是那个顿挫。

### 修复

- 增益回到 **1.0**（`FOLLOW_GAIN`），边缘带用 1.x 的 **15% / 64dp**：光标进带即推到位，光标随即停在带子边上，画面继续跟着手指滑——边滑边走。想更缓只改这一个常数（代价是光标更快贴近边线）
- **松手时不再补动画**：1.x 的 `revealCursorIfOffscreen` 只在捏合/双击缩放后调用，不是每次手势结束都调。放在 ACTION_UP 里等于每松一次手就滑一下，那是顿挫的来源
- 去掉上一版的像素余量累积（增益 1 时用不上，留着是死代码）

### 验证

- Mi 10 Pro → 192.168.1.100（NLA）：安装后连接、按住拖 900 px 正常，`railContainer` 偏移保持 `286`、画面没有多余滚动——这一版内容是 1857×1045、视口 2250×1045，装得下时本来就没有可移的余地，不动才是对的
- 仍然只能你上手确认：**放大后的跟随手感**（`content > viewport` 只有捏合放大才会出现，这台机注不进第二根手指）。这一版的行为就是 1.x 的，若还嫌快就把 `FOLLOW_GAIN` 调小

## v2.0.61 — 2026-10-31

上一版"松手才动"变成了顿挫，这版改回**边滑边走**：拖动过程中画面连续地跟，但只跟光标所需位移的一部分（增益 0.35），所以既不会卡到松手才跳，也不会一次长拖就窜到极限。

### 修复

- 光标进到边缘带（12% 或 48dp）之后，画面按 `所需位移 × 0.35` 连续滑过去，光标留在带子里慢慢逼近边线。增益小于 1，画面永远比光标慢——这是"跟手"和"窜到边"之间的那个点
- **余量累积**：慢速拖动时单步可能不足 1 px，直接截断会让画面卡住不动（又是一种顿挫），所以小数部分存在 `followCarryX/Y` 里留到下一步
- 手势开始时清零余量；松手时仍保留一次 `smoothScrollBy` 对准（整只跑出视野→拉回正中，只贴边→推到留一小条），作为兜底

### 验证

- Mi 10 Pro → 192.168.1.100（NLA）：按住拖 1000 px，`railContainer` 偏移保持 `286`——这一版内容是 1857×1045 而视口 2250×1045，**装得下时本来就没有可移的余地**，所以画面不动是正确行为；光标落在拖动终点、位置正确，没有多余的滚动
- 仍然没法机器验证：**放大后的连续跟随手感**。`min(视口/画面)` 的基数保证 1× 永远装得下，`content > viewport` 只有捏合放大才会出现，而这台机注不进第二根手指（`sendevent` 要 root）。增益 0.35 是照"跟手但不窜"调的起点，觉得还快或还慢，改 `FOLLOW_GAIN` 一个数就行

## v2.0.60 — 2026-10-31

跟随的时机才是根子所在：**按住期间不该跟随**。手指还按在屏上时每个事件都在移动光标，跟着推画面等于一次长拖就把滚动推到尽头——等松手时视野已经偏到别处。

### 修复

- `setRemoteCursorPosition()` 不再触发视角移动（它每个 MOVE 事件都会走到，是"重复移动到极限边界"的来源）
- 改成**只在手势结束时对准一次**：`onTouchEvent` 的 ACTION_UP / CANCEL 里调 `revealCursorIfOffscreen()`，加上原有的捏合结束、换分辨率、转屏三处。松手时才把视角对准光标：整只在视野外就拉回正中，只是贴边就推到留一小条（8% 或 32dp），走 `ScrollView2D.smoothScrollBy` 缓动过去而不是瞬间跳
- 按住拖动期间画面完全不动——用户有了稳定的参照系来推光标，松手后视野再跟上

### 验证

- Mi 10 Pro → 192.168.1.100（NLA）：鼠标模式下按住拖 900 px（900 ms 一划到底），`railContainer` 偏移全程保持 `286`（滚动量 0，画面没动），光标落在拖动终点、仍画在画面内；松手后也没有多余的滚动
- 仍然没法机器验证：**放大状态下的松手对准**。`min(视口/画面)` 的基数保证 1× 永远装得下，`content > viewport` 只有放大才会出现，而多点触控注不进这台机（`sendevent` 要 root、`adb shell input` 只有一根手指）。这条请你捏一下确认

## v2.0.59 — 2026-10-31

上一版夹住了速度，但没治到根：**触得太早**。手指还按在屏上时，光标每来一个事件都在走，画面就每步都跟着走满同样的量——几下就跑到内容尽头，正是"刷地一下到头了"。

### 修复

- **跟随只在光标真要看不见时才动**：去掉 15% 的软推带（那条带子占了视野的 30%，光标刚走三分之一画面就开始跟），改成「光标还在视野里就完全不挪画面」，越出视野才推，且只推到贴边留一小条（8% 或 32dp，取小的那个）。这样光标有整个视野可走，画面只在非动不可时动一下
- 于是也不再需要每步的速度上限（`maxStep`）和"跑出去就居中"两套分支——一条规则就够，代码少了一半

### 验证

- Mi 10 Pro → 192.168.1.100（NLA）：鼠标模式下滑动推光标 + 点一下，`railContainer` 偏移保持 `286`（= 内容在视口里居中，滚动量 0）——画面没被带着走；光标仍画在画面内、位置正确
- 仍然没法机器验证：捏合放大后的跟随手感。`content > viewport` 只有放大才会出现（`min(视口/画面)` 的基数保证 1× 永远装得下），而多点触控注不进来（`sendevent` 要 root），所以这条只能你上手确认

## v2.0.58 — 2026-10-31

跟随改成与光标同速；工具栏能收起吸附到侧边；信息面板补上帧率、流量与协议。

### 修复

- **视角跑得比光标快**：软跟随一步就把光标从越界处吸到带子边，一次大幅移动（甩动、加速曲线顶到 2.25×）会让画面窜出去一大截。现在把每步的滚动量夹到「这一步光标自己走了多少」，差额留给下一步继续靠近——缓动跟拍，不是一步到位

### 新增

- **工具栏「收起」**：收起后只剩一枚 ☰ 把手，贴右边缘、垂直居中、alpha 0.55，点一下回顶部成全宽一排（1.x 的那套）。换位是改 `RelativeLayout` 的对齐规则（`ALIGN_PARENT_START|TOP` ↔ `ALIGN_PARENT_END|CENTER_VERTICAL`）而不是调 margin，横竖屏切换、导航栏在左在右都不用各算一遍
- **信息面板扩充**（对着 mstsc 那张连接质量图补的）：
  - 显示：当前缩放百分比 + 指针模式
  - 协议：RDP 版本、安全协商（NLA / TLS / 标准 RDP 安全）、编码（RemoteFX + RDPGFX）、UDP 多传输状态（MS-RDPEMT 标志位：支持 / 已激活 / 未启用）
  - 帧率：每秒「服务端发了多少帧位图更新」——桌面静止时就是 0，那是实话不是没采到
  - 流量：本进程接收速率 + 会话开始以来的累计量（基线在连接建立时取，不把之前别的 App 的量算进来）
  - 已连接时长

### 修改

- 原生层加 `freerdp_get_protocol_info()`：读 `instance->context->settings` 里的协商结果（`FreeRDP_RdpVersion` / `NlaSecurity` / `TlsSecurity` / `RdpSecurity` / `MultitransportFlags` / `RemoteFxCodec` / `NSCodec`），拼成 `;` 分隔的字符串交给 Java 本地化
- **流量为什么不走 `freerdp_get_stats`**：它的入参是内部的 `const rdpRdp*`，客户端公共 API 取不到（`freerdp->settings`、`freerdp->rdp` 在 3.28 已经不是公开成员，编译时直接报 no member）。改用 `TrafficStats.getUidRxBytes`，和 1.x 同一套路，这台机可用；不支持时面板显示「本机不支持流量统计」而不是显示 0

### 验证

- Mi 10 Pro → 192.168.1.100（NLA）：
  - 信息面板实测输出 `协议：RDP 10.12 · NLA + TLS · RemoteFX + RDPGFX · UDP 可协商未启用`、`显示：97% · 触控`、`帧率：18 fps · 流量：216.3 KB/s ↓ · 会话内 22.8 MB`、`已连接：0:02:02`；桌面静止时帧率与速率归 0，累计量仍在涨（采样器每秒跑着）
  - 收起后界面上只剩坐标 (2249, 540) 的把手，桌面铺满整屏；点把手，八个按钮回到顶部一排，第一个标签变成「收起」
- 未验证：捏合放大后的同速跟随手感（`sendevent` 要 root、`adb shell input` 注不进第二根手指）

## v2.0.55 — 2026-10-31

修两个真机反馈出来的问题：视角跟随**方向是反的**（光标看着往回走），以及不开全屏时**顶部一大块黑**。

### 修复

- **跟随推反了**：`followCursor()` 软推那一支写成 `marginX - vx`（正数），而 `scrollBy` 要的是「现在的位置 − 想要的位置」，正确写法是 `vx - marginX`（负数）。结果往左推光标，画面跟着往左跑，光标反倒离带子更远——就是"鼠标往回走"。只有左/上两支是反的，右/下本来就对，所以症状是"往一个方向走正常、往回走就乱"。抽成 `followDelta(pos, low, high)` 一条式子，两轴各算各的
- **一轴推了就不看另一轴**：原来是 `else if` 链，横向需要推时纵向完全不参与判断（反之也一样），所以贴边斜着走只跟一半。现在两轴独立
- **顶部那块没人用的黑**：`onWindowInsetsChanged()` 拿 `statusBars | displayCutout` 的 top 给根布局留 padding，MIUI 把刘海也算进 top——实测横屏留 194 px、竖屏留 211 px，而系统真正让给状态栏的只有 35 px（`dumpsys window`：`cur=2340x1080 app=2250x1045`）。现在根布局不为状态栏挖白，桌面一直铺到屏幕顶（状态栏本来就是半透明的，压在画面上），工具栏自己按 `statusBars().top` 往下让，免得按钮盖在系统图标上

### 验证

- Mi 10 Pro（横屏）→ 192.168.1.100（NLA）：不开全屏时 `sessionScrollView` 从 `0,194-2340,1080` 变成 `0,0-2340,1080`，**顶部黑块没了**；画面由 `1512×851` 长到 `1857×1045`（视口大了，基数跟着变大），工具栏落在状态栏下方；触控模式点 (315, 1015) 仍能精准打开 Windows 开始菜单——绝对坐标映射没被改坏
- 方向那条按算术核对：`followDelta(50, 100, 900) = -50` → `scrollBy(-50)` → 内容右移 50 → 光标落到带子边 100 ✓；旧写法给 `+50` → 内容左移 → 光标被推出视野
- 未验证：捏合放大后的实际跟随手感（`sendevent` 要 root、`adb shell input` 注不进第二根手指），需要上手确认

## v2.0.54 — 2026-10-31

修「双指放大后鼠标划出视野就找不到」：视角现在真的跟着光标走，1.x 那套软跟随 + 丢了拉回中心。

### 修复

- **跟随的门槛判错了**：原来写的是「用户放大了」（`userZoom > 1`）才跟随。加了会话内改分辨率之后这条就不成立了——桌面 2340×1080 在非全屏横屏的视口（2250×851）下即使 1× 也会超出视口，光标照样能划出去，而旧门槛直接放过。现在判据换成真正的「画面比视口大」，并且**按轴判**：装得下的那个轴不动，免得白抖一下
- **算视角时读的是还没跑完的布局**：`followCursor` 原来用 `getWidth()` 当内容尺寸，而刚改完缩放时 `requestLayout` 还没落地，读到的是旧尺寸——捏合结束那一帧正是这样算错的，表现就是"放大后鼠标没了"。现在按 `位图 × 当前缩放` 算，永远即时正确
- **挪视角的时机太早**：`revealCursorIfOffscreen()` 改成 `post` 到下一帧再做。可滚动的范围是随内容尺寸定的，布局没跑完就被旧范围夹住，挪不到位
- **软跟随和"丢了居中"合成一条**：原来"整只跑出去就拉回中心"只在捏合结束时做，普通移动只软推到边缘留一圈——快速一划把光标甩出视野时，那一圈推量不够。现在每次光标移动都判：靠近边缘就推，完全跑出去就摆回视野中间

### 验证

- Mi 10 Pro（横屏，视口 2250×851）→ 192.168.1.100（NLA）：会话内 1920×1080 → 1024×768 → 1920×1080 动态改分辨率每次都当场重排（`sessionView` 1512×851 ↔ 1134×851），改完视角自动重新对准；鼠标模式下滑动推光标，箭头仍画在画面内、位置正确
- **没能机器验证**：捏合放大那一条路径——`sendevent /dev/input/event5` 要 root（这台 MIUI 上 shell 不在 `input` 组），`adb shell input` 又注不进第二根手指。逻辑上三处修复都对着这个场景，但请上手捏一下确认手感

## v2.0.50 — 2026-10-31

把分辨率改到**会话的控制界面上**，并且实测出：**这台 Windows 认 display-control，改分辨率不用重连**——1.0 那句"服务端完全不理这个 PDU"是 IronRDP 的发法，换成 FreeRDP 的 disp 通道就成了。

### 新增

- **工具栏「分辨率」键**（[floating_toolbar.xml](freerdpcore/src/main/res/layout/floating_toolbar.xml)）：在会话页里直接改桌面分辨率，选完点「应用」当场生效，不用断开重连。走的是 `LibFreeRDP.sendMonitorLayout()` → `android_disp_send_monitor_layout()` → MS-RDPEDISP 的 Monitor Layout PDU（`/disp` 本来就开着，这条通道一直在，只是没人按）
- **应用后确认一次**：`SessionActivity.applyResolution()` 发出布局后等 1.8 s 比对新位图尺寸——成了报「桌面已切到 1280×800」，没成就报「服务端没接受动态改分辨率，下次连接会按它要桌面」。不确认的话用户会以为改了，回头发现还是老的
- **`SessionResolution`**（[freerdpcore/.../SessionResolution.java](freerdpcore/src/main/java/com/freerdp/freerdpcore/presentation/SessionResolution.java)）：分辨率的唯一存储（`session_resolution_width/height`，0×0 = 适配屏幕）+ 唯一的那份对话框。连接簿的「显示设置」现在只是调同一个对话框（传 `onApply = null` → 只保存、下次连接生效），两处改的是同一个值，不再各存一份

### 移除

- `RdpSettings` 里被这次搬家变成死码的部分：指针模式、缩放方式、灵敏度、桌面尺寸与自适应开关、预设表、`label/presetIndex/effectiveSize/deviceSize` 全删掉，只剩码率档位（色深与是否有损编码由它派生）。连接簿那版对话框的临时字符串（`rdp_display_size_title`/`rdp_display_saved`/`rdp_adaptive_size`/`rdp_size_match_phone_format`）一并删
- **注意迁移**：分辨率存储从 app 侧的 `rdp_display` 偏好文件搬到 freerdpcore 的默认偏好，之前在那儿存过尺寸的人（只有上一版几分钟的窗口）会回到默认 1920×1080

### 验证

- Mi 10 Pro（横屏 2340×1080）→ 192.168.1.100（NLA）：
  - 会话内 1920×1080 → 1280×800：`sessionView` 从 `1512×851` 变 `1361×851`（= 1280×800 按 1.064 等比），**桌面当场重排**，没有重连
  - 勾「适配屏幕分辨率」→ 桌面变 2340×1080：`sessionView = 1843×851`——此时视口是 2250×851（2.64:1），桌面 2.17:1，两侧仍有黑边，因为状态栏/导航栏把可用高度吃掉了
  - 再点「全屏」：`sessionScrollView = 2340×1080`、`sessionView = 2340×1080`——**1:1 铺满整屏，零黑边、不裁、不拉**，任务栏和桌面边缘都在
  - 连接簿 → 显示设置 与会话页 → 分辨率 打开的是同一个对话框，改哪儿结果一致
- 未验证：双指捏合/滚动/右键、三指中键（`adb shell input` 注入不了多点触控）；外接鼠标手感；服务端不认 display-control 的那条提示路径（这台机器认，没法自然触发）

## v2.0.47 — 2026-10-31

按反馈**去掉拉伸**，改成「等比放大缩小 + 让桌面分辨率去对屏幕比例」——想要没黑边靠的是比例对上，不是把画面拽变形。

### 移除

- **拉伸显示模式**（v2.0.45 加的）：工具栏的「拉伸/适应」键、`SessionView.SCALE_STRETCH`、`ApplicationSettingsActivity` 的 `session_scale_mode` 偏好全部删掉。横屏 2340×1080 的视口放 16:9 桌面要拉宽约 48%，看字是扁的

### 新增

- **连接簿的「显示设置」**（[RdpActivity.java](app/src/main/java/com/decibelmeter/app/RdpActivity.java) 的溢出菜单 → 桌面分辨率对话框）：
  - **☑ 适配屏幕分辨率**——按手机此刻的实际像素向服务端要桌面（`RdpSettings.adaptiveSize`），一个远端像素正好一个屏幕像素，1:1、无黑边、不裁不拉
  - 分辨率预设单选：1920×1080 / 1600×900 / 1366×768 / 1280×800 / 1024×768 / 匹配本机（按手机比例算出的横屏尺寸）；勾上「适配」时单选组禁用
  - 放在连接之前这一页是因为**分辨率在握手时谈定**，改不了正在跑的会话；保存后提示「下次连接生效」
- `RdpSessionActivity` 改用 `settings.effectiveSize(this)`：原来它只认「自适应」开关，否则硬编码 1920×1080，选了预设也不生效

### 修改

- `applyFitZoom()` 永远 `min(视口/画面)` 整幅放下；缩放折回**单一等比** `scaleFactor`（v2.0.45 为拉伸引入的 `scaleX/scaleY` 两轴分开随之删掉，坐标映射、光标绘制、软跟随、触摸板位移、滚轮格距都改回一个值）

### 验证

- Mi 10 Pro（竖屏 1080×2340）→ 192.168.1.100（NLA）：连接簿菜单出现「显示设置」，对话框渲染出复选框 + 6 个预设（含「匹配本机（1920×886）」）；勾上适配并应用后重连，信息面板显示 **`桌面：1080×2340`**，`sessionScrollView = 1080×2129`、`sessionView = 966×2094`（基数 0.895 等比）——**桌面铺满整屏、零黑边、无变形，开始菜单与任务栏都在眼里**
- 未验证：双指捏合、双指滚动、双指轻点右键、三指轻点中键（`adb shell input` 注入不了多点触控）；外接鼠标/触摸板手感；「适配」开着时旋转手机不会自动按新方向重连（1.x 有这条，2.x 的会话页在 freerdpcore 里、重建会话要回到应用侧，暂时没接）

## v2.0.45 — 2026-10-31

显示方式也照 1.0 来：**默认永远整幅放下**（横屏不再裁掉任务栏），要零黑边就切「拉伸」。

### 修复

- **横屏画面缺一大块**：v2.0.43 之前横屏按宽度铺满（基数 = 视口宽 / 1920），1920×1080 的桌面在 2340×1080 的屏上会变成 2340×1316，上下各被裁掉约 120~230 px——任务栏直接被挤出可视区，看着就是"显示界面不全"。1.0 的 `Layout` 从来只做 `min(视口/画面)` 的整幅放下，放大交给捏合，所以永远不会缺东西。现在横竖屏都回到整幅放下

### 新增

- **显示方式「适应 / 拉伸」**（工具栏第二个键，和指针模式一样记住选择）：适应=保持比例整幅放下（一定有黑边但画面完整）；拉伸=两轴各自铺满（零黑边，代价是宽高比被拉掉——横屏视口 2250×851 是 2.64:1，对 16:9 的桌面约拉宽 48%）。这就是 1.0 的 `SCALE_FIT` / `SCALE_STRETCH`
- 缩放改成**横竖轴分开存**（`scaleX` / `scaleY`）：拉伸模式下两轴不同，只记一个值的话「点哪儿」和「实际点到哪儿」会在一个轴上系统性偏移——1.0 的 `Layout` 注释里就是这条。触控映射、光标绘制、软跟随、触摸板位移、滚轮格距现在都按各自的轴算；光标本身按两轴均值画，不被拽变形

### 修改

- `setBaseScale(baseX, baseY)` 取代 `setBaseScale(base)`；`getZoom()` 返回纵向缩放（捏合算的是两轴共同的倍率变化，取哪个都一样），坐标映射改用新的 `getScaleX()` / `getScaleY()`
- 去掉 `onMeasure` 里那句上游的 `Log.v`——它每次测量都打一行，正是排查布局死循环时刷屏的那一条

### 验证

- Mi 10 Pro → 192.168.1.100（NLA）：
  - 横屏「适应」：`sessionScrollView = 2340×886`、`sessionView = 1512×851`（基数 0.788），整张桌面都在眼里——**任务栏和时间区可见、上下边缘没被裁**，左右各留黑边
  - 横屏「拉伸」：`sessionView = 2250×851`，画面铺满整屏、零黑边，任务栏仍在；按钮文案同步成「适应」
  - 拉伸模式下点屏幕 (1100, 400) → 画面里的光标正落在那一点（两轴映射自洽）
  - 显示方式与指针模式都记住了：切过的选择在会话内即时生效
- 未验证：双指捏合、双指滚动、双指轻点右键、三指轻点中键（`adb shell input` 注入不了多点触控，需要手测）；外接鼠标/触摸板手感

## v2.0.43 — 2026-10-31

按 1.0（`E:\phone\ToolKit` 的 `RdpSessionActivity`）把手势与缩放两套逻辑搬过来：**两种指针模式一键切**、缩放改成「适配基数 × 用户倍率」并让画面**跟着光标走**。

### 新增

- **两种指针模式**（[SessionView.java](freerdpcore/src/main/java/com/freerdp/freerdpcore/presentation/SessionView.java) 的单指状态机）：
  - **触控**：点哪儿打哪儿；单指滑动 = 远端滚轮（56 远端像素一格）并带松手甩动惯性；按住 500 ms 松手 = 右键；按住之后再移动超过半个 touchSlop = 拖拽
  - **鼠标**：整块画面当触摸板，相对位移推远端光标，带 1.0 那条 sqrt 加速曲线（慢 0.75× → 1 px/ms 1.5× → 4 px/ms 封顶 2.25×）；轻点 = 左键（落在光标处，不是手指处）；轻点后在双击超时内再按住 = **tap-and-a-half** 直接进拖拽（按住 180 ms 或移动半个 touchSlop 都算，不用先空拖一段）；按住 600 ms 松手 = 右键，这期间一动就取消
  - 两指轻点 = 右键（上游 `DoubleGestureDetector` 的 single tap），**三指轻点 = 中键**（检测器只认两指，这段自己判）
- **工具栏「鼠标/触控」切换键**（放在最左）：文字写的是「点下去会切到什么」，和横屏/全屏同一套读法；选择记在偏好里，下一场会话沿用；切换时先结掉当前按住的键，否则从鼠标切走时远端会以为左键一直没抬
- **光标软跟随**：放大状态下光标靠近视野边缘一圈（15% 或 64 dp）就把画面往那个方向推，而不是一越界就整屏跳到中心；捏合结束时如果光标整只跑出去了才直接居中

### 修改

- **缩放模型换成基数 × 倍率**：`setBaseScale()` 是「按视口铺满」（竖屏整幅放下、横屏按宽度铺满），`setUserZoom()` 是 1×~4× 的用户倍率，有效缩放 = 两者相乘。原来捏合改的是绝对缩放，转屏重算适配时把用户的放大倍数一起冲掉；现在转屏只动基数
- 捏合改调倍率，并保持**焦点下那个远端像素不动**（原来是「滚动 origin 与新焦点的差」，倍率被夹住时还会白挪一下）；双击只在**已经放大**时用来回到 1×——没放大时不拦双击，桌面图标、文件列表全靠双击打开
- 双指滚动：从「每 10 px 发一次、只有纵向」改成**横竖各攒 30 dp 一格**，一次跨过几格就发几条
- 滚轮事件改发在**指针当前位置**（原来固定在 `(0,0)`）：Windows 按指针所在窗口决定滚谁，发在左上角会滚到不该滚的那一块
- 横竖屏按钮的标签以**真实方向**为准（`onConfigurationChanged` 里同步）：自动旋转把屏幕转过去之后，按钮还停在旧文案上会让人以为没生效
- 单指拖动的归属：画面超出视口时（放大后）让给 `ScrollView2D` 本地平移，SessionView 收到 `ACTION_CANCEL` 就把按住的键结掉；鼠标模式下单指是推光标的，本地平移直接关掉

### 验证

- Mi 10 Pro → 192.168.1.100（NLA）：
  - 触控模式：在桌面空白处按住 900 ms 松手 → Windows 11 桌面右键菜单出现（查看/排序方式/刷新/新建/显示设置/个性化…），且光标箭头就画在菜单左上角；轻点一下菜单关闭；竖屏单指上滑 → 抖音信息流滚动（远端滚轮，不是本地挪画面）
  - 切「鼠标」后：同样一段滑动，页面不动而画面里的光标跟着走；在画面内点一下 → 点在**光标所在位置**（视频开始播放）而不是手指位置
  - 缩放：竖屏 `sessionScrollView = 1080×2129` → `sessionView = 1080×607`（基数 0.5625 整幅放下）；横屏 `2340×886` → `2250×1265`（基数 1.172 按宽铺满，零黑边）；横屏时标签自动是「竖屏」
  - 记忆：断开重开后仍是鼠标模式（按钮显示「触控」）
- 未验证：双指捏合、双指滚动、双指轻点右键、三指轻点中键（`adb shell input` 注入不了多点触控，需要手测）；外接鼠标/触摸板下的光标手感

## v2.0.38 — 2026-10-31

按反馈把光标**画进远端画面里**（界面上不再有任何悬浮指针），并修掉全屏/横屏切换后画面不放大的真正原因。

### 修改

- **光标合成进画面**：[SessionView.drawRemoteCursor()](freerdpcore/src/main/java/com/freerdp/freerdpcore/presentation/SessionView.java) 在 `onDraw()` 画完远端位图后，把服务端下发的光标位图按当前缩放画在画面内。FreeRDP 在 `libfreerdp/core/capabilities.c` 里把 `colorPointerFlag` 写死为 TRUE，服务端因此从不把光标烤进帧缓冲、也从不回传光标坐标——所以坐标改由我们自己记：`SessionInputManager` 里每个带坐标的输入事件（左/右/中键、拖动、悬停移动）都过一遍 `trackPointer()`，「我们把指针发到哪儿」就是光标在哪儿
- **光标最小按原始尺寸画**（`cursorScale() = max(zoom, 1.0)`）：竖屏 0.56× 下跟着缩只剩 18 px，在手机上等于找不到；现在只有画面被放大时光标才跟着放大
- **服务端要隐藏光标时退回默认箭头**而不是真的不画：触屏客户端一旦看不见指针就没法继续操作。同时本地这层从构造起就是 `PointerIcon.TYPE_NULL`，外接鼠标也不会出现「系统箭头 + 画面内箭头」两个光标
- 悬浮触摸指针层（`TouchPointerView`）在会话页永久 `GONE`，不再向它转发光标位图；`SessionInputManager.toggleTouchPointer()` 随「鼠标」按钮一起删掉

### 修复

- **全屏/横屏切换后画面不放大**：真正的原因不是缩放没重算，而是 `onWindowInsetsChanged()` 只读设置项、不看会话内的 `forceFullscreen`——系统栏藏了，状态栏那 194 px 和导航栏那 90 px 留白却还留着，可视区被压成 2250×851。现在全屏时一并清掉留白与圆角安全区，并重发一次 `requestApplyInsets`
- **布局死循环**：`scrollView` 的 `onLayoutChange → applyFitZoom → setZoom → requestLayout → onLayoutChange` 自己转圈（`requestLayout` 过的 View 即使边框没变也会回调监听器），实测 4 s 内 `onMeasure` 1524 次。改为算出来的缩放和当前值相等就不下发
- **信息面板的桌面尺寸**取的是 `sessionView` 的宽高，那是缩放后的显示尺寸；改取远端位图

### 验证

- Mi 10 Pro（1080×2340）→ 192.168.1.100（NLA）：
  - 竖屏适配缩放 0.5625、画面铺满宽度；点一下后白色描边箭头出现在画面内点击处，等 4 s（服务端已发 `OnPointerSetNull`）箭头仍在
  - 全屏后根布局留白归零；再切横屏，`sessionScrollView = 0,0-2340,1080`、`sessionView = 0,0-2340,1316`（缩放 1.219，横向零黑边），标签同步为「竖屏 / 退出全屏」
  - 空闲 3 s 的 `SessionView.onMeasure` 日志 0 条（修复前 4 s 1524 条）；信息面板显示 `桌面：1920×1080 · 32 位色`、`渲染引擎：3.28.0`
- 未验证：外接鼠标下的实际手感（代码上本地光标已置 `TYPE_NULL`）、其他机型的刘海/挖孔留白组合

## v2.0.32 — 2026-10-31

修复连接簿连不上**公网设备、IPv6 与域名（DDNS）**的问题。

### 修复

- **裸 IPv6 地址被判非法**：FreeRDP 的 `/v:` 解析器只在 `[地址]` 形式下认 IPv6，裸的 `240e:…` 会被当成 `host:port` 拆开并返回“非法参数”，连接根本不会发起。`LibFreeRDP` 在拼参数前自动补方括号
- **域名/DDNS 解析到不可达地址**：真机日志里是 `connect to 240e:…:3389 failed with error: No route to host`——域名解析出的地址（或其中的第一条）不可达/已过期，FreeRDP 直接失败。启动器现在先把域名解析成全部地址，逐个 TCP 试连（1.5 s/条、最多 4 条），把**实测可达的地址**交给 FreeRDP；全部失败才交回域名让它自己报错
- **信息面板**：连接后显示「域名 → 实际 IP」，方便核对 DDNS 当前指向

### 验证

- Mi 10 Pro → 自己的 dynv6 DDNS 域名（本机没有把域名写进文档；仅 AAAA 记录，公网 IPv6 形如 `240e:…`）：`OnConnectionSuccess`，NLA 通过、会话建立；约 10 秒后服务端返回 `ERRINFO_RPC_INITIATED_DISCONNECT`（管理员在另一会话踢线，服务器侧策略），不是客户端问题
- 局域网 IPv4 路径不受影响（启动器同样会预解析并传可达地址）

## v2.0.30 — 2026-10-31

会话界面按使用反馈收敛：工具栏换成半透明文字按钮，补上信息面板、横竖屏与全屏；触摸鼠标去掉一堆按钮；画面默认 1920×1080 并整幅适配。

### 新增

- **会话工具栏**（[floating_toolbar.xml](freerdpcore/src/main/res/layout/floating_toolbar.xml)）：左上角一排半透明按钮——鼠标 / 键盘 / 信息 / 横屏·竖屏 / 全屏·退出全屏 / 断开，压在当前画面上、不占布局高度
- **信息面板**：「信息」按钮弹出连接信息——主机:端口、桌面尺寸与色深、引擎版本（`LibFreeRDP.getVersion()`）、客户端版本、启动前测得的 TCP 握手耗时（启动器在后台线程连一次测往返，再交给会话页）
- **横屏 / 全屏**：横屏按钮在 `SENSOR_LANDSCAPE` 与 `UNSPECIFIED` 之间切换并更新标签；全屏按钮切换沉浸式（隐藏状态栏/导航栏），退出时恢复

### 修改

- **触摸鼠标精简**：上游的触摸指针有滚动、复位、软键盘、扩展键盘等一圈按钮，实际用起来点不准；现在只留三个——拖动+左键的蓝色圆钮、右键、关闭
- **默认桌面 1920×1080**：启动器不再沿用旧偏好里的尺寸，只有显式勾过「自适应手机分辨率」才按手机来
- **画面整幅适配**：会话页按「能整幅放下」的比例缩放显示（只缩不放，不改请求分辨率），横竖屏切换后重新计算，竖屏下也能一眼看全桌面
- 工具栏的顶部留白避免与状态栏重复叠加（根布局已按状态栏留过 padding）

### 验证

- Mi 10 Pro → 192.168.1.100（NLA）：工具栏显示正常；信息面板显示 `1920×1080 · 16 位色`、引擎 `3.28.0`、客户端 `2.0.24`、TCP 握手 4 ms；横屏切到 2340×1080 且桌面整幅铺满、标签变「竖屏」；全屏后状态栏消失、标签变「退出全屏」；切回竖屏后重新适配；触摸鼠标只剩左键/右键/关闭；「断开」直接退回局域网页
- 未验证：长时间会话、不同机型上的横竖屏切换动画、全屏下的软键盘布局

## v2.0.20 — 2026-10-31

远程桌面改为**直接使用 FreeRDP 官方 Android 客户端**：会话界面、触摸/鼠标/键盘的控制逻辑全部换成上游实现，替换 v2.0.12 里自绘会话界面 + 自写 JNI 输入层的做法（那版的点击/鼠标在真机上手感不对）。

### 新增

- **`:freerdpcore` 库模块**：从 FreeRDP 3.28.0 的 `client/Android/Studio/freeRDPCore` 裁剪而来（MPL-2.0）——保留 `SessionActivity` / `SessionView` / `SessionInputManager` / `TouchPointerView` / `ScrollView2D` / `LibFreeRDP` 与会话相关资源，删掉 aFreeRDP 基于 Room + SQLCipher 的主页、连接簿、历史与快捷方式
- **原生层换成上游 `android_*.c`**：`android_freerdp.c` / `android_event.c` / `android_jni_callback.c` / `android_cliprdr.c` / `android_disp.c` / `android_rail.c`，编成 `libfreerdp-android.so`；设置不再逐项 set，而是把 `BookmarkBase` 翻成 FreeRDP 的 argv（`/v: /port: /u: /d: /p: /size: /bpp: /rfx /gfx /sound /audio-mode: /disp /clipboard /cert:ignore`）交给 `freerdp_parse_arguments`
- **会话入口变薄**：`RdpSessionActivity` 只做规范化地址、取/弹凭据、构造书签并启动 FreeRDP 的 `SessionActivity`；连接簿与局域网页的 Intent 契约不变
- **应用 Application 换成 `GlobalApp`**：会话生命周期、连接事件与剪贴板监听都由 FreeRDP 客户端管理
- 声音、剪贴板、GFX、触摸指针、软键盘/扩展键、硬件键盘映射都是 FreeRDP 自带实现

### 修改

- FreeRDP 构建补 `CHANNEL_RAIL_CLIENT=ON`（`android_rail.c` 依赖 rail 客户端入口）
- 静态链接时 `android_freerdp.c` 与 WinPR 各有一个 `JNI_OnLoad`/`JNI_OnUnload`：链接放行重复定义，并在我们的 `JNI_OnLoad` 里同时给 WinPR 的 `jniVm` 赋值（`winpr_jni_attach_thread` 依赖它）
- `LibFreeRDP` 静态块只加载 `libfreerdp-android.so`（上游 APK 里 freerdp3/client3/winpr3 是分开的共享库，本工程全部静态合并）
- 会话屏不再有自绘状态行、连接详情对话框、分辨率/画质档位与自定义手势；这些由 FreeRDP 客户端自己的界面承担（分辨率与画质默认取 `RdpSettings` 的 1920×1080 / 省流档）

### 修复

- **鼠标/点击不生效**：改用 FreeRDP 的 `SessionInputManager` 后，点任务栏开始按钮能弹出 Windows 搜索菜单，硬件键盘能在搜索框里打出文字
- **启动崩溃**：旧写法把 `CredentialStore` 放在字段初始化里（Activity 尚未 attach，`getApplicationContext()` 为 null），移到 `onCreate`

### 移除

- `RdpNative.java`、`rdpcore/`（自写 C shim 与本项目自定义的输入逻辑）、`RdpKeys`、旧的会话布局/连接详情/手势资源，以及 `local.properties` 里的 `rdpNative.enabled` 开关（原生层随构建一起编）

### 验证

- Mi 10 Pro → 192.168.1.100（Windows 11，强制 NLA）：连通、画面颜色正确；**点开始按钮弹出 Windows 搜索菜单**；**硬件键盘在 Windows 搜索框打出 `not`**；RDPSND 通道建立、OpenSL ES 后端加载；退出会话无崩溃
- APK 约 40 MB，`libfreerdp-android.so` arm64 9.3 MB / armv7 7.0 MB（未压缩、已剥符号）
- **未验证**：声音是否真的出声、长按右键与双指滚动、剪贴板双向、旋转/自适应、长时间稳定性
- 测试环境提醒：这台手机上有一个会注入 `KEYCODE_BACK` 的辅助/自动化应用（logcat：`Input event injection from pid 941`），会在几秒后把 FreeRDP 会话关掉，看起来像「自己退出」——与客户端无关

### 文档

- README 的「远程桌面」小节按「FreeRDP 官方 Android 客户端」重写；目录结构、依赖表、致谢、体积口径与构建要求同步；`third_party/README.md` 更新 rail 通道、MPL-2.0 许可与产物名

## v2.0.12 — 2026-10-31

内置远程桌面换引擎：从 Rust + IronRDP 换成 **C + FreeRDP 3.28.0**（静态链入 OpenSSL 3.5.9）。1.x 两次在「安卓上交叉编译 FreeRDP」上卡死，这次把那条路走通了；Java 侧的 JNI 契约保持不变，连接簿、凭据、手势与界面一行没改。

### 修改

- **协议层换成 FreeRDP**：NLA/CredSSP、位图、指针、RDPSND 全部由 FreeRDP 实现；`rdpcore/` 从 Rust crate 改成 C shim（`session.c` + `jni_bridge.c`），导出的 22 个 native 方法、状态码、帧号与输入语义与 1.x 完全一致
- **构建链路重做**：`third_party/build-android.sh` 交叉编译 OpenSSL + FreeRDP 成每个 ABI 一套静态库（源码按固定版本拉取、SHA-256 校验），AGP 的 `externalNativeBuild` 把 `rdpcore/` 编成 `librdpcore.so` 直接进 APK；删掉 1.x 的 `RdpCoreBuildTask`（手写 Gradle 任务调 cargo、再拷 `.so` 进 jniLibs）
- **链接 API 回到 24**：声音改用 FreeRDP 自带的 OpenSL ES 后端，不再需要 `libaaudio.so`（API 26），Android 7.x 也能加载内置通道
- **输入状态自维护**：按住的鼠标键位、按下的扫描码由原生层记录，`releaseAll` 逐个抬起；拖动中的移动事件带按键位；侧键走扩展鼠标事件；Java 的 0xE000 扩展位在原生层换算成 FreeRDP 的 `KBDEXT=0x0100`
- **滚轮**：±120 编进 flags 低 9 位，负数按 9 位补码（与 xfreerdp 一致），横向用 `PTR_FLAGS_HWHEEL`
- **连接详情**：内核一行显示 `rdpcore 2.0 / FreeRDP 3.28.0`；音频包计数不再显示——FreeRDP 的 OpenSL ES 后端不暴露包计数，`nativeAudioPackets()` 返回 -1，界面只显示「声音：已开/未开」
- **体积**：`librdpcore.so` arm64 约 9.3 MB、armv7 约 7.0 MB（1.x 为 13.9 / 9.65 MB，均未压缩、已剥符号）

### 修复

- **触控模式下点「连接详情」必崩**（1.x 遗留）：模式行把 `R.string.rdp_mode_touch` 写成了 `RdpSettings.MODE_TOUCH`（常量 0），`getString(0)` 抛 `Resources$NotFoundException`
- **画面红绿对调、蓝色为 0**：FreeRDP 的像素格式名按内存字节序，`PIXEL_FORMAT_XRGB32` 是 [X,R,G,B]；gdi 改用 `PIXEL_FORMAT_BGRA32`（内存 B,G,R,A，小端下正好是 Android `ARGB_8888`）

### 原生层（踩坑记录，都已固化进 `third_party/README.md`）

- **OpenSSL 为什么这次编得动**：1.x 卡在缺 GNU make 与 perl 模块。NDK 自带 `make.exe`；Git for Windows 的精简 perl 缺 `Locale::Maketext` / `ExtUtils::MakeMaker` / `Pod::*`，补进 `third_party/perl-lib`（纯 Perl，来自 Perl 官方仓库与 CPAN）；OpenSSL 用 `no-shared no-asm`。MbedTLS 走不通——FreeRDP 顶层把 OpenSSL 写成 `REQUIRED`
- **客户端上下文必须内嵌 `rdpClientContext`**：只给 `rdpContext` 的大小会让 `freerdp_client_context_new` 返回 NULL（「无法创建 RDP 上下文」）
- **安卓没有 `HOME`**：`freerdp_settings_new` 要 HomePath，拿不到就建不出上下文（日志只有 `KNOWN_PATH_HOME is nullptr`）；start 时把 `HOME` 指到应用私有目录
- **NLA 算 MD4 失败**：OpenSSL 3 把 MD4/RC4 挪进 legacy provider，静态库不带可加载的 provider 模块；按官方 Android 构建开 `WITH_INTERNAL_MD4/RC4/MD5`，用 WinPR 自带实现
- **32 位 ARM 的 `__thread`**：FreeRDP 默认开 ThinLTO，LTO 把 TLS 降低推迟到链接期，`-femulated-tls` 不生效、回到 bionic 到 API 30 才导出的 `__tls_get_addr`；关掉 LTO 后每个对象在编译期定死 TLS 模型
- **工具链细节**：CMake 3.31.5 + Ninja 用 `sdkmanager` 装；CMake 是 Windows 原生程序，脚本里 MSYS 路径要 `cygpath` 转回 `D:/` 形式；OpenSSL 只支持干净的 out-of-source 构建，且传给 `Configure` 的源码路径要用 Windows 形式

### 验证

- `third_party/build-android.sh`（arm64-v8a + armeabi-v7a）通过；`assembleDebug` 通过，APK 内含两个 ABI 的 `librdpcore.so`，依赖只有系统库（`libc/libm/libdl/libz/liblog/libOpenSLES`）
- Mi 10 Pro → Windows 11 虚机（强制 NLA）：**已连通**，桌面画面颜色正确；触控点击在远端生效（点开了页面上的视频）；连接详情可打开（往返 12 ms、输入统计实时增长、内核显示 FreeRDP 版本）；日志确认 `RDPSND 通道已连接` 与 `Loaded opensles backend for rdpsnd`；按返回键结束会话无崩溃，日志是干净的 `ERRCONNECT_CONNECT_CANCELLED`
- **未复验**：应用内键盘打字的端到端、锁存 Win+E / Ctrl+Alt+Del、开始菜单的精确点击、长时间稳定性、横屏/全屏切换（界面逻辑与 1.x 相同，但引擎已换，需下轮补测）

### 移除

- `rdpcore/` 的 Rust crate（`Cargo.toml` / `src/lib.rs` / `third_party/ironrdp-rdpsnd-native`）、Gradle 里的 cargo 交叉编译任务、`local.properties` 的 `rdpNative.cargo` / `rdpNative.lib`
- 1.x 的 MbedTLS 试验线（构建脚本里用 `WITH_MBEDTLS=OFF` 明确关掉）
- 2.0 工程未复制 `Python/`（113MB，全工程没有引用）与 `_verify/`（真机截图）

### 文档

- `third_party/README.md`：依赖版本、来源与校验、构建步骤、许可、七条构建决定的来龙去脉
- README 的「远程桌面」小节重写引擎与构建部分，「依赖」「致谢」「目录结构」「入库范围」同步更新；真机验证结论如实区分「已验证」与「未复验」

## v1.43 — 2026-10-31

连接详情参照 Windows 远程桌面的「连接质量」对话框重做，补上往返时间、活动 ID 等真能用来排查的信息。

### 新增

- **连接质量**：顶部一句评价，按预检时 TCP 握手的往返时间分档——≤30 ms 很好、≤80 ms 好、≤150 ms 一般，再差是较差。帧率不参与评价：远端画面不动时帧率本来就是 0，拿它判网络会误伤
- **时间戳 / 活动 ID**：每次连接记下开始时刻与 8 位十六进制活动 ID，方便对上「是哪一次连接」
- **[客户端详情]** 应用版本（名称 + versionCode）、本地系统（Android 版本 · 机型）、内核（rdpcore / IronRDP）
- **[网络详情]** 传输协议（如实写 TCP：IronRDP 还没有 RDP 的 UDP 传输）、往返时间（TCP 握手）、本次接收速率与累计流量、画面帧率与像素流量、声音状态与音频包数
- **[远程计算机详情]** 会话类型、网关（未使用）、远程计算机（按域名连时显示 `域名 → 解析结果`，IPv6 自动加方括号）、状态、尺寸、码率、指针模式、输入统计
- 正文改成**可选中复制**的 TextView（像 mstsc 一样能整段拷走），底部提示「长按文本可以复制」

### 原生层

- `pre_connect` 顺手记下 TCP 握手的耗时，新增 `nativeConnectMs()` 暴露给界面；解析失败时保留 0

### 验证

- `assembleDebug` / `lintDebug` 通过（0 error）
- 设备当时被其它应用占用，未截到新对话框的图；打开「连接详情」即可核对三段信息与质量评价

### 文档

- **隐私清理**：README/CHANGELOG 里两台设备的真实 MAC、`192.168.5.x` 内网地址、机器名（PC-HOME / PC-OFFICE / NAS-HOME）、示例照片与片名统一换成占位值；界面示例地址改为 `192.168.1.100`
- README 增加目录与「开源项目与致谢」：逐项列出用到的开源项目、许可与感谢（IronRDP / FreeRDP / jcifs-ng / SMBJ / usb-serial-for-android / Compose / Coil / Media3 / cpal / tokio / rustls 等），并注明参考的 Windows 客户端与交互来源
- 修正构建说明：产物名改为 `工具箱-<版本>.apk`，补上版本号自动递增规则与当前工具链版本（Gradle 9.8.0 / AGP 9.4.0）

## v1.38 — 2026-10-31

鼠标模式对齐笔记本触摸板的操作手感。

### 修改

- **指针加速**：原来灵敏度是恒定倍率，慢速点小目标太滑、快速横跨桌面又太慢。现在按手指
  移动速度取增益：慢速 0.75×、1 px/ms 约 1.5×、4 px/ms 封顶 2.25×，再乘灵敏度滑块。
  曲线用 sqrt 而不是线性，慢速段不迟钝、快速段也不会一下甩飞
- **双指滚动支持横向**：两指平移时纵向按 y 攒格、横向按 x 攒格，分别发纵向/横向滚轮
  （鼠标滚轮事件原本只有纵向；原生层补了 `is_vertical=false` 这条通道）。连接详情的
  「输入」会标出「滚轮 … 纵/横」
- **三指轻点 = 中键**：和触摸板常见的三指映射一致；双指轻点仍是右键
- 鼠标模式的手势提示同步更新

### 实现要点

- 加速需要知道「每毫秒走了多远」：按下时记时刻，每次 MOVE 用事件时间差算速度；
  速度夹在 0–4 px/ms，避免合成事件时间戳相同时除零或增益爆掉
- 双指手势在判定阶段会记录**最多同时几根手指**，抬手时据此区分两指右键 / 三指中键；
  一旦判成滚动或缩放就不再算轻点

### 验证（Mi 10 Pro → 192.168.1.100）

- 鼠标模式滑动：光标跟随正常、无崩溃；慢速小位移更稳，快速滑动位移明显变大
- 触控模式单指滑动：连接详情显示 `最近 滚轮 -120 @960,544 纵`，纵向滚轮通路未受影响
- 横向滚动与三指轻点 adb 合成不了多点触控，需要手上验证

## v1.37 — 2026-10-31

远程桌面默认 1920×1080，顺手做了一轮性能与代码清理。

### 修改

- **默认桌面尺寸改成 1920×1080**：没存过尺寸时不再按手机算，而是按绝大多数 Windows 桌面的
  原生尺寸请求；「匹配本机」移到分辨率列表最后，仍可选。「自适应手机当前分辨率」默认关闭，
  需要 1:1 像素时再勾
- **状态行按需重建**：它原来每帧（33 ms）都拼一次字符串、还跨 JNI 取错误文本。现在按
  「状态 / 尺寸 / 模式 / 锁存键 / 连接地址」的签名判断，内容不变就每 500 ms 才刷一次；
  连接正常时不再每帧取错误字节数组
- **带宽采样先看时钟再跨 JNI**：每秒才采一次，之前每帧都要先拿帧数/字节数/TrafficStats
- **RDPSND 协商日志只记第一次**：服务端换音频格式时会重复协商，之前每轮都打一条
- 分辨率预设顺序调整为 1920×1080 → 1600×900 → 1366×768 → 1280×800 → 1024×768 → 匹配本机

### 验证（Mi 10 Pro → 192.168.1.100）

- 进入会话状态行显示 `1920×1080`，显示对话框里 1920×1080 默认选中、自适应未勾
- 状态行内容随状态/尺寸/锁存键即时更新；断线时仍会弹「连接已断开」并展开工具条

## v1.36 — 2026-10-31

声音真的出来了：上游的 cpal 后端漏了 `stream.play()`。

### 修复

- **收到音频包但没声音**：`ironrdp-rdpsnd-native` 建好 cpal 输出流后没有调用 `play()`。
  cpal 的文档写得很清楚——不是所有平台建完流就自动跑（Android 的 AAudio 要显式
  `request_start()`），不调的话音频回调永远不触发，数据全被丢进黑洞：表现正好是
  「连接详情里音频包数在涨，但一点声音都没有」。补上 `stream.play()` 并加一行
  `RDPSND 输出流已启动` 日志
- 这一条与 v1.30 的 RDPDR 是两件事：RDPDR 让服务端愿意发音频，`play()` 让本机真的放出来；
  缺任何一个都是"没声音"

### 验证（Mi 10 Pro → 192.168.1.100）

- 远端播放 `C:\Windows\Media\Alarm01.wav`：logcat 出现「输出流已启动」，同期
  `dumpsys media.audio_flinger` 里本应用出现 **active** 音轨（轮询 8 次都测到），音频包
  持续到达——从服务端到扬声器的整条链路打通

## v1.35 — 2026-10-31

IPv6 地址、版本号自动递增 + APK 带版本名、声音排查工具。

### 新增

- **远程桌面支持 IPv6**：连接簿的地址栏可以填 `fe80::1` 或 `[fe80::1]`（方括号会去掉、
  统一存裸地址，同一台机器不会因为写法不同存成两条）。底层把 `host:port` 拼成 `[v6]:port`
  再去解析/连接；导出的 `.rdp` 与复制的 `freerdp://` 链接也按 IPv6 规则加方括号
- **版本号自动递增**：根目录 `version.properties`（`versionCode` / `versionName`），
  每次 `assemble*/install*/bundle*` 之前自动 +1，lint 之类不触发；APK 输出文件名改成
  `工具箱-<版本>.apk`
- **声音诊断**：连接详情里的「声音」一行现在带**已收音频包数**。远端在放声音时这个数字应该
  一直涨；不涨 = 服务端没发；涨了却听不见 = 手机这边音量/蓝牙路由的问题

### 修复

- **声音（继续修）**：上一版加了 RDPDR 通道后，服务端已经会把音频推过来——现场日志能看到
  `RDPSND wave packet format_no=0 bytes=32768` 连续到达，cpal 后端也没有报错。
  另外把音频格式下标的处理做成"取不到就退回第一种"，避免个别服务端用服务端格式表的下标
  导致整段音频被丢掉
- 说明：Android **没有"使用扬声器"权限**，播放音频不需要任何权限，所以之前不是权限问题；
  真正的原因就是协议层少挂了 RDPDR 通道（v1.30 已修）

### 验证（Mi 10 Pro → 192.168.1.100）

- 在远端播放 `C:\Windows\Media\Alarm01.wav`：logcat 连续出现 wave 包，连接详情显示
  「已收 7 个音频包」，`dumpsys` 里能看到本应用的音频客户端；加 RDPDR 之前一条都没有
- IPv4 连接照常（IPv6 本机没有可用的目标可测，仅验证了地址解析与拼接路径）

## v1.30 — 2026-10-30

远程桌面：声音真的响了（根因是少了 RDPDR 通道），外加按你提的几条改界面。

### 修复

- **没声音的根因：RDPDR 通道没挂上**。IronRDP 的 RDPSND 客户端要等 Windows 先发
  "Server Audio Formats" 才会开始协商，而 Windows 只在**同一个会话里有 RDPDR（设备重定向）通道**时
  才会给客户端建"远程音频"设备并启动 rdpsnd —— IronRDP 上游把这个坑记在 #1791 里。
  本工程之前只开了 RDPSND 没开 RDPDR，所以通道挂上了、服务端一个音频包都不发。
  现在给 ironrdp 加 `client-rdpdr` 特性（客户端会挂一个 Noop 的 RDPDR 通道，不重定向任何设备）。
  验证：logcat 里能看到完整的 `AudioFormat(V8, ...)` → `Training` 协商，现在每轮连接都会打一行
  「RDPSND 协商开始」；加 `client-rdpdr` 之前这条永远不出现。用 mstsc 连同一台机器有声音，
  说明服务端侧的音频链路本来就没问题
- 连接详情里那行「声音：已开」现在是真的开了通道；若还没有声音，按提示查电脑侧

### 修改

- **去掉鼠标模式底部那一排按键**（左/右/中键与滚轮）：触摸板手势已经覆盖，白占一条屏幕
- **手势提示自动淡出**：切换到模式时显示 4 秒后淡出，不再常驻压在桌面上
- **工具条把手挂到右边缘**：收起时是一枚**半透明**的小把手贴在右边中间，点一下才展开顶部工具条；
  展开后点「收起」又缩回去。全屏看桌面时几乎不挡内容
- **新增「自适应手机当前分辨率」勾选**（显示对话框）：勾上后按手机**当前方向**的实际像素请求桌面
  （竖屏 1080×2340、横屏 2340×1080），1:1 不缩放，字最锐利；旋转屏幕后自动按新尺寸重连。
  打开时下面的分辨率预设会灰掉

### 验证（Mi 10 Pro → 192.168.1.100）

- 声音：每轮连接都能看到 `ironrdp_rdpsnd::client` 的 AudioFormat + Training（之前一条都没有）
- 自适应：勾选并应用后重连为 1080×2340；按「横屏」自动重连为 2340×1080；再切回 1920×1080 预设正常
- 收起后的把手在右边缘、半透明；点开恢复整条工具条；底部按键行已消失；提示 4 秒后淡出

## v1.29 — 2026-10-30

远程桌面有声音了：RDPSND 音频通道 + Android AAudio 播放。

### 新增

- **声音**：连接时声明 RDPSND（PCM 44.1 kHz 立体声 16 位），服务端把音频从静态虚拟通道推过来，
  由 IronRDP 的 cpal 后端交给 Android 的 AAudio 播放。不需要额外权限，跟随系统媒体音量
- 连接详情里多一行「声音：已开（PCM 44.1kHz）」，没声音时先看这一行：
  - 「已开」= 本机这边没问题，去查电脑那边有没有音频输出设备、是不是静音（这台测试虚机就是这样：
    Hyper-V 虚拟机上没有音频设备，任务栏连音量图标都没有，任何客户端连过去都不会有声音）
  - 「未开」= 本机或系统版本不支持（AAudio 要 Android 8.0+）

### 实现上的取舍

- **默认的 opus 编解码关掉了**：`ironrdp-rdpsnd-native` 上游默认打开 opus，会拉 `libopus_sys` 用 CMake
  从源码编 libopus——安卓交叉编译要额外装 CMake，每条 ABI 还要多编一个 C 库。本工程只需要 PCM，
  所以把 0.7.0 的源码复制进 `rdpcore/third_party/` 并把默认特性留空（`[patch.crates-io]` 指过去），
  `src/` 与原版逐字节相同，只改了 Cargo.toml
- **ndk-context 由 nativeStart 初始化**：cpal 的 Android 后端要从 `ndk_context` 取 JavaVM 与
  Application Context（用来查 AudioManager/AudioTrack 的缓冲大小）。JNI 侧手工取 `GetJavaVM`(槽 219)
  与 `NewGlobalRef`(槽 21)，Context 必须换成全局引用，只在第一次初始化
- **链接按 API 26**：`libaaudio.so` 到 API 26 才有桩库。代价是这个 .so 在 Android 7.x 上加载失败，
  `RdpNative.isAvailable()` 会收起内置远程桌面入口，应用其它部分不受影响

### 顺带

- 依赖树里的 `tracing` 日志接进 logcat（tag `rdpcore`）：cpal/RDPSND 的 error/warn 之前全被丢掉，
  「没声音」时完全无从查起；原生侧也顺手记一行会话参数（尺寸 / 色深 / 有损 / 声音）

### 验证

- 客户端播放通路：临时用 cpal 直接放 2 秒 440 Hz 正弦，`dumpsys media.audio_flinger` 里出现
  `7 Tracks of which 1 are active` 且客户端是 UID 10300（本应用）——AAudio 通路成立，自检代码已删
- 通道协商：logcat `RDPSND 通道：true`、`声音=true`
- **服务端没得测**：这台 Hyper-V 虚机没有音频输出设备（任务栏无音量图标），抖音视频在播也一个音频包都没发过来；
  换一台有声卡的电脑连即可，客户端侧不用再改

## v1.28 — 2026-10-30

远程桌面：能主动断开、断线看得见、码率分三档。

### 新增

- **断开连接**：浮动工具栏最右边加了「断开」，确认后结束会话并退回上一页；会话已经断掉时同一个按钮变成「重连」，点一下原地重建连接（不用退出再进）
- **码率与画质三档**（显示对话框）：省流 = 16 位色 + RemoteFX 有损编码；均衡 = 32 位色 + 有损；清晰 = 32 位色 + 关闭 RemoteFX（文字最锐利、位图流量最大）。色深与编解码都是握手时协商的，改档位会自动重连一次
- **连接详情里的真实网速**：`TrafficStats` 每秒采一次本应用接收的字节，和原来"按未压缩像素算"的画面流量分开显示——调档位时能直接看到省了多少

### 修复

- **电脑重新登录后画面停住、也不提示断开**：远端没了但 TCP 没关（休眠、重启、换会话）时，客户端一个字节都不发，就永远发现不了。现在空闲 20 秒补一条"原地鼠标移动"当心跳：对端不在了会立刻撞上 RST，顺带还给公网 NAT 续命
- **断开原因被丢掉**：`run()` 结束那一刻队列里还排着一条 `Terminated`，`select!` 先选中"future 完成"，收尾时直接退出——界面上只剩一句没有下文的「已结束」。现在收尾会把队列里的事件读完，状态行会写「连接中断：…」
- 状态从「已连通」掉下来时：工具栏自动展开、弹一次「连接已断开，可以点『重连』再试一次」，按钮同时变成「重连」；`已结束` 这个状态词改成 `已断开`

### 验证（Mi 10 Pro / Android 13 → 192.168.1.100）

- 断开：点「断开」→ 确认 → 回到局域网页
- 断线：开飞行模式 9 秒，状态行变成「已断开 · … · 连接中断：… Software caused connection abort (os error 103)」，按钮变「重连」；关掉飞行模式点「重连」，几秒后回到「已连通」
- 档位：从省流切到清晰会重连一次，画面正常；网络计数在工作（静态桌面 0 B/s，拖动窗口后不再为 0）

## v1.27 — 2026-10-30

把「还能加什么」里剩下的三件大事补齐了：压缩包在线浏览/解压、相册增量备份、跨主机复制；外加传输持久化与外挂字幕。

### 新增

- **压缩包在线浏览与解压**（`SmbZip` + `SmbZipActivity`）：不下载整包就能看内容。`ZipFile` 要本地文件、`ZipInputStream` 只能从头顺扫（列一个 4 GB 的包＝先拉 4 GB），所以自己读了包尾的**中央目录 + EOCD**（含 ZIP64 定位器），条目表几十 KB 就够；点开某条目时才用本地头算出的数据偏移去取那一段，STORED/DEFLATE 都支持。页面里目录折叠成一层、可勾选多条、菜单里「解压全部到这里」；条目名支持 UTF-8 与 GBK（Windows 打的包不带 UTF-8 标志时按 UTF-8 严格解失败就退回 GBK），带 `..`/绝对路径的条目拒绝解出（Zip Slip）
- **相册一键增量备份**（`SmbBackup`，浏览页菜单「备份相册到这里」）：查 MediaStore 的照片+视频，按**文件名**判断共享上有没有，只传缺的那些；确认框先报「共 N 项 / 约 X」再动手。用名字而不是大小当判据是刻意的：中途断掉留下的半截文件如果按大小判定「已存在」就永远补不回来
- **跨主机复制/移动**（`SmbCrossTransfer`）：选目标目录时菜单里多了「换一台机器作目标…」，选完照常翻目录，横幅会补一句「源在 x.x.x.x 上：字节要经手机中转」。同机仍走服务端复制（字节不出服务器），跨机才是「读一条随机句柄 → 写对面」的流式中转，目录递归、逐级建目录、重名避让，**移动只在整棵子树搬完后才删源**
- **传输任务持久化**（`UnfinishedStore`）：下载进度按 1.5 秒节流写进 SharedPreferences（主机/远端路径/落点句柄/已写字节）。「已下载与最近打开」页顶部新增「未完成」区，进程被杀之后照样能「继续」从断点接上，或「放弃」把半截文件删掉
- **外挂字幕自动加载**：播视频时找同目录同主名的 `.srt/.ass/.ssa/.vtt`，整份落到字节缓存后挂成 `MediaItem.SubtitleConfiguration`（第一条默认打开，标签取 `电影.zh.srt` 里的 `zh`）。Media3 不会自己去猜同名字幕文件，必须显式挂
- **已下载页的条目操作**：长按一条 → 分享 / 复制路径 / 删除（删除同时清 MediaStore 记录）
- 搜索结果的动作里加「打开所在目录」——搜完最常见的下一步就是回到那里动手

### 修改

- **相册备份省掉每文件两次 ContentResolver 查询**：`SmbClient.upload` 拆出一个「名字与大小已知」的重载，备份直接把 MediaStore 那一趟查到的 `DISPLAY_NAME`/`SIZE` 递进去。八千张照片就是少一万六千次进程间调用，而一次查询比传完一张小照片还慢
- **解压不再为同一个目录反复问「在不在」**：`extractTo` 记住这次已经建过的目录，几百个文件同在一个目录下时从几百次 `exists` 降到一次
- **未完成存档改成内存优先**：进度每 1.5 秒回写一次，原来每次都要把整份 JSON 重新解析一遍（两条并发下载一秒就两次）；现在表常驻内存，只在读写时序列化
- 解压页与备份页的任务体只递 `applicationContext`：闭包被进程级队列持有，递页面会把这一页留到任务结束
- 解压页去掉一条「选中数刚好等于条目数就当作全选」的推断：按名字过滤本来就能覆盖这种情况
- 预览页的「下载到本机 / 用其他应用打开」改走 `TransferHub` 队列：原来是挂在页面 `worker` 上的裸任务，退出页面就断、通知栏也没进度，现在和浏览页共用一条队列（也因此才有了持久化与续传）
- 传输任务表移出浏览页快照（`SmbBrowseScreen(state, jobs, …)`）：进度回调一秒推十次，不该带着整页列表重组
- 缩略图缓存淘汰加 30 秒节流：每写一张图就 stat 一遍几千个缓存文件，越用越慢
- 换机器时归还上一条连接（引用计数只加不减会攒下关不掉的 TCP）
- `SmbKind` 增加 `ARCHIVE`（zip），点压缩包直接进解压页而不是条目菜单

### 修复

- **解压报「incorrect header check」**：`InflaterInputStream(单参构造)` 在设备上按 **zlib 头**解，而 ZIP 条目是 raw deflate。改成自己给 `Inflater(true)`
- **续传写到一半报 `write failed: EBADF`**：`FileOutputStream(pfd.fileDescriptor)` 不持有 `ParcelFileDescriptor` 本身，GC 一收掉它就关了底层 fd——真机上下到 1 GB 时断在这里。现在用 `PfdStream` 把 pfd 变成流的成员
- **续传「失败：NetworkOnMainThreadException」**：恢复任务时在主线程登录了那台机器（新建连接要解析主机）。改成在工作线程把连接与落点都备齐，再回主线程入队
- 解压/写入失败会在共享上留下一个顶着正常名字的半截文件（下次按名字判断「已经有了」就被跳过）：`writeFile` 现在写不完就把自己的文件删掉
- 解压页读失败时漏还一份连接引用

### 真机验证（Mi 10 Pro / Android 13，PC-HOME = 192.168.1.100，NAS-HOME = 192.168.1.10）

- 压缩包：把 412 B 的 test.zip（`hello.txt`、`中文.txt`、`docs/readme.md`）上传到 `Users/Public` → 解压页正确列出 3 项（含 UTF-8 中文名与折叠出的 `docs/`）→「解压全部到这里」建出 `docs/` 并解出三个文件，`docs/readme.md` 在应用内预览内容与编码（UTF-8）都对
- 传输持久化：2.6 GB 的电影下到 655 MB 时 `force-stop` 进程 → 重开「已下载」页出现「未完成 655.0 MB / 2.6 GB」→ 点「继续」→ 从 1.0 GB 接着走并最终完成（2.6 GB，落进公共下载目录），完成后用长按菜单删除
- 跨主机复制：在 NAS 上长按一个 2.7 MB 的 csv →「复制到…」→「换一台机器作目标」→ 选 PC-HOME → 进 `Users/Public` → 确认，传输面板显示「已完成 · 4.2 MB/s」且文件出现在目标目录；横幅期间显示「源在 192.168.1.10 上」
- 相册备份：菜单进得去，系统权限框正常弹出，确认后报「手机里共有 8115 项照片/视频，约 38.7 GB」（本次到此为止，没有真的往共享里灌 38 GB）
- 预览页改动没打断播放：同一部电影仍能起播并显示画面；字幕框在无内嵌轨时如实提示
- 上传路径重构后重跑一遍：`上传到此处…` 选一个 18 B 的文本 → 传输面板「已完成」，文件出现在目标目录（测完删除，`Users/Public` 回到原来的 9 项）
- 测试产生的文件（共享里的 zip/解出项/复制出的 csv/上传的文本、手机里的 test.zip 与那 2.6 GB 下载）已全部删除，NAS 与 `Users/Public` 回到原来的条目数

## v1.26 — 2026-10-30

把「还能怎么改」这一串里除了跨主机复制以外的都做了：传输搬进前台服务、断点续传、视频续播、类型筛选、递归搜索、PDF 缩略图、播放器增强、已下载页、SMB3 加密开关。

### 新增

- **传输搬进前台服务**：任务表从 Activity 移到进程级 `TransferHub`，`SmbTransferService` 负责让进程活着并在通知栏显示进度（带「全部取消」）。**页面退出传输照跑**——原来 `onDestroy` 会把任务全取消、连接还回去，下 1.7 GB 的电影期间不能离开那个页面。连接的寿命由 `TransferHub` 按机器「钉」住（`SmbHub` 引用计数），Activity 退出只减少自己那一份
- **断点续传**：失败/取消的下载会出现「重试」，从已写入的字节继续（`download(path, offset, …)` 走随机读句柄定位）。起点只认任务记录的 `doneBytes`，提交前核对总字节数
- **视频续播**：按「主机 + 路径」记位置，起播跳过去；看完或只看开头几秒不记
- **类型筛选**：工具条胶囊按 图片/视频/音频/PDF/文本 过滤，目录始终显示
- **递归搜索**：「搜索子目录…」，逐目录扫（SMB 没有搜索接口），三道闸 400 目录 / 20000 条目 / 50 命中，结果标题写明扫了多少目录；命中项可预览/下载/复制路径
- **PDF 首页缩略图**（`PdfRenderer`，复用整份文件的字节缓存）；视频与 PDF 缩略图**延迟 250 ms 再取**，划走的条目自动取消
- **播放器增强**：字幕轨选择（`currentTracks` + `TrackSelectionOverride`）、倍速 0.5×~2×（翻页重建也记得住）、横屏/竖屏切换
- **已下载与最近打开**（主页独立入口）：列公共 `下载/工具箱` 的文件与最近预览记录，点一条直接打开/继续看
- **SMB3 传输加密开关**（`jcifs.client.encryptionEnabled`），放在兼容设置里，默认关

### 修复

- **续传起点被顶成文件末尾**：任务完成时无条件把 `doneBytes` 顶到 `total`，取消时也执行了——于是重试从 EOF 开始读，瞬间「完成」而文件只有一半（真机上就丢了 700 MB）。现在只有真正 DONE 才顶。另外续传起点改成任务自己记录的字节数：MediaStore 的 pending 项查不到 SIZE，`resumableBytes()` 在公共下载目录这条路上恒为 0
- 「已取消」的任务没有「重试」按钮（按钮只加在了「失败」分支）
- `TrackSelectionOverride` 在 `androidx.media3.common`（不是 `exoplayer.trackselection`）

### 真机验证（Mi 10 Pro / Android 13，PC-HOME = 192.168.1.100）

- 后台传输：1.2 GB 的电影开始下载后**按 Home 回桌面**，文件仍然下完（1,285,045,596 字节，与远端一致）；`SmbTransferService` 为前台服务
- 续传：下载到 259 MB 取消 → 点「重试」→ 3 秒后进度显示 669 MB（从断点继续，不是从 0），最终文件字节数与远端一致
- 搜索：`Videos` 里搜 `mkv` → 「找到 1 项（扫了 1 个目录）」
- 主页入口与「已下载」页正常打开；类型筛选胶囊显示「全部」
- 未验到：跨主机复制（未实现）、提交前的字节数核对只在正常路径上跑过、SMB3 加密开关只保证能编译（需要支持加密的服务端才能验）

### 未做

- **跨主机复制/移动**：需要「选目录模式里换机器」+ 两台机器之间流式中转 + 目录递归，是这一串里唯一没落地的；现在复制/移动仍限同一台机器内

## v1.25 — 2026-10-30

去掉「用其他应用连接」：远程桌面只有一个入口，就是内置的「远程连接」。

### 移除

- 局域网设备菜单与连接簿菜单里的「用其他应用连接」都不再出现，不再把地址交给系统里注册 `freerdp://` 的客户端
- 连接簿顶部「系统里有 N 个外部客户端」的状态行、找不到客户端时的复制链接提示，以及只服务于这条路的文案一并删掉
- 清单里的 `<queries>`（`freerdp://`、`application/rdp` 包可见性）随之删除——工程里已没有 `queryIntentActivities`
- `RdpLauncher` 里拉起外部客户端的代码（`launch` / `availableClients` / `clientCount` / `describe`）删掉，只留链接构造、端口探测与 `.rdp` 导出

保留：连接簿的「复制链接」「导出 .rdp 文件」，两条都不带密码；导出的文件拿到电脑上仍可双击用 mstsc 连。

## v1.24 — 2026-10-30

修好一直没被真机验证过的预览页（两个潜伏 bug），按局域网流给内部播放器调参，并补上复制/剪切与「直接交给外部播放器」。

### 修复

- **预览页永远停在「正在准备…」**：`link` 是普通字段，而连接成功的那个回调只把 `ui` 赋成了与初始值相等的对象——`StateFlow` 去重之后不触发重组，页面永远读到 `link == null`。图片、视频、PDF、文本四种预览全都受影响。改成 `linkFlow: MutableStateFlow<SmbLink?>`，由它驱动重组
- **ExoPlayer 在 IO 线程上创建 → `Player is accessed on the wrong thread`**：播放器必须建在「创建它的那个 Looper」上。现在只有「起回环代理」这一步在网络线程，播放器的创建、配置、prepare 与 release 全部回到主线程（释放原来也丢在后台线程，同样是错的）

### 优化（内部播放器）

- 回环代理每块 64 KB → 256 KB：播放器读得比拷贝快，64 KB 一次等于每块等一个 SMB 往返
- `DefaultLoadControl` 短缓冲（10s/60s，起播 1s）+ 大 back-buffer；`SeekParameters.CLOSEST_SYNC`（精确 seek 会为对齐关键帧把中间数据全读一遍，网络上是「拖一下卡几秒」）；控制器 ±10 秒；音频焦点、拔耳机自动暂停；播放时保持亮屏

### 新增

- **复制到… / 移动到…**：条目菜单与多选工具条都有，目标用「只列文件夹」的选择器选。复制走 jcifs 的 `copyTo`（SMB2 `FSCTL_SRV_COPYCHUNK`，服务端搬字节，目录递归），移动优先 `renameTo`，跨共享改名失败才退回「复制 + 删除」。重名一律避让，绝不覆盖
- **用外部播放器直接播放**：浏览页条目菜单（音视频才有）直接起 `SmbStreamService` 把流交给 VLC/小米视频等，不用先进预览页；与预览页共用 `SmbExternalPlay`

### 真机验证（Mi 10 Pro / Android 13，PC-HOME = 192.168.1.100）

- 内部播放器：`测试视频.1080P` 起播正常，拖进度条跳到 `1:02:06 / 1:20:21` 并渲染出画面，控制器 ±10 秒与设置按钮可用
- 图片预览：`Pictures/IMG_0001.jpg` 正常显示（这条路径第一次被真机走到）
- 复制：`Videos` 里 1.2 GB 的电影复制到 `Public`，约 6 秒完成（≈200 MB/s，是服务端在搬），目标文件大小一致
- 移动：把那份副本移回 `Videos`，源目录少一项、目标多一项；随后用多选删除清掉了测试副本
- 外部播放：`小米视频` 通过回环地址直接播放该电影（片头「美亚长城传媒」），页面退出后 `SmbStreamService` 仍在前台运行

## v1.23 — 2026-10-30

下载落点从应用私有目录换到公共「下载/工具箱」。

### 修改

- 新增 `DownloadStore`：Android 10+ 通过 MediaStore 写到 `/sdcard/Download/工具箱/`——文件管理器、相册、播放器都能直接看到，卸载应用也不会带走；Android 9 及以下没有这条免权限通道（要 WRITE_EXTERNAL_STORAGE），退回原来的私有目录
- 为什么换：`Android/data/<包名>/files/Download/` 从 Android 11 起对其它应用（含系统文件管理器与相册）隐藏，用户下完一部电影根本找不到，只能靠「用其他应用打开 / 分享」递出去——这是真机上反馈最多的一条
- MediaStore 的写入分两步：先以 `IS_PENDING=1` 占位写字节、`commit()` 时清标志，避免相册在传输途中看到半截文件；失败或取消走 `discard()` 删掉占位项，不留 0 字节空壳
- 重名避让规则不变（`照片.jpg` → `照片 (2).jpg`）：先按 `RELATIVE_PATH` 查一遍现有名字再插入；插入后回读 `DISPLAY_NAME` 作为最终名字——MediaStore 会按 MIME 给个别扩展名补后缀（`.ini` 映射成 text/plain，于是磁盘上叫 `.ini.txt`），提示与打开都以它回写的为准
- 完成提示带上落点：`已保存 xxx（大小） · 下载/工具箱`
- 浏览页的「下载到本机」与预览页的取到本地走同一套落点；`uniqueFileIn` 与旧的文件路径分支移除

### 真机验证（Mi 10 Pro / Android 13）

从 PC-HOME 的 `Users/Public` 下载一个文件：`/sdcard/Download/工具箱/` 出现该文件，MediaStore 记录 `relative_path=Download/工具箱/`、`is_pending=0`、`_size` 正确 —— 说明别的应用能立刻看到它。测试文件与索引记录已清理。

## v1.22 — 2026-10-30

修一条会让人以为「文件丢了」的提示：选目录上传模式下列表只剩文件夹，状态行却把被过滤掉的文件说成「带隐藏属性」。

### 修复

- `电影` 目录里 256 个电影文件在「选目录上传到此处」时按设计被过滤（该模式只列文件夹），但状态行把它算进了「另有 256 项带隐藏属性」——用户据此去查隐藏项开关，实际根本不是那回事。现在三种「少了东西」的原因分开计数、分开措辞：选目录模式 → 「共 1 个文件夹 · 另有 256 个文件未显示：选目录模式只列文件夹」；关键字 → 「未过滤时 N 项」；隐藏属性 → 「另有 N 项带隐藏属性」
- 选目录模式的横幅也标明「（只列出文件夹）」，不用靠猜

### 修改

- **带隐藏属性的条目默认显示**（`SmbSettings.showHidden()` 默认改为 true）。这个属性由服务端说了算，各家 NAS/Samba 的实现差别很大（有的会把整个目录都标上 hidden，或从权限位映射过来），「默认藏起来」的代价可能是整页文件消失，而用户第一反应是文件没了。菜单里的「显示隐藏项」仍然可以关掉，带隐藏属性的条目本身也会标「隐藏」

### 真机验证（同一台 NAS 192.168.1.10 / 共享/电影）

- 选目录模式：`共 1 个文件夹 · 另有 256 个文件未显示：选目录模式只列文件夹 · 剩余 1.3 TB`
- 退出该模式、用普通浏览进同一目录：`共 257 项` —— 文件一个不少

## v1.21 — 2026-10-30

长按多选，以及「播不了怎么办」的几条出路：换系统播放器、把流交给外部播放器。

### 新增

- **长按多选**：长按条目进入，点其它条目累加/取消；工具条换成「已选 N 项 / 全选 / 下载 / 删除 / 取消」。批量下载每文件一条任务进同一队列；批量删除带确认。只选一项时多出 `⋯`，给回完整条目菜单——目录没有右侧 ⋮，否则改名/属性这些动作没入口。全选只作用于当前过滤后可见的条目；返回键先退出多选
- **换系统播放器**：视频 ExoPlayer 出错时，可直接切到 `VideoView`（厂商 `MediaPlayer`）用同一回环流再试。意义在于它用设备自带的解码器——ExoPlayer 依赖厂商裁过的 MediaCodec 集，RMVB/WMV、带特殊音轨的 MKV 常常在它那里直接报错
- **用其他播放器打开**（VLC / MX / 系统播放器）：通过新增的 `SmbStreamService`（前台服务，`foregroundServiceType=dataSync`）持有回环代理，把 `http://127.0.0.1:端口/…` 交给外部播放器。URL 经 `ResultReceiver` 回传——只需要「拿到地址就发 Intent」，为这一件事写 AIDL 不划算；URL 本身也是异步才准备好（先登录 SMB、再起监听端口）
- 前台服务带「停止」动作的通知，空闲 5 分钟（`SmbHttpProxy.lastAccess` 判断）自动收摊；`FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` 权限与 `dataSync` 类型已按 targetSdk 34 的要求声明

### 修复

- **列表会卡成空白且再也不动**：一次目录读取返回时，如果用户已经换到别的目录，代码只把结果写进缓存就 return，**没把 `loading` 交还**——之后每次 `load()` 都被「已有列表在读」挡死，界面停在「空列表 + 没有状态行」，连返回上一级都失效（真机踩到）。现在这种情况下会交还 loading，并替当前目录补一次读取
- 缓存缺失时 `publish()` 不再清屏：保留现有内容并补一次读取，比让界面变白强

### 真机验证（Mi 10 Pro / Android 13，连 Win11 虚机）

- 多选：长按 Public → 「已选 1 项」；点 XXX → 「已选 2 项」且 `⋯` 消失；只有目录时点「下载」被拦下（提示选中的里面没有文件）；「删除」弹出「删除选中的 N 项？…」，确认后共享上的文件真的消失、列表随即刷新
- 串流：切到音频页 → 菜单「用其他播放器打开」→ 系统选择器列出「媒体查看器 / QQ音乐 / 下载管理」，`dumpsys activity services` 显示 `SmbStreamService isForeground=true`、通知带 actions=1，页面退出后服务仍在
- 未验到：`VideoView` 兜底那条路需要一份 ExoPlayer 解不了的文件，手头没有，只保证编译与逻辑

## v1.20 — 2026-10-30

共享文件的传输提速：同一个 64 MB 文件，**下载 15.0 → 49.6 MB/s（3.3×）、上传 8.3 → 17.4 MB/s（2.1×）**。

### 修改

- `jcifs.client.rcv_buf_size` / `snd_buf_size` 从库默认的 64 KB（`SmbConstants.DEFAULT_RCV_BUF_SIZE`）抬到 1 MB。这两个值不只是流缓冲，同时就是套接字缓冲＝TCP 窗口，所以它才是这次的主因
- 「允许 SMB1」时缓冲自动退回 64 KB：SMB1 且服务端没有 `LARGE_READX` 时读大小按这个缓冲算，1 MB 的 READ 老设备会直接拒——用户主动开兼容就说明目标是那类设备，宁可慢也别连不上
- `SmbClient.download` 改用已打开的资源问大小，少一次 resolve + 一次多余的往返
- SMBJ 通道的随机读块从 256 KB 抬到 1 MB（只影响预览/边播，不影响下载上传——那两条永远是 jcifs 路径）
- 传输面板显示**速率**（`已完成 · 49.6 MB/s`），否则这类优化没有任何可观测的验收标准

### 试过但行不通（留给以后的人，别再走一遍）

- 给 `openInputStream(1 MB)` 传显式缓冲：服务端回 `STATUS_INVALID_PARAMETER`，下载直接失败。SMB2 下 `SmbFileInputStream.init` 取的是 `th.getReceiveBufferSize()`＝服务端协商的 MaxReadSize，显式值会绕过这个上限
- 给 `openOutputStream(false, 1 MB)` 传显式缓冲：同样被拒——这个会话的单次 WRITE 上限就是 64 KB。上传要再快只能做并行写（把 ContentResolver 的顺序流切块、多句柄按偏移写），成本明显高于这次收益，未做

### 真机验证（Mi 10 Pro / Android 13，Wi-Fi 6 1200 Mbps、RSSI -52，连 Win11 虚机 192.168.1.100）

64 MB 随机数据文件，上传后立刻回读下载，面板读数：下载 15.0 → 49.6 MB/s，上传 8.3 → 17.4 MB/s。带宽上限约 60~80 MB/s（Wi-Fi 6 实测效率），下载已接近；上传仍受 64 KB/请求限制。

## v1.19 — 2026-10-30

按 KDE Connect / Syncthing / LocalSend 的交互习惯补三件事：常用文件夹收藏、多任务传输队列、从手机出发的上传。三项都走现有 SMB 通道，没有引入第二个协议栈。

### 新增

- **常用文件夹收藏**（`SmbSettings.Bookmark`，JSON 持久化）：任意目录菜单「收藏当前目录」一点加入（名字取路径末段），「文件夹收藏…」里点一条直达、点「管理」改名或删除。无地址入口的目标选择框把收藏排在最近主机之前——这就是 KDE Connect 的「先选设备、再选那台设备上被标出来的目录」
- **传输队列**：`TransferJob` 各带独立进度、取消标记与失败原因，默认 2 条并发（1 条让批量上传纯串行；8 条在千兆链路上互相抢 SMB credit，实测总吞吐反而下降）。界面是可折叠面板：「传输中 N 项 / 最近传输」+ 每条的进度条与取消 + 「清除」已完成项，最多显示 4 行、其余折成「还有 N 项」
- **从手机出发的上传**：菜单「从手机选文件上传…」先多选文件，再进「选目录模式」（列表只留目录，顶部横幅「已选 N 个文件 · 上传到这里」），确认后整批入队
- `SmbSendActivity`：接住别的应用「分享」过来的 `ACTION_SEND`/`SEND_MULTIPLE`（`*/*`），把 URI 转给共享页的同一条选目录流程。它是纯跳板——不建连接、不落盘，用 `Theme.NoDisplay`，转发后立刻 finish
- 目标选择框与收藏跳转的标题改用最近主机记住的机器名，而不是 IP

### 修改

- 传输不再与浏览互斥：原来只要有一条传输在跑，点条目、再选文件都会被拦住；现在可以边传边翻目录、看预览、继续加任务
- 单条全局进度条改成任务面板（`BrowseSnapshot.jobs` 取代 `progressLabel/progressDone/progressTotal`）
- 写操作完成后作废目标目录的缓存，并且只有在用户还停在该目录时才立刻重取
- 进度文案从「正在下载 X」改成「下载 X」——它现在是任务标签，完成态旁边写「正在…」很别扭
- 进度推界面按 100 ms 节流：原来每 64 KB 推一次，两条并发时整页快照一秒重建几十遍

### 真机验证（Mi 10 Pro / Android 13，连 Windows 虚机 192.168.1.100）

- 收藏：在 `Users` 里收藏 → 菜单立刻变成「取消收藏当前目录」；退到共享列表后从收藏一点直达，标题显示机器名
- 队列：连点三次「下载到本机」，面板里三条任务各自独立、完成态保留，点「清除」移除
- 分享上传：用真实 `ACTION_SEND` 拉起 `SmbSendActivity`（带一个 `content://` 流）→ 目标框里收藏排第一 → 进选目录模式（列表只剩 3 个目录、文件被滤掉、横幅显示「已选 1 个文件」）→ 在 `Users/Public` 确认后上传「已完成」，列表随即刷新；同一路径在 `Users` 根上传则如实报「权限不足」（普通账号写不了 `C:\Users`）


## v1.18 — 2026-10-29

共享浏览的交互整轮打磨：面包屑、目录缓存、就地过滤、明面上的操作入口。

### 新增

- **面包屑改成可点的圆角块**（`共享列表 › Users › …`，当前级高亮），任意一级直接跳；长按当前级复制这一层的 UNC。原来是一行原文路径 + 弹对话框选层级，跳一次要点两下
- **目录列表缓存**：按访问顺序保留最近 14 层，新鲜期 60 秒。命中就立刻上屏、一个请求都不发；过期先显示缓存再后台静默重取（只有一条细线表示在刷新，失败也不清空屏上内容）。翻共享全是「进三层、退回去看别的、再进来」这种回头路，不缓存每次返回都要转一遍进度条
- **每个目录记住滚动位置**，退回来接着看；换机器或重连时缓存与滚动位置整份清掉（缓存的键只有路径，不同机器上的同名路径会撞上）
- **工具条**把过滤 / 排序 / 视图 / 刷新从 overflow 菜单提到明面上；排序胶囊直接显示当前方式，不用开菜单才知道自己按什么排的
- **过滤改成就地展开的输入框**，展开即抢焦点并弹键盘，边打字边筛；收起时自动清掉关键字——留着的话条目会被看不见的条件筛掉，用户只觉得「文件怎么少了」
- 文件条目右侧加 **⋮ 按钮**（与长按同一个菜单），并在列表下方给一行操作提示：手机上没人知道条目可以长按
- 状态行区分两种「少掉了」：`另有 N 项带隐藏属性，可在菜单里显示` 与 `未过滤时 N 项`
- 目录条目改用和文件角标同尺寸的块画 ▸，列表左边缘不再一格深一格浅

### 修改

- 返回键（v1.17 之前那轮加的逐级退回）现在**是瞬时的**：走缓存，不再闪「正在读取…」
- `BrowseSnapshot` 增加 `refreshing` / `filter` / `searchOpen` / `sortLabel` / `hint` / `scrollIndex`；界面参数收进 `SmbBrowseActions`，十几个 lambda 用位置传已经读不动了
- 移除 `smb_up`、`smb_breadcrumb_title`（面包屑块与返回键取代了「上一级」按钮和层级对话框），新增 `smb_view_grid`、`smb_view_list`、`smb_retry`、`smb_action_more`、`smb_filter_clear`、`smb_hint_actions`、`smb_hidden_dropped_format`

### 修复

- `LazyListState` / `LazyGridState` 的构造参数按 1.12.1 的真实签名写（`firstVisibleItemScrollOffset`；网格只有 `firstVisibleItemIndex` 可传），并用 `key(dir)` 包住列表体——否则换目录时 `remember` 不重建，滚动位置不会复位
- 隐藏项与关键字过滤共用一句「未过滤时 N 项」，会让人去清明明没填的搜索框；改为分别计数
- 补回被误删的 v1.5 条目标题，并把与另一会话重号的 v1.6 改到 v1.18：CHANGELOG 的版本序列恢复成 v1.18 → v1.4 连续递减

### 真机验证（Mi 10 Pro / Android 13，连 Windows 虚机的 `Users` 共享）

面包屑、工具条、网格视图、⋮ 与提示行均正常；进 `Users` 后按返回，紧随其后的 dump 已经是上一层的完整列表（无加载闪烁）；切换「显示隐藏项」时 `共 2 项 · 另有 2 项带隐藏属性` 立刻变 `共 4 项`，全程没有第二次网络请求。与并行落地的 RDP 那一串改动（v1.7~v1.17）合并后 `assembleDebug` 与 `lintDebug` 仍为 0 error。

## v1.17 — 2026-10-29

触控模式改成 Win11 触摸屏的手感，并修好了拖动。

### 修改

**触控模式**（原来的"按下去就按下左键、点哪儿点哪儿"是触控板的做法，不是触摸屏的）：

| 手势 | 动作 |
| --- | --- |
| 轻点 | 左键（抬手时才按下再抬起） |
| 双击 | 双击 |
| 单指滑动 | 滚动，内容跟着手指走，松手带甩动惯性 |
| 按住 500 ms | 弹出方块提示；松手 = 右键 |
| 按住后拖动 | 拖拽（长按到位才按下左键） |
| 双指张合 | 缩放 |

- 按下去**不再立刻**按左键：不这样"想滑一下页面"会变成框选/拖拽。代价是点击在抬手时发出（触摸屏本来就是这样）
- 长按给一个白色圆角方块（画在光标层，不占帧），和 Windows 的"按住"提示同一个意思
- 触控模式**不再画鼠标指针**：手指点哪儿就在哪儿生效，屏幕上不该多一个箭头
- 轻点容差与滚动阈值同宽（1.5 倍 touchSlop），手抖几下仍然算点击

### 修复

- **拖拽在远端一直是断的**：移动事件只带了 `PTRFLAGS_MOVE`，Windows 把指针事件的按键位当成当前状态，第一下移动就把左键当成松开了——窗口、图标、框选全都拖不动（之前只有日志能证明"按下了"，画面上什么也没发生）。原生层现在给拖动中的移动事件补上按住的键位，和 FreeRDP 的做法一致
- 这个修复同时让鼠标模式的拖动真正可用（v1.15 的 tap-and-a-half 之前只在日志里成立）

### 验证

- 触控模式：滑动任务管理器列表会滚动且带惯性；长按弹出右键菜单；按住后拖动把 Edge 图标和任务管理器窗口都拖到位；点开始按钮弹出开始菜单
- 鼠标模式：tap-and-a-half 之后光标带着左键移动（拖动窗口/框选都能画出选区）

## v1.16 — 2026-10-29

轻点之后的拖拽不再需要先"空走"一段距离。

### 修改

- **进入拖拽的门槛从位移改成"按住"**：轻点之后 300 ms 内再按下，原来是移动超过 3 倍 touchSlop（这块屏约 51 px）才按下左键，用户得先拖出去一段拖动才生效；现在按住 180 ms 就直接进入拖拽，之后手指一动就是真的在拖
- 移动这条入口保留，但阈值降到半个 touchSlop（约 8 px）——按住的同时手已经在动，也能立刻开始拖
- 两条入口收敛到同一个 `commitDrag()`：左键只按一次，不会重复发"按下"
- 双击不受影响：第二次按下在 180 ms 内抬起时计时器已被取消，仍然是两次真实的点击
- 双指落下会取消待定的拖拽计时，避免双指右键被吃掉一半

### 验证

- logcat：轻点（travel=0）→ 第二次按下 180 ms 进入拖拽 → 移动 260 px 后抬起，期间左键一直保持按下
- 远端画面：在虚拟机桌面空白处按住拖动，Windows 画出了框选矩形——确认左键在拖动期间是真的按住的
- 双击：两次轻点之间没有补出拖拽，行为与 v1.15 一致

## v1.15 — 2026-10-29

鼠标模式的手势对齐笔记本触摸板。

### 修改

按 Windows 精密触摸板的那一套重排，用手势本身区分意图，不再依赖"按住多久"：

| 手势 | 动作 |
| --- | --- |
| 单指移动 | 移动光标 |
| 单指轻点 | 左键 |
| **双指轻点** | **右键**（笔记本上的标准手势，本轮补回） |
| 双指上下滑 | 滚轮 |
| 双指张合 | 缩放 |
| **轻点后紧接着按住拖动** | **拖拽**（tap-and-a-half，笔记本上就是这么拖的） |
| 按住不动 600 ms 松手 | 右键（手机上"按住"比双指顺手，留作等价入口） |

几处实现上的取舍：

- **拖拽要等手指真的动起来才按下左键**。轻点之后的第二次按下只做"待定"，移动超过 3 倍 touchSlop 才真正按下；不这样的的话双击的第二下会被吃成"点击 + 拖拽"，双击就废了
- **tap-and-a-half 的时间窗取系统双击超时**（`ViewConfiguration.getDoubleTapTimeout()`，300 ms），超时就不再算"接着上一次点击"
- **长按之后又挪动手指 = 移动光标**，不再补右键（原来"长按后移动"是拖拽，现在拖拽归 tap-and-a-half，两条路合一）
- 双指手势的判定、滚动方向、残留手指的吞掉逻辑沿用 v1.14

### 与笔记本仍有差别的地方

- 笔记本有实体按键，可以"按住按键 + 移动"直接拖；这里是玻璃，所以拖拽只能走 tap-and-a-half
- 三指/四指手势（任务视图、切换桌面）没有做——那是操作系统级的功能，RDP 里没有对应报文
- 双指横向滑动应该发水平滚轮（`HORIZONTAL_WHEEL`），目前只做上下

### 验证

- logcat 确认链路：轻点 → `tap click`，随后 11 ms 内按下 → `armed gap=11` → 移动后 `drag start`（左键保持按下到抬手）
- 滚轮通路：点 4 次「滚 ↓」按钮，任务管理器列表确实往下滚
- **双指手势仍无法用 adb 合成**（`input` 只有单指针），需要手上试：双指轻点=右键、双指滑动=滚动、双指张合=缩放

## v1.14 — 2026-10-29

修「双指滑动=滚轮」根本不生效的问题。

### 修复

- **双指滚动被缩放手势整个吃掉了**：`handleTouch` 先问 `handlePinch`，而它在**任何**双指 MOVE 上都直接 `return true`。两根手指平行滑动时间距几乎不变、缩放量为 0，于是既没缩放也没滚动，屏幕上什么都不发生——那段滚轮代码从来没被执行过。这是 v1.8 加捏合缩放时埋下的，之后一直没回头验证双指滑动
- **改成先判意图、再决定走哪条**：把双指手势收进一个 `handleTwoFinger`，按下时记下两指间距与中点，移动后比较"间距变化"和"中点位移"，谁大听谁的——间距变大是缩放，中点移动是滚动；判定一次就锁定，不会中途改主意
- **滚动方向按"手指带着内容走"**（和手机一致）：两指上滑，内容跟着往上走 = 滚轮向下。原来那行把方向写反了，注释和代码对不上
- **一格的距离从 120 dp 收到 30 dp**：原来滑一大段才动一行，手感很黏
- **抬起一根手指后吞掉剩余手势**：双指抬起一根时若还剩手指在屏幕上，剩下的那根不再被当成新的单指手势——否则光标会从旧位置猛跳一下
- 顺手删掉「双指轻点=右键」：长按松手已经是右键，这条多余且和双指手势抢事件

### 验证与限制

- 滚轮通路本身已在真机上验过：点 4 次「滚 ↓」按钮，任务管理器的进程列表确实往下滚了（按钮和双指走的是同一个 `RdpNative.wheel`）
- **双指手势没法用 adb 合成**：`adb shell input` 只支持单指针（`tap/swipe/motionevent` 都是），要模拟两根手指得用 `sendevent` 直接写触摸节点，需要 root。所以这一条只能靠手上试——请重点试三件事：① 双指上下滑能滚动；② 双指张开/捏合仍是缩放；③ 单指操作没被影响

## v1.13 — 2026-10-29

放大之后光标跑出视野就"丢了鼠标"——触控板上没有实体指针可以回头看。现在视角会跟着光标走。

### 新增

- **放大状态下的软跟随**：光标靠近视野边缘一圈（约 15% 或 64 dp）时，画面朝那个方向推，把光标留在可见范围内。不是一越界就整屏跳到中心——跳变会让人失去方向感，留一圈的推法更像摄像机跟拍。只在放大时生效；没放大时整张桌面都在视野里
- **放大动作结束时把光标找回来**：双指捏合或双击放大之后，如果光标已经整只跑到视野外，视角直接居中到它上面。这是用户明确要的"视角聚焦到鼠标位置"；只在完全看不见时才动，光标还在边上就不打扰（捏合是"我要看这块区域"的动作，不该被推走）

### 说明

- 这两条只在**平移控件关闭**时才有机会触发——平移开着的时候单指拖动是挪画面、光标不动，自然不会丢

### 验证

- 2× 放大后把光标往右下拖 1300 多像素：视角跟着推，光标停右侧留白带里，没有跑到看不见的地方
- 双击放大：以双击点为焦点放大 2×，光标仍在视野内时视角不动
- `lintDebug` 0 errors

## v1.12 — 2026-10-29

修鼠标模式的"过于灵敏"：点一下之后随便挪挪光标就变成拖拽、双击被吃成"点击+拖拽"。手势按"长按之后的动作"重新分工。

### 修改

- **手势分工改成以长按为分界**：
  - **轻点**（按下后没怎么动就抬起）→ 左键
  - **长按**（按住不动 600 ms）后**直接松手** → 右键
  - **长按之后继续移动** → 拖拽（这时才按下左键），抬起时放开
- **按下长按时只标记、不立刻发键**：到位时给一次触觉反馈，告诉用户"此刻松手是右键、动就是拖"。这样两种"长按之后的动作"才能并存，而不会在按下的瞬间就替用户做决定
- **彻底去掉"轻点之后按住 = 拖拽"这条**：它太容易误触——点完一下紧接着去挪光标是极常见的动作，只要阈值一到就变成拖拽，连双击都会被吃成"点击+拖拽"（第二下按下就按住左键了）。现在移动光标这条路永远是干净的
- **轻点容差与拖拽阈值分开**：轻点容差取系统 `touchSlop` 的 1.5 倍（这台机器上是 25 px），超过就不算"点了一下"；长按判定也用它做"没动"的门槛
- 长按阈值 450 → 600 ms，接近 Android 默认的 `longPressTimeout`

### 实测（Mi 10 Pro，logcat + 截图双证）

- 轻点 → `tap click`，一次左键
- **双击任务管理器标题栏 → 窗口最大化**（两次点击都到位，第二下没有被吃成拖拽）
- 长按 1.2 s 不动松手 → `armed` → `right click on release`，右键
- 长按后移动 → `armed` → `drag start`，左键保持按下直到抬手
- **先点一下再立刻拖动光标 → `drag start` 计数为 0**（这正是之前误触的那条路）

### 测试方法上的一个坑

`adb shell input swipe` 的前几帧跨度远大于线性插值（9 秒 282 px 的滑动，头 150 ms 就走了 25 px），用它模拟不出"按住不动再移动"。要精确控制时序得用 `input motionevent DOWN` + `sleep` + `MOVE`——跨多个 `input` 进程的触摸流在 Android 上是**接续的**，不会被当成两次手势。

## v1.11 — 2026-10-29

两件事：鼠标模式的手势改成「轻点=左键 / 长按=右键 / 轻点后按住拖动=拖拽」，以及针对刷新慢（尤其是公网）做了能做的优化。

### 新增

- **鼠标模式手势重排**：
  - <b>轻点</b>（按下没怎么动就抬起）→ 左键
  - <b>长按</b>（按住不动约 450 ms）→ 右键，松手不再补左键，并给一次触觉反馈
  - <b>轻点后紧接着按住并移动</b>（400 ms 内）→ 拖拽，抬起时放开左键
  - "长按=右键"和"按住移动=拖拽"会打架——手指一按下去就同时满足两者。所以拖拽的入口单独挂在"刚轻点过"这个前提上，这也正是笔记本触摸板 tap-drag 的做法。单纯移动仍然只是推光标，不发任何按键
  - 双指轻点=右键、双指上下滑=滚轮保持不变
- **画质档（16 位色）**：「显示」里新增「清晰 32 位 / 流畅 16 位」。16 位色让服务端按 RGB565 发位图，**位图载荷直接减半**，是公网带宽紧张时最有效的一档；改完自动重连（色深是连接时协商的）
- **连接详情里的实时帧率与流量**：每秒采样原生层的帧数与像素字节数，显示「画面：N 帧/秒 · 约 X MB/秒（按未压缩像素算）」。刷新慢时先看这一行：帧率上不去而流量很小 → 是网络延迟；流量大帧率也低 → 是编码/带宽

### 修改

- **取帧管线去掉了一次整帧分配与逐像素转换**：原来每取一帧都新建一个 8 MB 的 `Vec`、逐像素 `push`（1080p 下每秒 30 次 × 200 万次循环），现在 alpha 在帧到达时就地补齐（一次线性遍历），取帧那条路只剩一次 `SetIntArrayRegion` 整块拷贝。素材还是同一份，但每秒省掉几百 MB 的分配与搬运

### 关于公网刷新慢（说清楚能做什么、做不了什么）

这个栈里能动的三档，按效果排序：

1. **桌面分辨率**——像素数是所有开销的乘数。公网建议 1280×720 或更低，「匹配本机」那种 1920×1080 是给局域网用的
2. **16 位色**——本次新增，位图载荷减半
3. **网络本身**——RDP 对延迟极其敏感（每次输入都要等一个往返），跨公网 40 ms 以上的 RTT 用起来就是"拖影"。直连公网也不安全，建议走 WireGuard / 内网穿透

做不到的（不是没做，是当前依赖不支持）：

- **位图缓存（Bitmap Cache）**：IronRDP 没实现，服务端无法复用已经发过的图块。mstsc 在 WAN 上省流量很大一部分来自这里
- **GFX 通道 / RemoteFX / H.264**：IronRDP 没有 GFX，只能走基础位图更新。这是公网观感与微软客户端差距的**最大来源**——同样一张壁纸，H.264 编码后只有基础位图的几分之一
- **体验标志（关壁纸、关动画）**：`ConfigBuilder` 把 `PerformanceFlags` 写死成默认值，没有开放；要用得 fork

### 验证

- 长按 1.2 s 不动 → 桌面弹出 Windows 右键菜单（查看 / 排序 / 刷新 / 新建）
- 轻点后立刻按住拖动 → logcat 打出 `drag start`，左键保持按下直到抬手
- 「显示」里切到「流畅 16 位」→ 自动重连，连接详情显示「画质：流畅 16 位」
- 连接详情新增「画面：0 帧/秒」——静止桌面本来就没有更新帧，这一行确实是活的
- `lintDebug` 0 errors

## v1.10 — 2026-10-29

界面上的叫法去掉了「实验」：**内置连接（实验）→ 远程连接**。同时把一路攒下来的提示语按现在的实现重写了一遍——其中几条已经和事实相反了。

### 修改

- **改名**：局域网设备菜单里的「内置远程桌面（实验）」、连接簿条目菜单里的「内置连接（实验）」，统一定为「**远程连接**」；连接簿页面标题也从「远程桌面」改成「远程连接」，与入口同名
- **把「交给外部客户端」那条路改成明确的备选**：原来叫「远程桌面」「连接」，现在叫「**用其他应用连接**」，并排到「远程连接」后面——不依赖手机里装没装别的客户端的那条才是主路
- **重写过期的说明**：
  - 连接簿页顶部的说明原来写着"本进程不实现 RDP 协议"，早就不是事实了。现在是：「远程连接」直接在本应用里连（NLA、画面、键鼠都在这里）；「用其他应用连接」才是把地址交给注册了 freerdp:// 的应用
  - 局域网里点「用其他应用连接」弹出的那段，原来写"内置 RDP 客户端在这个工程里做不到：FreeRDP 交叉编译要宿主机有 make 与 pkg-config……"——这是 FreeRDP 路线失败时留下的结论。现在改成：这台也能用「远程连接」直接操作，下面这些是交给外部客户端的方式
  - 「系统里没有注册 freerdp:// 的客户端」原来像是一条坏消息，现在补上"这只是『用其他应用连接』缺个去处，远程连接不受影响"
  - 端口测试成功后的提示、地址格式校验的提示、新建连接里的字段占位符都按现在的能力重写（地址栏明确写「IP 或域名」，并给了例子）
- **连接详情**：会话页原来的「输入统计」只是个 toast，而 MIUI 会把连续的第二条 toast 直接吞掉——查问题时最需要的恰恰是刚才那条写了什么。改成对话框，一次列出目标（域名会显示 `xxs → 192.168.1.100`）、状态、桌面尺寸、指针模式、输入统计与引擎版本。会话页藏了标题栏、溢出菜单点不到，入口挂在**状态行**上：展开工具栏后点那行字即可
- 鼠标模式的触摸板提示压短成一行（原来那句 32 个字会把工具栏顶宽）

### 验证

- 局域网设备菜单：查看共享文件 / **远程连接** / **用其他应用连接** / 设置登录账号 / ……，「远程连接」排第二
- 连接簿页：标题「远程连接」，说明与状态行都是新文案，列表条目照常显示「地址 · 账号 · 已存密码」
- 会话页：点状态行弹出「连接详情」，六行信息齐全（`rdpcore 0.1.0 / IronRDP`）
- `lintDebug` 0 errors

## v1.9 — 2026-10-29

两件事：鼠标模式补上"按住拖动"，以及远程桌面可以直接用域名连（不必先自己查 IP）。

### 新增

- **鼠标模式：按住即拖拽**。手指按下去不动约 350 ms，左键就按下（给一次触觉反馈），此后手指移动就是拖着走，抬起时放开左键；这一下不算点击。移动超过阈值会取消这个判定——那是在推光标而不是"按住"。触摸板本身没有"按住不放"这个状态，而拖窗口、拖滚动条、框选文件全都要它
- **远程桌面支持域名**：
  - 连接簿的地址栏本来就写着「IP 或主机名」，现在真的按这个走了：非字面量地址会先在本进程解析，状态行显示 `PC-HOME → 192.168.1.100:3389`，解析失败给一句"解析失败：检查域名拼写，或这台手机当前的 DNS 能不能查到它"
  - **解析优先取 IPv4**：同一个名字往往同时有 A 和 AAAA 记录，默认解析给的是 IPv6；家用局域网里 IPv4 才是稳的那条，而且凭据是按"扫描页看到的那个 IPv4"存的——解析成 IPv6 就成了另一台主机，密码要重输
  - **凭据按解析结果回退**：按域名连时，如果域名下没有存过账号，会去查解析出来的 IP 那一条，同一台机器不用因为写法不同再输一次密码
  - **域名解析页**长按域名多了一项「用远程桌面连这台」，直接把域名带进会话页

### 修复

- **会话结束后状态没结掉**：原生会话线程退出时只把输入通道置空，`state` 还停在 RUNNING，于是下次进会话页会指着一帧旧画面报"已连通"，看着像连上了。线程退出时统一改成"已结束"

### 实测（Mi 10 Pro → 192.168.1.100，Windows 11，强制 NLA）

- 鼠标模式：把光标推到任务管理器标题栏上 → 按住不动（有震动反馈）→ 手指移动，窗口跟着被拖到屏幕右下角；抬起后左键正常断开，没有留下"一直按着"的状态
- 域名：路由器的 DNS 能解析 `PC-HOME`（`ping PC-HOME` 得到 192.168.1.100）。连接簿里新建一条地址为 `PC-HOME` 的连接，点「内置连接」→ 状态行 `PC-HOME → 192.168.1.100:3389`，**没有弹凭据框**（直接复用了按 IP 存的那份），会话正常渲染
- `lintDebug` 0 errors

## v1.8 — 2026-10-29

会话页的控件层按微软 Windows App（`com.microsoft.rdc.androidx`）的做法重做：桌面铺满整屏，控件浮在画面上、可收起；补上横屏与全屏按钮，并加了双指缩放/平移。

### 参照方式

微软的客户端就装在这台手机上。没有反编译它的代码——做的是**观察运行中的界面**：把它连到同一台 192.168.1.100，用 `uiautomator dump` 把会话内每个控件的文字、可点区域和状态导出来，再对照自己的实现。这样拿到的是交互模型（哪些控件、放在哪儿、什么时候出现），不是可直接照抄的代码或资源，也不涉及绕过任何保护。

观察到的关键几条：

- **控件浮在画面上，不占布局高度**。它的会话顶栏是一枚圆角胶囊，只有三个图标（平移控件 / 会话切换器 / 键盘）；点开会话切换器才铺开整条命令栏（主页 / 触摸屏指针 / 鼠标指针 / 设备 / 应用 / 完成），下面是带实时预览缩略图的会话卡片
- **指针模式是两枚并排的图标**：触摸屏指针（点哪儿到哪儿）、鼠标指针（触摸板），与我这边的触控/鼠标两种模式是同一个模型
- **有一个独立的「平移控件」开关**：开着时单指拖动是挪画面而不是点击——放大之后没有这个开关，就只能在"想点"和"想挪"之间二选一
- **证书警告**：连陌生主机时它会弹「无法验证证书 / 名字不匹配」，并给一个「不再要求连接这台电脑」的勾选

### 新增

- **可收起的浮动工具栏**：默认收起成一个「☰」，点开才是完整一排（收起 / 鼠标·触控 / 键盘 / 平移 / 横屏 / 全屏 / 功能键 / 显示），状态行只在展开时显示；**出错时自动展开**，否则收起状态下看不到失败原因
- **横屏 / 竖屏按钮**：一按切到强制横屏（`SENSOR_LANDSCAPE`，两个方向都认），再按回竖屏
- **全屏按钮**：隐藏状态栏与导航栏（immersive sticky），整块屏幕给桌面；同时把系统的标题栏也藏掉
- **双指缩放 + 平移控件 + 双击放大**：捏合最多 4×，焦点下的那个远端像素保持不动（否则每次捏合画面都往中心滑）；平移量夹在"画面仍铺满视图"的范围内；平移模式下双击在 1× 与 2× 之间切换
- 鼠标模式的按键行（左/右/中/滚轮）也改成浮在底部，不再占布局高度

### 修复

- **功能键面板在横屏下被截掉一半**：6 行按键放不下，外面套一层 ScrollView
- 会话页原来还留着系统标题栏，白占一条；这一页要的就是整屏桌面，进去就隐藏 ActionBar

### 实测（Mi 10 Pro → 192.168.1.100，Windows 11，强制 NLA）

- 竖屏进入：桌面铺满，只有一枚 ☰；点开后整排按钮浮在画面上
- 横屏：一按立即转，画面按高度等比铺满，按钮跟着重排
- 全屏：状态栏/导航栏消失，桌面顶到屏幕四边；按钮变成「退出全屏」
- 平移 + 双击：桌面放大到 2×，点哪儿放哪儿；单指拖动画面跟手，拖到边界停住不露黑边；再双击回到 1×
- `lintDebug` 0 errors

### 仍然缺的（与微软客户端对照）

- **证书校验只在文档里提了**：我们连陌生主机不弹警告、也不记「记住了这台」，这是安全上的实质缺口，下一步要补
- **剪贴板、声音、存储/摄像头重定向**：对方有完整的重定向面板，我们一样都没做
- **会话切换器那种实时缩略图**：我们同时只维持一条会话（原生层一份全局状态）

## v1.7 — 2026-10-29

内置远程桌面从「能连上、能点」补到「能当一台机器的鼠标键盘用」：触控/鼠标两种指针模式、可锁存修饰键的功能键面板、分辨率与画面缩放；局域网页改成进入即扫描。全部改动在 Mi 10 Pro → 192.168.1.100（Windows 11，强制 NLA）上逐项验证。

### 新增

- **两种指针模式**（会话页底栏切换，选择会记住）：
  - 触控模式：手指点哪儿远端光标就去哪儿并立刻点击，长按当右键。直观，但小图标点不准
  - 鼠标模式：整块画面当触摸板，手指相对移动推动远端光标（灵敏度 50%–400%），轻点=左键、双指轻点=右键、双指上下滑=滚轮，另配左/右/中键与滚轮按钮。光标位置按远端像素累加，换分辨率或旋转都不会跳走
  - 两种模式共用一份缩放参数（横竖两轴分开存），拉满屏幕时两轴缩放不同也不会让点击位置系统性偏移
- **功能键面板**：30 个键（Esc/Tab/Enter/Space/Bksp/Del/Ins/Home/End/PgUp/PgDn/方向/Menu/Caps/F1–F12）走扫描码，顶部是 Ctrl/Alt/Shift/Win 四个**可锁存**的修饰键——点一下按住不放，关掉面板也还有效，状态行显示「按住 Win」。手机上没法一只手按 Ctrl、另一只手点屏幕，所以只能这么做
- **Ctrl + Alt + Del**：把三个键按扫描码序列发出去。真机上 Windows 11 认这串键位，直接唤出了安全注意序列界面（锁定 / 退出登录 / 更改密码 / 任务管理器）
- **分辨率与缩放**：预设 1024×768 / 1280×800 / 1366×768 / 1600×900 / 1920×1080，外加「匹配本机」按手机物理像素算一个横屏桌面；缩放支持等比适应与拉满屏幕；都存 SharedPreferences，下次连接沿用
- **局域网页进入即扫描**：不用再点一次按钮；没有可用网段（只连了蜂窝）时不自动扫，免得一进来就弹「网段格式不对」
- **远端光标自绘**：连接时声明支持「服务端指针」，指针形状就走独立的指针更新报文。原生层缓存形状（非预乘 ARGB）与热点，Java 侧用一层叠加 View 画在 Surface 之上——鼠标模式下光标每动一次都要重画，整帧重绘要把 3 MB 位图重新刷上 Surface，拖动会明显卡；这一层只失效自己。服务端说「用默认箭头」时自己画一个，说「隐藏」时（全屏视频一类）就不画

### 修改

- 原生输入层改用 `ironrdp_input::Database` 维护键鼠状态，不再自己拼 `MousePdu`/`KeyboardEvent`：重复按下、扩展键 E0 前缀、按钮事件与移动事件的分离、`release_all` 都由它负责，代码少了一截，也把「按键卡住」这类问题从源头关掉
- JNI 接口随之收敛为 `nativePointer / nativeWheel / nativeScancode / nativeUnicode / nativeReleaseAll`；`nativeStart` 增加 `width`/`height`，`RdpKeys` 负责 Android 键码与字符到 set 1 扫描码的映射
- 会话页底栏重排：模式开关 / 键盘 / 功能键 / 显示，鼠标模式下多一行左右中键与滚轮；「输入统计」移进溢出菜单

### 修复

- **改了画面缩放不重绘**：只在收到新帧时才画，远端静止时要等下一次屏幕变化才看得到效果，会被当成「设置没生效」。加了一次强制重绘
- **中文输入法组合中的拼音被当成字符发到远端**：现在检测组合区，组合期间一个字都不发，等提交后再发最终文字
- **发过 Ctrl+Alt+Del 之后键位状态对不上**：安全桌面会重置服务端的键盘状态，而我们以为修饰键还锁存着。改成发完就清空锁存
- 功能键面板的修饰键行溢出屏幕（WIN 被切掉）、`Space`/`Home` 两个标签换行；改成 5 列布局并把最小宽度归零
- **鼠标模式看不见光标**：`RdpOutputEvent::PointerBitmap/PointerHidden/PointerDefault` 之前被 `Some(_) => {}` 一起吞掉了。声明了支持服务端指针之后，服务端就不会把光标烤进画面，于是屏幕上既不画也不显示——两个方向都空着。现在按服务端下发的形状画，形状会随上下文变（桌面箭形、文本 I 形）

### 实测（Mi 10 Pro → 192.168.1.100，Windows 11，强制 NLA）

- 鼠标模式：从画面中心拖到开始按钮、再按「左键」，开始菜单在光标处打开——证明光标位置与点击位置是同一套坐标
- 光标：拖动时白箭头跟着走，移到任务管理器的搜索框上变成 I 形（说明拿到的是服务端下发的真实形状，不是自己糊的一个箭头）
- 功能键：锁存 Win 后按 E 打开资源管理器；Ctrl+Alt+Del 唤出安全界面；Esc 关掉开始菜单
- 分辨率：1920×886 → 1024×768 触发重连并生效；切「拉满屏幕」立即重绘，不再需要等下一帧
- 自动扫描：从工具箱进局域网页，不点按钮直接开始扫并列出 5 台设备
- `lintDebug` 0 errors，`assembleDebug` 通过，退出会话页无 native abort

## v1.6 — 2026-10-29

用户提供了可连的 RDP 主机（192.168.1.100，账号 XXX），于是先把「内置客户端到底要实现到哪一层」这件事问清楚，再按问出来的结论把 IronRDP 接进去，最后在这台真机上把画面与输入跑通。

### 新增

- `RdpProbe.java`：**RDP 安全层探测**。发 X.224 Connection Request 问服务端能用哪种安全层，并且**问两遍**——只请求 TLS、再请求 TLS|HYBRID|HYBRID_EX；只问后者永远只会拿到权限最高的那个，区分不出「允许 TLS」和「强制 NLA」。结论直接决定内置客户端要不要实现 CredSSP + NTLM，而这一点光看 3389 开不开完全问不出来
- 19 字节的 client hello 不是照记忆拼的：在本机起监听、让 mstsc 连上来，把它发出的真实包抓下来当模板（只改末尾的 requestedProtocols）。失败码表按 FreeRDP `libfreerdp/core/nego.h` 抄：`0x1 SSL_REQUIRED_BY_SERVER / 0x2 SSL_NOT_ALLOWED_BY_SERVER / 0x3 SSL_CERT_NOT_ON_SERVER / 0x4 INCONSISTENT_FLAGS / 0x5 HYBRID_REQUIRED_BY_SERVER / 0x6 SSL_WITH_USER_AUTH_REQUIRED_BY_SERVER`
- 入口两处：连接簿条目菜单与局域网页的设备菜单，都叫「检测安全层」；结论写常驻状态行（toast 会被 MIUI 限流吞掉，这是上一轮已经学到的）

### 实测（本轮的关键事实）

- **192.168.1.100 与 192.168.1.44 都强制 NLA**：请求标准 RDP 或纯 TLS 时服务端一律回 `RDP_NEG_FAILURE` + `0x5 HYBRID_REQUIRED_BY_SERVER`；请求 HYBRID 选中 HYBRID，请求到 HYBRID_EX 选中 HYBRID_EX。PC 侧 Node 探测与手机侧 Java 探测结论一致（往返 21 ms）
- 因此内置客户端**必须实现 CredSSP + NTLM** 才连得上这台，「先做纯 TLS 版本」这条捷径被实测排除
- 修掉一个自己踩出来的解析 bug：RDP_NEG 数据在响应第 11 字节、值在第 15 字节。最初按 11 读 failureCode 会把 length 字段当成码，得出 `0x80003` 这种看似合理的错值——我一度据此把结论说成 `SSL_REQUIRED_WITH_NLA`，实际是 `HYBRID_REQUIRED_BY_SERVER`。已在代码注释里把这个偏移写死，避免下次再少算
- 复现验证「新建连接不会静默写凭据」：清空数据后只建连接，`shared_prefs` 里只多出 `rdp_connections.xml`，条目显示「未存账号」。真机上先前看到的「已存密码」是我自己的自动化误点凭据框留下的残留，不是应用缺陷

### 内置协议层接上了：rdpcore 改用 IronRDP

上一轮停在「两条路要用户取舍」，这一轮按选定的路线（装 Windows SDK 走 IronRDP）把它做穿，并在真机上连上了那台强制 NLA 的虚拟机。

- `rdpcore/Cargo.toml` 从**零 crate 依赖**变成三个依赖：`ironrdp` 0.17（features `client/rustls/connector/session/graphics/input`）、`tokio`、`smallvec`。只引 facade——`ironrdp-client` 由它重导出成 `ironrdp::client`，少一层版本对齐风险。TLS 后端必须显式选 `rustls`，否则报 "a TLS backend must be selected"；选 native-tls 就得在安卓上交叉编译 OpenSSL，那正是当年 FreeRDP 路线卡死的地方
- 会话由 `ConfigBuilder`（`with_credssp(true)` + `with_tls(true)` + 1280×800）→ `RdpClient::new(config, tx)` → 消费 `RdpOutputEvent::{Image, ConnectionFailure, Terminated}` 组成；帧是 `Vec<u32>` 的 `0x00RRGGBB`，Java 侧补 alpha 就是 `ARGB_8888`，**不需要换红蓝通道**（真机核对过颜色）
- `ConfigBuilder::build()` 有四个"前端必须提供"的字段（`client_build` / `client_dir` / `client_name` / `platform`），少一个就直接 bail "missing required configuration"。平台如实报 `MajorPlatformType::ANDROID`，电脑名取 `Build.MODEL` 并按协议上限截到 15 个字符（Client Info 里那是 32 字节 UTF-16 含结束符）
- **`RdpClient::run()` 不 spawn**：它穿过几个借 `&UnboundedSender<RdpInputEvent>` 的 async fn，rustc 推不出 `Send`，报一整屏 "implementation of `Send` is not general enough"。改成 `tokio::pin!` 后与取帧循环一起 select；反正运行时是 CurrentThread，单任务轮询和 spawn 在这个模型下没区别
- 错误文案统一走 `err.report()`（IronRDP 的整条 context 链）而不是 `Display`——后者只给最外层 `[context]`，真机上看到"连接失败"却不知道败在哪一步最没用；anyhow 的错误用 `{:#}`
- JNI 那层保持手写，但 `nativeStart` 从 `(host, port)` 变成 `(host, port, user, domain, password, device)`：账号由会话页在同一进程里向 `CredentialStore` 取，缺账号先弹凭据框（NLA 没账号握不了手）。密码只在这一次跨边界出现，不进 Intent、不进连接簿、不进日志
- **滚轮从占位编码改成真的**：`VERTICAL_WHEEL` + ±120。IronRDP 的 encode 会按符号自己补 `WHEEL_NEGATIVE` 并把数值塞进 flags 低 9 位，所以只要如实传格数，别自己去拼那一位

### 修在真机上的两个输入问题

- **按钮事件不能带 `PTR_FLAGS_MOVE`**。合并着发的现象很具体：远端指针跟着悬停、tooltip 都出得来，但点开始菜单没反应。按 MS-RDPBCGR 把「位置变化」和「按键状态变化」拆成两类事件——位置变了先补一条纯移动，再发不带 MOVE 位的按键事件（FreeRDP 就是这么分的），拆开之后单击、双击、切页签全部生效
- **抬手必须以 `down=false` 发同一条按钮事件**：原来的语义是"按钮位为 0 就发移动"，等于永远不发抬起，远端会认为按键一直按着。Java 侧签名同步改成 `pointer(x, y, button, down)`
- 顺带补了 `LAST_POINTER`：位置没变就不重复报移动，新会话开始时清空，否则第一条移动事件会被自己去重吃掉

### 交叉编译这套工具链的实情（下次换机器照这一段配）

- 目标侧：`aws-lc-sys`（rustls 的默认后端）带 C 与汇编，要把 NDK clang 交给 `CC_/AS_/AR_/CFLAGS_<下划线三元组>`，且 `--sysroot` 必须是 `D:/...` 形式——传 MSYS 的 `/d/...` 会被当相对路径，症状是 sysroot 里一个头文件都找不到（这个坑本轮又踩了一次）
- 宿主侧：依赖树里的 build script 是**宿主可执行文件**，链接要 Windows SDK 的 `ucrt\x64`、`um\x64` 加 VS 的 `lib\onecore\x64`，也就是 `LIB`。上一轮判定"这台机器链不动"的真正原因就是缺它；SDK 10.0.26100 装上并给出 `LIB` 之后一次通过
- 因此 `app/build.gradle` 的 `buildRdpCore` 现在同时注入这两套环境，并在 `rdpNative.lib` 与环境 `LIB` 都为空时**在配置阶段就报错说清楚缺什么**，而不是让人去猜链接器的输出
- 本机配置（cargo 绝对路径、`LIB` 那串）从仓库里的 `gradle.properties` 挪到 gitignore 掉的 `local.properties`：`findProperty` 看不到那个文件，所以构建脚本里手工读一次，`rdpNative.enabled` / `ndk` / `cargo` / `lib` 四条都走同一入口
- 代价是体积：`librdpcore.so` 从 463 KB 涨到 arm64 约 13.9 MB、armv7 约 9.65 MB（IronRDP + rustls + aws-lc + reqwest 全在树里，release 已按 `opt-level="z"` + thin LTO 压过）

### 实测（内置通道，Mi 10 Pro → 192.168.1.100，Windows 11，强制 NLA）

- 连接后状态行 `已连通 · 192.168.1.100:3389 · 1280×800`，桌面、壁纸、任务栏、桌面图标全部正常渲染，颜色无红蓝错位，画面等比居中带黑边
- **单击**开得出开始菜单（里面能看到登录用户就是 XXX），点得动搜索结果里的分类页签；**应用内键盘**敲进去的 `notepad` 出现在远端搜索框并给出「运行命令」最佳匹配；**双击**桌面图标把 Edge 真的启动起来了
- 退出会话页回到扫描页，无 native abort、无 tombstone；`lintDebug` 0 errors
- 还没做：位图缓存与 RemoteFX、会话中改分辨率、剪贴板、服务端自定义指针形状、扫描码与组合键；原生侧只有一份全局 `SESSION`，同时只能连一台
- 本轮改动与另一会话并行落地的 v1.5（共享页 Kotlin/Compose 重写、`useAndroidX=true`）共存，`assembleDebug` 与设备实测均在合并后的状态下通过


## v1.5 — 2026-10-29

共享文件从「只能取回本地」升级为「能看」。浏览页与预览页改用 Kotlin + Compose，缩略图走 Coil，视频走 Media3，PDF 走系统 `PdfRenderer`；SMB 协议层保持 jcifs-ng 为默认，SMBJ 作为可选的实验引擎并存。

### 新增

- **共享浏览页改为 Compose 实现**（类名与 Intent 契约不变，扫描页调用点一行没改）：列表 / 网格两种视图，条目带缩略图，非图片视频用扩展名角标
- **应用内预览页**（`SmbPreviewActivity` + `SmbPreviewPages`）：图片双指缩放 / 拖动 / 双击 1×↔2.5×；视频与音频边取边播、可任意拖动；PDF 逐页渲染；文本/日志/代码直接看并显示实际编码。预览页一次带走当前目录里所有能预览的项，因此可以左右翻页
- `SmbRange`：按偏移读远端文件的抽象层（`size` / `readAt` / `readFully`），jcifs-ng 走 `SmbResource.openRandomAccess("r")`，SMBJ 走 `File.read(buffer, offset, ...)`。列目录与传输仍由 `SmbClient` 负责，只有读字节这一步可切换引擎
- **系统返回键在共享里先逐级回上一层**（`SmbBrowseActivity` 接管 `OnBackPressedDispatcher`）：退到「共享列表」那一层才真的退出。之前翻了三层目录想回头看别的共享，一按返回就直接掉回扫描页，而右上角明明就有「上一级」，两条路行为不一致。真机验证：从 `E/#工具/GitHub加速神器/` 连按，前三次分别停在 `E/#工具/`、`E/`、共享列表，第四次才回扫描页
- `SmbHub`：按主机复用已登录连接并带引用计数——浏览页与预览页同时持有同一条连接，退出预览不会把浏览页的连接关掉；关闭动作一律丢到后台线程
- `SmbHttpProxy`：只在 `127.0.0.1` 上监听的极小 HTTP/1.1 服务，把 SMB 文件按 Range 暴露给播放器。选这条路的理由是 `MediaDataSource` 要 API 31，而 minSdk 24；URL 带一次性 token，防止本机其它进程猜到端口就来读
- `SmbSourceCache` + Coil 自定义 `smb://` `Fetcher`：字节缓存到 `cacheDir/smb_preview`（默认 128 MB，按最后使用时间淘汰，菜单「预览缓存」可看占用与清空）；缩略图 8 MB、原图与 PDF 64 MB 的上限会明确报「文件过大」而不是慢慢拉。Coil 3 的自定义 fetcher **不会**自动写它的磁盘缓存（读过 3.6.3 的 `EngineInterceptor` 确认），所以这层缓存必须自己管
- `SmbVideoThumbs`：视频首帧经回环 HTTP + `MediaMetadataRetriever` 抽取，内存 + 磁盘两级缓存，并发压到 2
- `SmbPdf` / `TextDecode`：`PdfRenderer` 需要可 seek 的 FD，所以 PDF 先整份落缓存再翻页（带进度）；文本按 UTF-8 → GB18030 → Big5 严格试解（`CharsetDecoder` 的 `onMalformedInput(REPORT)`），避免 GBK 中文日志显示成豆腐块还没有任何提示
- `SmbErrors`：把两个库的不同措辞（jcifs 的 "Access is denied" 与 SMBJ 的 `STATUS_ACCESS_DENIED`）归到同一套中文提示，新增「共享或文件不存在」一类
- 设置项：视图（列表/网格）、缩略图开关、预览缓存上限、以及「实验：改用 SMBJ 读文件」
- 构建：`android.useAndroidX=true`、AGP 9 内置 Kotlin（不再需要 `org.jetbrains.kotlin.android` 插件）+ Compose 编译器插件 2.4.21、Compose BOM 2026.09.00、Coil 3.6.3、Media3 1.11.1、SMBJ 0.15.0、核心库脱糖 2.1.5
- `res/xml/network_security_config.xml`：明文 HTTP 只放行 `127.0.0.1` / `localhost`，不开全局 `usesCleartextTraffic`
- 预览页不锁方向并声明 `configChanges`（旋转重建会拆掉播放器与回环服务，表现为「转一下方向就重播」）

### 修复

真机验证（Mi 10 Pro / Android 13，连 Windows 11 的 `E` 共享与飞牛 NAS）抓到四个只有跑起来才会暴露的问题：

- 连接成功后那一次列目录被自己的 `loading` 保护挡死，界面永远停在「正在读取…」——`connectWith` 顺手置了 `loading=true`，而它紧接着要调的 `load()` 开头就是「在读就跳过」；改为连接期间只改状态文字
- 共享被当成文件：共享根的 `getAttributes()` 返回 0（没有 `ATTR_DIRECTORY` 位），于是点共享弹菜单而不是进去；列共享这一层直接按目录处理
- 状态行显示「剩余 0 B」像盘满了：根层 `getDiskFreeSpace()` 返回 0 而不是「不知道」，0 一律按未知处理
- 列表整行点不动：`clickable` 之后再叠一个只管长按的 `pointerInput` 会把 down 事件吃掉；改用 `combinedClickable`

### 修改

- 日期格式化不再放成进程级常量：本应用可以在运行时切换语言，常量会带着旧 Locale 一直用（lint `ConstantLocale` 提示的是真 bug），改为按 `remember(context)` 建
- 上传/下载的同名避让逻辑收成一个 `uniqueFileIn`，浏览页与预览页共用
- README「依赖」章节重写：补上五个新库的许可与用途、SMBJ 的两条硬限制（不支持 SMB1、列不出共享）、以及包体积从 5.73 MB 到 23.6 MB（debug 包）的代价说明
- lint 保持 0 error；warning 从 31 涨到 54，新增部分集中在 Compose 页面带来的 `PluralsCandidate`（「共 %1$d 项」这类）、`LockedOrientationActivity`（新页面同样锁竖屏）、`UseKtx`（建议用 androidx.core 的写法，为了不多引一层依赖保留平台 API）与英文文案里当破折号用的连字符（`TypographyDashes`，与既有文案风格一致，不改）


## v1.4 — 2026-10-29

局域网三件套合成一个入口，并开始把远程桌面从「交给别的 App」往「在应用里操作」推进。内置通道这一版打通承载层（会话线程 / 帧缓冲 / 渲染 / 触控回传 / 真实 TCP），**RDP 握手尚未接入**，界面文案也这么写。

### 新增

- 主页只留「局域网」一项：点扫描出的设备直接在同一个菜单里选操作（查看共享文件 / 远程桌面 / 内置远程桌面 / 打开网页 / 设置账号 / 收藏 / 重新探测 / 详扫端口 / 存到连接簿 / 导出 .rdp / 复制 IP）；共享页与连接簿改从页内溢出菜单进
- `rdpcore/`：内置远程桌面的原生层，Rust 写的 cdylib，**零 crate 依赖**——JNI 那层手写，函数表槽位下标用脚本按 NDK 自带的 `jni.h` 逐条数出来，并与规范可查的槽位互相印证（GetVersion=4、FindClass=6、ThrowNew=14、DeleteLocalRef=23、GetStringUTFChars=169、NewByteArray=176、SetByteArrayRegion=208、SetIntArrayRegion=211）
- `RdpNative.java`：加载 `librdpcore.so` 并封装调用；加载失败时 `isAvailable()` 返回 false，界面自动收起内置入口，handoff 那条路不受影响
- `RdpSessionActivity.java`：会话页。30 fps 轮询取帧（不做回调进 JVM）、等比缩放居中绘制（不拉伸变形）、触控坐标按同一套缩放反算回远端像素、左/右键切换、滚轮、软键盘输入、输入统计（真机上用来证明触控确实进了原生层）
- 文本跨边界一律走 UTF-8 字节数组，不用 `NewStringUTF`（那个要「修改版 UTF-8」，中文机器名会乱码）
- 会话页不锁方向且声明 `configChanges`：原生会话线程活在进程里，Activity 重建会让画面与会话对不上
- `app/build.gradle` 新增 `buildRdpCore` 任务：cargo 交叉编译 arm64-v8a / armeabi-v7a 并投递到 jniLibs；NDK 路径按 `rdpNative.ndk` > `ANDROID_NDK_HOME` > `<sdk.dir>/ndk/<最大版本>` 依次找，缺 NDK 时给出可操作的报错。**默认不参与构建**，要 `rdpNative.enabled=true` 才启用
- 引擎选型：四个候选里只有 IronRDP 实现了 NLA/CredSSP（NTLM + Kerberos），而 NLA 是 Win10/11 默认开启、手写客户端最容易卡死的一关；FreeRDP/aFreeRDP 是 C，要 cmake + ninja + NDK + 交叉编译 OpenSSL；Thincast 是 .NET/Windows 客户端，跑不到安卓

### 修复

- 上传同名文件会直接覆盖共享上的旧文件（不可逆），改为自动避让成 `照片 (2).jpg`
- 共享页三个排序方式里只有「大小/时间」把目录置顶，按名称排序时目录和文件混排；改为三种排序都目录优先
- 后台动作的并发保护原先散在各调用点，新建/重命名/删除可以叠在传输上抢同一条进度条；改为收口到 `runTransfer`
- 传输期间打开「兼容设置」并保存会关掉正在使用的连接；改为提示先结束当前传输
- 写操作与传输的收尾在 `client` 为空时仍会执行；补上前置判断

### 移除

- `RdpActivity` 里与 `RdpLauncher.shareRdpFile` 重复的导出逻辑（扫描页与连接簿现在共用一份）

### 构建环境的实情（内置通道的取舍依据）

- 这台开发机原本没有 Rust、没有 NDK、没有 cmake/ninja。本次装了 rustup 1.99.1（stable，含 aarch64/armv7/x86_64 Android targets）与 `ndk;29.0.13113456`（2.4 GB）
- **装了 MSVC 编译器却没有 Windows SDK**（`Windows Kits` 目录不存在），所以任何带 build script / proc-macro 的依赖都链不动：`cargo install cargo-ndk` 失败、`jni` crate 也编不过；gnullvm 宿主工具链同样缺一个能链 PE 的驱动（拿 NDK clang 伪装成 `x86_64-w64-mingw32-clang` 后仍在链接期失败）。这就是 `rdpcore` 零依赖、手写 JNI 的直接原因
- NDK 的 clang 是 Windows 可执行文件，`--sysroot` 必须传 `D:/...` 形式；传 MSYS 风格的 `/d/...` 会被当成相对路径，症状是 sysroot 里一个库都找不到
- Gradle 9 移除了 `Project.exec`，外部命令要用注入的 `ExecOperations`；自定义任务的每个字段还必须带 `@Internal`/`@Input` 之类的注解

### 实测

- 真机（Mi 10 Pro）：arm64 463 KB / armv7 353 KB 两个 ABI 都编出来并进包；`.so` 加载成功（内置入口因此出现）；原生线程实测连上 192.168.1.44:3389 耗时 22 ms；测试画面确实在从原生帧缓冲刷出并等比留黑边；「输入统计」显示 10 次指针事件、最近一次已换算成远端坐标 `512,384`
- 默认构建（不传 `-P` 参数）仍然通过，没装 Rust/NDK 的人不受影响
- lint 0 error。新增的两条 warning 都是刻意取舍：会话页黑底留边触发的 `Overdraw`、隐形 IME 采集框触发的 `LabelFor`（补 `contentDescription` 后 lint 又提示不要 hint/contentDescription 并存，故只留 contentDescription）
- **未验证**：RDP 握手本身（X.224 → MCS → TLS → NLA → 位图更新）。当时没有可登录的 RDP 主机，所以停在连通性结论上没有伪造画面

## v1.3 — 2026-10-29

局域网三件套补齐：扫描从「四个端口」扩到三路发现，共享从「只能看」补到能取能写，远程桌面从一条链接变成一本连接簿。全部改动在 Mi 10 Pro（Android 13）+ 家里网段真机验证过。

### 新增

- 局域网扫描改为**三路并发发现**：TCP 两级端口探测（在场端口打每个地址、补探只对已应答的设备做）、UDP 137 广播枚举（一个包问遍整段）、mDNS 浏览扩到 10 种服务类型。端口全关着的设备（音箱、手机、IoT）现在也能进列表
- 端口集三档（精简 4 / 标准 8 / 全服务 8+36），对话框里每个选项自带代价说明；菜单新增「选择本机网段」（多网卡可切，蜂窝段刻意排除）、「只看可用设备」、「手动添加设备」
- 设备类型线索：mDNS 服务类型 → NetBIOS 角色后缀（`<1C>` 域控、`<1B|1D>` 主浏览器、`<20>` 文件服务器）→ 端口组合，猜不出来就不显示
- 收藏与手动添加落盘（`lan_devices`），跨扫描、跨重启保留；单台可「重新探测端口」或「详扫 1–1024 + 常见高位端口」；开着 80/443/8080 时菜单里直接「打开网页 :端口」
- 共享文件夹：下载到本机（私有 Download、同名加序号、带回远端时间戳）、「用其他应用打开」、「另存为…」经 SAF 直写目标、上传、新建文件夹、重命名、删除，全部带进度与取消；列目录与传输分两条队列，拷文件时能继续翻目录
- 共享文件夹：面包屑跳任意一级、关键字过滤、名称/大小/时间排序、显示隐藏项、共享剩余空间、每台机器记住最后目录；主页新增「共享文件夹」独立入口（最近主机或手输 `\\NAS\照片` / `smb://ip/`）
- SMB 兼容设置：允许 SMB1（默认关）、强制签名、来宾回退、连接超时，改完重建连接；协议版本谈不拢单独归一类错误文案
- `LocalFileProvider`：工程不引 AndroidX，故自建只读文件出口（拒绝 `..`、再校验 canonical path、`exported=false`、单次授权），供「打开方式」与分享 `.rdp` 使用
- 主页新增「远程桌面」独立入口：连接簿（增删改、重复目标挡下）、端口连通测试（握手 + RTT）、导出 `.rdp`（RDP 6 INI 文本，CRLF/UTF-8 无 BOM，不含 `password 51:b:`）、装了多个客户端时弹系统选择框
- 清单新增 `<queries>`（`freerdp` scheme 与 `application/rdp`）与 `RdpActivity`、`SmbBrowseActivity`、`LocalFileProvider` 注册

### 修改

- 凭据对话框从 `LanScanActivity` 抽成 `CredentialDialog`，扫描页、共享页、连接簿共用一份实现
- SMB 连接的建立与关闭移到工作线程；jcifs 构造根资源会走名称解析，主线程上直接抛异常
- 探测与测试的结论写进常驻状态行而不是只弹 toast：toast 三秒即散，且实测 MIUI 13 会把连续第二条 toast 吞掉
- 上传遇到同名文件自动避让成 `照片 (2).jpg`，不覆盖远端已有文件
- 端口标签对没登记过的端口只显示数字（原先渲染成 `784/`）；设备名缺失时明细行不再重复 IP
- README 的「局域网扫描」一节拆成三节并重写，「注意事项」补扫描行为、写操作、私有目录、`<queries>` 四条

### 修复

- 共享页主线程建连导致的 `NetworkOnMainThreadException`（表现为「失败：android.os.NetworkOnMainThreadException」）
- 端口集与兼容设置对话框在 MIUI 上只剩说明文字、选项列表被 `setMessage` 挤掉
- 仅靠广播或 mDNS 发现的设备被误标成「还没探测端口」（实为探过且全关）；整轮跑完后手动/收藏条目也补上这一结论
- 凭据缺失时共享页直接 `finish()` 退出，改为就地弹登录框；从主页进共享页也不再依赖必须先扫描

### 移除

- 5 条不再被引用的字符串：`lan_rdp_no_account`、`lan_rdp_info_format`、`lan_ports_count_format`、`smb_session_expired`、`smb_detail_format`（并入 `smb_props_format`），中英文各一份
- `LanScanner` 里被 `localSegments()` 取代的 `linkPropertyAddress()`

### 实测与取舍

- 真机（Mi 10 Pro，192.168.1.0/24）：全服务预设约 5 秒扫完 253 个地址，找出路由器（53/80/443/8080，旧版根本不会出现）、NAS、Windows 电脑与两台只回 mDNS 的 AirPlay 音箱；详扫在路由器上多找出 784 端口；远程桌面端口测试握手 33 ms；导出的 `.rdp` 内容逐行核对正确、分享面板可选到网盘与文件管理器；Keystore 加密的凭据确认落盘为密文
- **没有做厂商（OUI）识别**：安卓上能拿到 MAC 的通道只有 UDP 137，只有开了共享的 Windows/Samba 会应答，`/proc/net/arp` 自 Android 10 被 SELinux 拦住；权威数据集里光 Apple 就 1593 条前缀，为「已经知道是 Windows 电脑」的设备背这张表换不来信息，类型判断改走实际拿得到的信号
- **仍未内置 RDP 客户端**：FreeRDP 交叉编译需要宿主机 make/pkg-config 与一份 Linux NDK，条件没变化（详见 README）
- lint 0 error、51 warning（上版 31）。新增的 20 条集中在 `LockedOrientationActivity`（3 个新 Activity 沿用强制竖屏）、`DiscouragedApi`（同源）、`PluralsCandidate`（`共 %d 项` 一类）、`Typos`（英文文案里的 "SMB1 "），均为既有取舍；`QueryPermissionsNeeded` 已通过 `<queries>` 消除
- debug 包 5.84 MB → 5.73 MB（本次新增代码约 60 KB，体积差异来自构建产物缓存与资源重排）

## v1.2 — 2026-10-28

### 新增

- 局域网扫描：对本机 /24 网段并发探测 135/139/445/3389 四个端口，机器名走 NetBIOS UDP 137 → mDNS → 反向 DNS 三路兜底，结果按 IP 末段稳定排序
- SMB 共享浏览：点「查看共享文件」先列共享（srvsvc NetrShareEnum），逐级进目录，文件弹详情并可复制 UNC 路径；只读，不写不删不下载
- 凭据存储：Keystore AES-256/GCM 加密后写 SharedPreferences，可勾「记住密码」；不勾只在进程内存里，页面间传递不走 Intent
- 远程桌面走 handoff：把 `freerdp://<用户>@<IP>:3389/connect` 交给系统里注册该 scheme 的客户端（aFreeRDP 等），无可用客户端时复制链接并提示；密码不进 URI，域经 `?d=` 传，不生成 `.rdp` 也不要 FileProvider
- 主机菜单新增「设置登录账号」：只保存不连接的入口，远程桌面 handoff 靠它才能带上用户名与域
- 权限新增 `ACCESS_NETWORK_STATE`

### 修改

- 依赖新增 `org.codelibs:jcifs:3.0.4`（jcifs-ng，LGPL-2.1）及其 compile 依赖 `bcprov-jdk18on`；debug 包 1.19 MB → 5.84 MB，APK 内 `androidx/*` 类数仍为 0

### 移除

- `gradle.properties` 的 `android.overridePathCheck=true` 与 README 里两条中文路径说明：工程已改名到纯 ASCII 路径 `F:\phone\ToolKit`，去掉后实测 `assembleDebug` 通过

## v1.1 — 2026-10-21

### 修改

- 固件下载：擦除成功后直接写入，不再复位重进 Bootloader（对齐 ISP_C_V1.3 的下载流程）；只有解写保护（0x73）后仍重新同步，避免手动模式擦除后从空 Flash 启动跑飞
- README 的「擦除」说明同步更新

## v1.0 — 2026-10-13

首次入库，同时完成一轮 lint 清理（6 error + 59 warning → 0 error + 31 warning）。

### 新增

- 三个工具：分贝仪（RMS→dBFS + 1/1 倍频程频谱 + 60 秒曲线 + 校准偏移）、域名解析（A/AAAA，列表存本机）、固件下载（AN3155 串口 ISP 烧录 STM32，支持 CH340/CP210x/FTDI）
- 中英双语界面，深浅色跟随系统主题
- `tools/gen_icons.js`：纯 Node 零依赖的图标生成脚本，由 `assets/icon_source_raw.png` 派生各密度方形/圆形图标、自适应前景与单色层
- `.gitignore`（排除编译产物、IDE 元数据、本机调试遗留、签名与凭据四类）、本更新日志
- README 新增「注意事项」「仓库与提交规范」两节

### 修改

- 启动图标：方形版图形缩到画布 61% 并转透明安全区，圆形版改为真正的白圆盘，自适应图标补 `<monochrome>` 层——消除 `IconLauncherShape` 10 条、`IconDuplicates` 5 条、`MonochromeLauncherIcon` 2 条
- 布局中 4 处硬编码 `--` 提取为 `@string/stat_placeholder`（`translatable="false"`，只留在默认资源里）
- `tools/*.ps1` 的默认源图路径改为仓库内 `assets/`，不再指向个人目录

### 修复

- 校准偏移文案 `cal_current` 的占位符由 `%1$s` 改为 `%1$.1f`，不再出现 `89.70000458` 这类长尾数，且跟随本地化小数格式
- `NoiseMeter.createRecord` 显式捕获 `SecurityException`，运行期权限被撤回时返回初始化失败而非依赖兜底逻辑
- `IspActivity.registerUsbReceiver` 标注 `@SuppressLint("UnspecifiedRegisterReceiverFlag")`：不带 flag 的重载在 API 33 才存在，无法与带 flag 的版本合并，实际逻辑已按系统版本分级

### 移除

- 4 条全项目零引用的字符串：`isp_invalid_delay`、`isp_log_saved_to`、`isp_read_flash_canceled`、`isp_hex_dump`（中英文各一份）

### 已知保留的 lint 提示（刻意取舍，非缺陷）

`LockedOrientationActivity` 4 条（强制竖屏）、`PluralsCandidate` 5 条、`DiscouragedApi` 4 条、`ButtonStyle` 3 条、`ClickableViewAccessibility` 3 条、`TypographyDashes` 2 条、`OldTargetApi` 1 条（targetSdk 34）等，共 31 条。
