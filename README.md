# 工具箱（Toolbox）

安卓手机工具合集应用，当前包含「分贝仪」「域名解析」「固件下载」，其他功能待添加。界面纯系统控件实现（不使用 Compose），仅「固件下载」依赖一个 USB 串口驱动库（见「依赖」一节）。

## 功能

### 分贝仪（主页 → 分贝仪）

- 实时分贝显示（30–120 dB 刻度条），RMS 声压计算，125 ms 指数平滑
- 60 秒滚动实时曲线（纵轴按数据自动量程，5 dB 步进，深浅色自适应）
- **声音频段显示**：每帧 4096 点 FFT（Hann 窗）按 1/1 倍频程分成 9 段（63 Hz / 125 / 250 / 500 / 1k / 2k / 4k / 8k / 16k），柱状显示并带峰值记忆线；右上角文字提示当前**主频段**及其能量占比（如「主频段 500 Hz（占 32%）」）
  - 频段电平按能量占比换算：`频段 dBFS = 总 dBFS + 10·log10(该段能量 / 各段能量之和)`，保证各频段能量之和恰等于总声压，且与总分贝共用同一校准偏移
  - FFT 在采集线程执行，不占用主线程；频率分辨率 ≈ 11.7 Hz（48 kHz 采样 / 4096 点）
- **麦克风输入设备可选**：内置麦克风、有线耳机、蓝牙 (SCO/BLE)、USB 麦克风等（`AudioRecord.setPreferredDevice`）
- 统计：最大 / 最小 / 能量平均 Leq
- 校准偏移可调（dBFS + 偏移 = 显示 dB，默认 90）
- 采集优先使用 `UNPROCESSED` 音源（绕过 AGC 自动增益，更接近真实声压），不支持时自动回退 `MIC`
- 状态栏信息：dBFS 原始值、采样率 48 kHz、音源类型、实际路由设备
- 深浅色跟随系统；中文 / English 双语，默认跟随系统，也可在右上角「语言」菜单手动切换（选择持久化保存）
- 测量中保持屏幕常亮；页面离开自动停止采集

### 域名解析（主页 → 域名解析）

- 输入域名后同时查询 **A 记录（IPv4）** 与 **AAAA 记录（IPv6）** 并并列显示，用于核对 dynv6 这类动态域名的当前 IP
- 顶部输入框是常留空位：输入域名后回车或点「解析」即加入列表（自动小写、去 `http(s)://` 前缀、去路径与结尾点号）；列表为空时页面居中显示添加提示
- **长按地址复制到剪贴板**；长按域名弹出菜单，可复制域名 / 重新解析 / 从列表删除（删除有二次确认）
- 进入页面自动查询全部域名，每条结果显示 IPv4 / IPv6 条数与查询时刻；标题栏**右上角刷新按钮**（不在溢出菜单里）重新解析列表内所有域名，长按单条域名也可只重解该条
- 查询在单线程池执行，不阻塞界面；同一域名只允许一个请求在跑；无网络或域名不存在时该条显示失败原因
- 域名列表持久化在 SharedPreferences（`dns/domains`），解析结果不持久化，每次进入重新查询
- 需要 `INTERNET` 权限（DNS 查询走系统解析器，不需额外运行时申请）；深浅色与中英双语同全局

### 固件下载（主页 → 固件下载）

用手机经 OTG + USB 转串口（CH340 等）把固件写进 STM32 片内 Flash，走芯片出厂固化的系统存储器 Bootloader（AN3155 串口 ISP 协议，8 数据位 / 偶校验 / 1 停止位），不需要 ST-Link/J-Link。

- **进入 Bootloader 的 17 种方式**（对应转接板 DTR/RTS 与 BOOT0/NRST 的各种接法）：0=手动按键；1–4 RTS 接 NRST（反相）+ DTR 接 BOOT0；5–8 两者反接；9–12 单线复位脉冲；13–16 只设 BOOT0 电平。步延时可调（默认 100 ms）
- **芯片识别**：读 Bootloader 版本与支持命令表、PID、Flash 容量寄存器实测容量，再据此在型号表里消歧（0x0415 / 0x0411 / 0x0416 / 0x0417 / 0x0441 这类多义 PID），覆盖 F0/F1/F2/F3/F4/F7/L0/L1/L4/L4+/L5/U5/G0/G4/H5/H7/C0/WB/WBA/WL
- **读保护判定**：由 GET_VERSION 的选项字节推断 L0/L1/L2；下载前自动解除写保护，擦除失败会自动解保护后重连再试一次；解除读保护（0x92）有二次确认，因为它会全片擦除且 Level 2 不可逆
- **擦除**：优先扩展擦除 0x44，不支持或失败则回退标准擦除 0x43（全局），擦完回读 256 字节应全为 0xFF
- **写入**：256 字节分页；末块按 4 字节对齐补 0xFF（协议要求长度是 4 的倍数，且避免顶到 Flash 末尾越界）。可选逐块回读校验（耗时约翻倍），完成后可 GO + 复位直接运行
- **最近打开的固件**：加载成功的固件记入「最近打开」列表（最多 5 条，显示文件名 + 字节数，当前已加载的一条用强调色标出），下次进页面直接点它即可重新载入，不必再翻文件管理器；长按可移除。靠 SAF 的 `takePersistableUriPermission` 保住长期读权限，进页面时校验权限是否仍在（文件被移动/删除或授权被撤的条目自动丢弃），点开读不到也会顺手移除并提示。BIN 的起始地址取界面上当前填写的值，所以改完地址再点一次最近项就能按新地址重新解析
- **辅助功能**：整片 Flash 读回并导出 Intel HEX（备份现有程序）、任意地址段读内存（带地址的十六进制窗口）、选项字节读 / 写（16 字节成对「值 + 反值」，并按 F4/H7、F1 语义解释 LOCK / BOR / WDG / nWRP / RDP）、调试命令面板（单发同步、GET、GET_ID、GET_VERSION、两种擦除、GO、解写/读保护、读容量寄存器、手动发送原始字节、单独置 DTR/RTS 电平）
- **中止**：写入与读回过程可随时中止，协议层每个等待循环按 250 ms 分片检查取消标志
- **日志**：每行 `[时:分:秒.毫秒] [等级]` 前缀（TRCE/INFO/CMD/WARN/ERR/OK），命令与应答按 `>> / <<` 打印十六进制，便于对照复位与握手时序排障
- **芯片信息可折叠**：默认只显示一行「型号 · Flash 容量 · 读保护」，点击该行才展开 PID、Bootloader 版本、SRAM、选项字节读数、支持的命令表等细节；展开状态持久化（`isp/chipExpanded`）
- **日志导出**：日志框下方「导出日志」（也可从溢出菜单进入）把当前日志存为 `isp_yyyyMMdd_HHmmss.txt`，文件开头附带机型、系统版本、波特率、进入方式、延时、固件名等一行摘要，便于事后排障；旁边是「清除日志」
- **日志可自由翻看**：日志区是可独立上下滚动的子 ScrollView——嵌套滚动必须在下按时 `requestDisallowInterceptTouchEvent(true)`（否则手势总被外层页面抢走，日志根本翻不动），且因为日志文本可选中文本、会先吃掉 `ACTION_DOWN`，监听要同时挂在 `tvLog` 和 `svLog` 上。自动跟随底部只在「当前就贴着底部」时生效，往上翻历史不会被拽回去；新任务开始时回到底部。另有「全屏」按钮用整屏视图查看/复制长日志
- **日志不拖慢下载**：数据块（256 字节）只展开前 12 字节再省略其余；日志行先攒进缓冲，每 150 ms 用 `TextView.append` 增量刷新一次；进度回调按 100 ms 节流。若每条日志都整体重设文本，下载几十页后界面会因反复重排大段文本而卡死（实测过）
- 界面观感：分区标题用强调色 + 细底线（`styles.xml` 里的 `IspSection` / `IspDivider` / `IspLabel` / `IspHeaderRow`），日志区带圆角边框（`drawable/bg_isp_box.xml`），连接状态前加 `●` 并在已连接时用强调色；颜色全部取 `?android:attr` 主题属性，深浅色跟随系统
- 参数持久化在 SharedPreferences（`isp`：波特率、进入方式、延时、BIN 起始地址、逐块校验、完成后运行、上次设备 VID:PID），同一台设备再次插入会自动预选
- 首次连接会弹系统 USB 授权框；应用声明了 `device_filter.xml`（CH340/CH341、CP2102、FT232/FT2232、PL2303），插入这些设备时系统可直接询问是否用工具箱打开；设备拔出自动断开
- 界面主体是 `activity_isp.xml`，可在 Layout Editor 拖拽修改；调试命令、选项字节、读内存三个对话框由代码动态生成，无法在布局编辑器里拖拽
- 需要手机支持 USB OTG；不支持时本页只会提示「未发现 USB 串口设备」，其它功能不受影响
- 菜单里有「关于 CH340 驱动」说明：安卓内核没有 CH340 驱动，也不需要另装驱动 App，本工具在应用层直接驱动该芯片

#### 排障：CH340 读多字节响应必须避开整包长度

安卓的 `bulkTransfer` 把「请求长度」当作传输结束条件之一：CH340 的 bulk-IN 每包 32 字节，若请求长度正好是 32 的整数倍（如 512），而芯片发来的数据也正好是整数包（如 ReadMemory 的 256 字节 = 8 个满包、没有短包收尾），这次传输就永远等不到结束，超时后内核把已收到的数据整批丢弃。症状极具辨识度：**单字节 ACK 每次都能收到，256 字节数据一个都收不到**。

因此协议层的接收缓冲固定按 511 字节请求（`IspProtocol.RX_REQUEST`），保证任何一次读取都以短包结束。另外 `readExact` 有真实超时并会报「期望 N 字节，只收到 M 字节」，不会在原地忙等。

#### 排障：同步能通但 GET 无回应

「发 `0x7F` 能收到 ACK，发命令却完全无回应」有两种成因，用菜单 → 调试命令 → 「线路自检（校验位是否正常）」区分，它依次发 `7F`、`00 FF`、`01 FE`、`02 FD`：

1. **接收侧丢包**（本项目实际踩到的是这个）：驱动/协议层读法不对，多字节响应一个都收不到，见上一节。自检里四条探测都会「无回应」或表现与字节长度相关。
2. **校验位没生效**：STM32 Bootloader 只接受偶校验（8E1）。若转串口实际按 8N1 发送，第 9 位恒为停止位的 1，于是只有「数据位里 1 的个数为奇数」的字节能被当成合法偶校验收下——`0x7F`（7 个 1）能通，而 `0x00 FF`（0 和 8 个 1）被芯片判为校验错误静默丢弃。自检结果就是 `7F`/`01 FE`/`02 FD` 有回应、`00 FF` 没有。这种情况换 CH340G/CH341 原装芯片或 CP2102 转接板即可（本工具也支持 CP2102、FT232、PL2303、标准 CDC）。

另外每次进入 Bootloader 前都会重发一次 `8E1` 线路参数：DTR/RTS 的控制写在部分兼容芯片上会冲掉 UART 配置。

## 依赖

只有一个第三方库：`com.github.mik3y:usb-serial-for-android:3.9.0`（JitPack，MIT），「固件下载」页用它做 USB 转串口通信。安卓内核没有 CH340/CH341 驱动，该库在应用层实现了这些芯片的初始化、收发与 DTR/RTS 控制，因此用户不需要另装任何驱动 App。

- 工程仍保持 `android.useAndroidX=false`：库的 `androidx.annotation`（编译期注解）与 `kotlin-stdlib` 已用 `exclude` 去掉，APK 内没有 androidx 类
- `settings.gradle` 增加了 `maven { url 'https://jitpack.io' }` 并用 `content { includeGroup 'com.github.mik3y' }` 限定只从它取这一个坐标（阿里云镜像上没有该库），其余依赖仍走阿里云镜像
- 想支持更多 USB 串口芯片：`res/xml/device_filter.xml` 里加对应 VID/PID 即可，驱动库本身已内置识别

## 环境要求

使用 Android Studio 自带的 JDK / SDK / Gradle，无需单独安装工具链。
首次打开项目时 Android Studio 会自动提示安装所需 SDK 组件（platform 34、build-tools 34.0.0）。

## 构建

命令行构建（复用 Android Studio 自带 JDK 与 Gradle，把 `<AS>` 换成本机 Android Studio 安装目录）：

```bash
export JAVA_HOME="<AS>/jbr"
<AS>/gradle/gradle-<版本>/bin/gradle.bat -p . assembleDebug
# 或者直接用工程自带 wrapper：./gradlew assembleDebug
```

或直接用 Android Studio：Build → Build APK(s)。

说明：
- `settings.gradle` 已配置阿里云镜像仓库（直连 dl.google.com 会 TLS 握手失败）
- `gradle.properties` 已加 `android.overridePathCheck=true`（工程路径含中文，实测构建可通过）
- 工具链：AS 2026.2.1 jbr(JDK25) + Gradle 9.6.0 + AGP 9.1.0 + compileSdk 37 + build-tools 36.0.0
- 首次构建需要 `local.properties` 里的 `sdk.dir` 指向本机 Android SDK 根目录；该文件不入库，由 AS 打开工程时自动生成

产物：`app/build/outputs/apk/debug/app-debug.apk`（可直接安装，minSdk 24 / Android 7.0+）。

## 安装到手机

```bash
# 用 SDK 自带的新版 adb（Android 16 需 platform-tools 36+）
<SDK>/platform-tools/adb.exe install -r -t app/build/outputs/apk/debug/app-debug.apk
```

`-t` 不能省：debug 包带 `testOnly` 标记，缺省会报 `INSTALL_FAILED_TEST_ONLY`（Android Studio 会自动加，命令行要手动加）。

### 无线调试 / 安装常见问题

- **AS 提示 `minSdk(API 24) > deviceSdk(API 21) on device Unknown`**：不是手机版本低。小米 HyperOS 会过滤 adb shell 读到的属性（`getprop` 返回 `ro.build.version.sdk=21`、`release=6.0.1` 等占位值），AS 据此误判。开启「开发者选项 → USB 调试（安全设置）」（需登录小米账号、插 SIM、联网）后重连即可；`adb install` 走设备端 PackageManager 校验，不受此影响。
- **编译报「程序包 android.app 不存在」/ 产物只有 v1 签名（`INSTALL_PARSE_FAILED_NO_CERTIFICATES`）**：Gradle 守护进程缓存了错误的 SDK 路径（例如指向只含 emulator、没有 platforms 的第二个 SDK 根目录）。执行 `gradle --stop` 后重新构建，并在 AS 里把 Android SDK 目录统一指向实际安装的那份。
- 校验 APK 签名方案：`<SDK>/build-tools/<版本>/apksigner.bat verify --verbose <apk>`，Android 16 要求 v2 及以上。

## 新增功能

在 `ToolBoxActivity.tools` 列表注册 `new Tool(R.string.title_xxx, XxxActivity.class)` 即可（target 传 null 为灰色占位项）。

## 目录结构

```
app/src/main/java/com/decibelmeter/app/
  ToolBoxActivity.java # 工具箱主页（功能列表 + 待添加占位）
  MainActivity.java   # 分贝仪界面、权限、设备选择、统计与校准
  DnsActivity.java    # 域名解析：A/AAAA 查询、域名列表持久化、长按复制
  IspActivity.java    # 固件下载页：USB 设备选择与授权、参数持久化、文件读写(SAF)、调试/选项字节对话框
  IspSession.java     # 一次下载会话的编排：进 Bootloader、型号识别、地址校验、下载/校验/读回/选项字节、调试单步
  IspProtocol.java    # AN3155 串口 ISP 协议：命令帧、地址与校验和、ACK/NAK、同步重试
  BootLoaderControl.java # 用 DTR/RTS 驱动 BOOT0/NRST 的 17 种进入与退出时序
  FlashProgrammer.java# 擦除、256 字节分页写入、逐块与整体回读校验、整片读回
  ChipDatabase.java   # STM32 型号表（PID/容量/页/选项字节地址）与多义 PID 消歧打分
  FirmwareImage.java  # 固件分段模型 + Intel HEX 解析与导出、BIN 加载
  IspLog.java         # 后台线程到界面的日志/进度/取消回调接口
  BaseActivity.java   # 语言切换菜单基类
  LocaleHelper.java   # 应用内语言持久化与 Locale 包装
  NoiseMeter.java     # AudioRecord 采集线程 + RMS→dBFS + FFT 频段回调
  MeterView.java      # 30–120 dB 刻度条自定义 View
  CurveView.java      # 60 秒滚动分贝曲线自定义 View
  SpectrumAnalyzer.java # Hann 窗 + radix-2 FFT + 1/1 倍频程能量聚合（纯 Java，无依赖）
  SpectrumView.java   # 频段柱状图自定义 View（自动量程、峰值记忆、主频段高亮）
assets/icon_source_raw.png # 图标原始设计稿（2048px，右下角带生成器水印）
assets/icon_master.png     # 由设计稿紧裁出的 512px 母图，是 gen_icons.js 的输入
tools/gen_icons.js         # 图标生成脚本（纯 Node，零依赖）：母图 → 各密度方形/圆形图标 + 自适应前景 + 单色层
tools/make_icon_from_png.ps1 # 历史脚本（PowerShell + System.Drawing），裁切逻辑已由 gen_icons.js 覆盖，保留备查
tools/analyze_icon.ps1       # 辅助脚本：打印设计稿非白像素包围盒，用于确认水印与图形本体不重叠
tools/make_icon.js           # 早期纯 Node 手绘图标脚本（另一套图形），当前资源不由它生成
.gitignore                 # 入库排除规则，见「仓库与提交规范」
CHANGELOG.md               # 每次推送的变更说明
```

### 启动图标

一条命令重出全部图标资源（需要 Node，无需 PowerShell 和额外依赖）：

```bash
node tools/gen_icons.js
```

脚本流程：读 `assets/icon_source_raw.png` → 覆盖右下角水印矩形 → 按非白像素求包围盒取正方形 → 缩成 512px 母图 `assets/icon_master.png` → 把与画布边缘连通的近白区域转为透明（否则图标会被判成「铺满方形」，启动器上也看不出圆角）→ 派生：

| 产物 | 尺寸 | 图形占画布 | 说明 |
| --- | --- | --- | --- |
| `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png` | 48/72/96/144/192 | 61% | 方形传统图标，四周透明安全区 |
| `mipmap-*/ic_launcher_round.png` | 同上 | 66% | 圆形传统图标：不透明白圆盘 + 盘内图形，与方形版像素不同 |
| `drawable-nodpi/ic_launcher_foreground.png` | 432 | 62% | 自适应图标前景，透明底，白底由 `colors.xml` 的 `ic_launcher_background` 提供 |
| `drawable-nodpi/ic_launcher_monochrome.png` | 432 | 62% | 单色剪影（墨色转白色 alpha），供 `mipmap-anydpi-v26/*.xml` 的 `<monochrome>` 层，Android 13+ 主题图标用 |

换设计稿的步骤：替换 `assets/icon_source_raw.png` → 跑 `powershell -File tools/analyze_icon.ps1` 看水印包围盒 → 若水印位置变了，同步 `gen_icons.js` 里 `wmX/wmY` 的比例（0.808 / 0.925）→ 重新执行 `node tools/gen_icons.js` 并构建。
注意：PowerShell 5.1 会把无 BOM 的 UTF-8 脚本按 GBK 解析，两个 `.ps1` 必须存成带 BOM 的 UTF-8（路径含中文时会踩这个坑）。

## 注意事项

- **权限只有两项**：`RECORD_AUDIO`（分贝仪）与 `INTERNET`（域名解析）。固件下载不需要存储权限，选固件走系统文件选择器（SAF）。
- **数据只存本机**：域名列表、校准偏移、ISP 参数全部写 `SharedPreferences`，不联网上传、不含任何预置的第三方域名或 IP；卸载即清除。
- **域名解析会发起真实 DNS 查询**：`InetAddress.getAllByName` 走系统解析器，你输入的域名会被发往当前网络的 DNS 服务器。
- **分贝精度**：手机麦克风无声学标定，绝对精度约 ±3–10 dB，详见「测量精度说明」；本应用是参考级工具，不能替代声级计。
- **固件下载有变砖风险**：烧录会擦除片内 Flash，选项字节改写可能导致读保护、调试口关闭等后果；操作前先用「读回」备份原片内容，并确保供电稳定、串口线共地。BOOT0/NRST 自动时序依赖接线正确，接错只是进不去 Bootloader，但误改选项字节需要额外手段恢复。
- **USB 功能只能真机验证**：Android 模拟器没有 USB 宿主，`UsbManager`/OTG 相关路径（固件下载）必须在带 OTG 的实机上测；CH340/CH341、CP210x、FTDI 由 `usb-serial-for-android` 驱动。
- **强制竖屏**：四个 Activity 都固定 portrait，避免旋转导致采集线程与 USB 会话重建，因此 lint 的 `LockedOrientationActivity` 提示属刻意取舍。
- **targetSdk 仍是 34**：上调前需回归前台服务、通知权限与 USB 授权行为。
- **中文路径**：工程目录含中文时靠 `android.overridePathCheck=true` 才能构建，克隆到纯英文路径可去掉这项。

## 仓库与提交规范

代码同步托管在 GitHub 与 Gitee 两处，规则如下。

**入库范围**（`.gitignore` 已落地，提交前用 `git status` 复核）：

| 类别 | 处理 |
| --- | --- |
| 编译产物 | 排除：`build/`、`.gradle/`、`*.apk`、`*.aab`、`*.dex`、`lint-results*`、`.cxx/`、`captures/` |
| IDE 与工具元数据 | 排除：`.idea/`、`*.iml`、`local.properties`（含本机 SDK 绝对路径）、`.DS_Store`、`Thumbs.db` |
| 本机调试遗留 | 排除：`Python/`（AS Python 插件在本目录落的解释器缓存，100MB+）、`_verify/`（真机截图）、`*.log` |
| 签名与凭据 | 一律排除：`*.jks`、`*.keystore`、`*.p12`、`*.pem`、`.env`、`secrets*`、`*token*`；密码/令牌只允许通过环境变量临时传入，不写入任何入库文件 |
| 构建必需 | 保留：`gradle/wrapper/gradle-wrapper.jar`、`gradlew`、`gradlew.bat`、`settings.gradle` 里的公共镜像地址 |

**提交信息**：中文，一行主题 + 空行 + 要点列表。主题格式 `范围: 做了什么`，范围用功能名或模块名，例如：

```
固件下载: 支持读回整片 flash 并校验

- 新增读回进度与中断处理
- 修复读回长度未按页对齐的问题
```

**每次推送必须写更新内容**：推送前在 `CHANGELOG.md` 顶部追加一条（新增 / 修改 / 修复 三类），提交信息主题与该条对应；两个远端推同一个 commit，不允许只推一边后就继续改动。

**推送命令**（`origin` 为 GitHub，`gitee` 为 Gitee）：

```bash
git push origin main
git push gitee main
```

界面资源：`res/layout/activity_main.xml`（分贝仪）、`res/layout/activity_dns.xml` + `item_dns_domain.xml`（域名解析页与列表条目）、`res/layout/activity_isp.xml`（固件下载页）、`res/values/styles.xml`（固件下载页的分区标题/分隔线/标签/可展开标题行样式）、`res/drawable/bg_isp_box.xml`（日志区圆角边框，颜色取主题属性）、`res/drawable/ic_dns_refresh.xml`（标题栏刷新按钮矢量图标，用 `colorControlNormal` 着色适配深浅色）、`res/xml/device_filter.xml`（声明可自动接管的 USB 串口芯片 VID/PID）。

## 测量精度说明

手机麦克风未做声学标定，绝对精度约 ±3–10 dB。建议找一个参考声级计或标准声源，用「校准」按钮调整偏移量后再使用。
