# 工具箱（Toolbox）

安卓手机工具合集应用，主页当前是五个可用入口：「分贝仪」「域名解析」「固件下载」「局域网」（扫描 + 共享文件夹 + 远程桌面合成一个入口）与「已下载 / 最近打开」，后面还挂着一条灰色「更多工具（待添加）」占位（`ToolBoxActivity.java` 的 `tools` 列表）。界面以系统控件（ListView / AlertDialog）实现为主，共享文件夹那条线上的**四页**用 Kotlin + Jetpack Compose——浏览、预览、压缩包解压、「已下载 / 最近打开」（缩略图走 Coil，视频走 Media3，PDF 走系统 `PdfRenderer`），第三方依赖见「依赖」一节；内置远程桌面直接使用 `FreeRDP` 官方 Android 客户端（`freerdpcore/` 模块承载会话界面与控制逻辑，原生层静态链入 OpenSSL），这个模块**总是参与构建**（`settings.gradle` 无条件 `include ':freerdpcore'`，没有开关）——缺 `third_party/` 的静态库时 `assembleDebug` 会直接在 CMake 阶段报错，而不是跳过远程桌面。

## 目录

- [功能](#功能)
- [依赖](#依赖)
- [开源项目与致谢](#开源项目与致谢)
- [环境要求](#环境要求)
- [构建](#构建)
- [安装到手机](#安装到手机)
- [新增功能](#新增功能)
- [目录结构](#目录结构)
- [注意事项](#注意事项)
- [仓库与提交规范](#仓库与提交规范)
- [测量精度说明](#测量精度说明)

## 功能

### 分贝仪（主页 → 分贝仪）

- 实时分贝显示（30–120 dB 刻度条），RMS 声压计算，125 ms 指数平滑
- 60 秒滚动实时曲线（纵轴按数据自动量程，5 dB 步进，深浅色自适应）
- **声音频段显示**：每帧 4096 点 FFT（Hann 窗）按 1/1 倍频程分成 9 段（63 Hz / 125 / 250 / 500 / 1k / 2k / 4k / 8k / 16k），柱状显示并带峰值记忆线；右上角文字提示当前**主频段**及其能量占比（如「主频段 500 Hz（占 32%）」）
  - 频段电平按能量占比换算：`频段 dBFS = 总 dBFS + 10·log10(该段能量 / 各段能量之和)`，保证各频段能量之和恰等于总声压，且与总分贝共用同一校准偏移
  - FFT 在采集线程执行，不占用主线程；频率分辨率 ≈ 11.7 Hz（48 kHz 采样 / 4096 点）
- **麦克风输入设备可选**：内置麦克风、有线耳机、蓝牙 (SCO/BLE)、USB 麦克风等（`AudioRecord.setPreferredDevice`）；旁边有**「刷新设备」**按钮（`MainActivity.java:89-94`）——插上 USB 麦克风不必重进页面，重扫一遍设备列表并在提示里写明「发现 N 个输入设备」，如果正在测量还会立刻按新设备重启采集
- 统计：最大 / 最小 / 能量平均 Leq
- 校准偏移可调（dBFS + 偏移 = 显示 dB，默认 90）
- 采集优先使用 `UNPROCESSED` 音源（绕过 AGC 自动增益，更接近真实声压），不支持时自动回退 `MIC`
- 状态栏信息：dBFS 原始值、采样率 48 kHz、音源类型、实际路由设备
- 深浅色跟随系统；中文 / English 双语，默认跟随系统，也可在右上角「语言」菜单手动切换（选择持久化保存）
- 测量中保持屏幕常亮；页面离开自动停止采集

### 域名解析（主页 → 域名解析）

- 输入域名后同时查询 **A 记录（IPv4）** 与 **AAAA 记录（IPv6）** 并并列显示，用于核对 dynv6 这类动态域名的当前 IP
- 顶部输入框是常留空位：输入域名后回车或点「解析」即加入列表（自动小写、去 `http(s)://` 前缀、去路径与结尾点号）；列表为空时页面居中显示添加提示
- **长按地址复制到剪贴板**；长按域名弹出四项菜单（`DnsActivity.showDomainMenu`）：**远程连接**（拿这个域名直接开内置远程桌面，端口 3389；故意不在这里先解 IP——交给会话前由启动器 `RdpSessionActivity` 逐个试连，并把「域名 → 实际可达 IP」显示在信息面板里，换网络后解析值还会变，存域名比存某一次解出来的 IP 合适）/ 复制域名 / 重新解析 / 从列表删除（删除有二次确认）
- 进入页面自动查询全部域名，每条结果显示 IPv4 / IPv6 条数与查询时刻；标题栏**右上角刷新按钮**（不在溢出菜单里）重新解析列表内所有域名，长按单条域名也可只重解该条
- 查询在单线程池执行，不阻塞界面；同一域名只允许一个请求在跑；无网络或域名不存在时该条显示失败原因
- 域名列表持久化在 SharedPreferences（`dns/domains`），解析结果不持久化，每次进入重新查询
- 需要 `INTERNET` 权限（DNS 查询走系统解析器，不需额外运行时申请）；深浅色与中英双语同全局

### 固件下载（主页 → 固件下载）

用手机经 OTG + USB 转串口（CH340 等）把固件写进 STM32 片内 Flash，走芯片出厂固化的系统存储器 Bootloader（AN3155 串口 ISP 协议，8 数据位 / 偶校验 / 1 停止位），不需要 ST-Link/J-Link。

- **进入 Bootloader 的 17 种方式**（对应转接板 DTR/RTS 与 BOOT0/NRST 的各种接法）：0=手动按键；1–4 RTS 接 NRST（反相）+ DTR 接 BOOT0；5–8 两者反接；9–12 单线复位脉冲；13–16 只设 BOOT0 电平。步延时可调（默认 100 ms）
- **芯片识别**：读 Bootloader 版本与支持命令表、PID、Flash 容量寄存器实测容量，再据此在型号表里消歧（0x0415 / 0x0411 / 0x0416 / 0x0417 / 0x0441 这类多义 PID），覆盖 F0/F1/F2/F3/F4/F7/L0/L1/L4/L4+/L5/U5/G0/G4/H5/H7/C0/WB/WBA/WL
- **读保护判定**：由 GET_VERSION 的选项字节推断 L0/L1/L2；下载前自动解除写保护，擦除失败会自动解保护后重连再试一次；解除读保护（0x92）有二次确认，因为它会全片擦除且 Level 2 不可逆
- **擦除**：优先扩展擦除 0x44，不支持或失败则回退标准擦除 0x43（全局），擦完回读 256 字节应全为 0xFF。擦除 ACK 后 Bootloader 仍处于命令状态，**直接开始写入，不再复位/重进 Bootloader**（手动模式下复位还有掉出 Bootloader、从空 Flash 跑飞的风险）；只有解写保护（0x73）后芯片会自己复位，才重新进入并同步
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

### 局域网（主页 → 局域网）

发现本机所在网段里的设备，判断它们能干什么（共享文件、远程桌面、网页管理页），并把这些结论留在手机上。**扫描、共享、远程桌面合成这一个入口**：三者面向的是同一批设备，分开列会让人先想「我该点哪个」，而不是直接点那台机器。点条目即出全部操作。

- **四路并发发现**，各有不可替代的用途：
  - TCP 端口探测——结论最硬，但四个端口全被防火墙拦住的设备在它眼里等于不存在
  - UDP 137 广播（NBSTAT 通配查询）——一个包问遍整段，拿回机器名、MAC 与 NetBIOS 角色，是「TCP 看不见但机器还在说话」时最省的一条路
  - mDNS 浏览（NSD，16 种服务类型）——手机、音箱、电视、打印机、NAS 多数不开 SMB/RDP，基本只能靠它报上来；同一 IP 的多个服务先合并，再按「服务名有多像机器名」取最好的那个，TXT 里的 model 与广告端口也一并留下
  - SSDP/UPnP（M-SEARCH）——电视、音箱、打印机和不少 IoT 只回这一路；抓设备描述 XML 里的 friendlyName / modelName，路由器的 `InternetGatewayDevice` 直接标成网关
- **端口集三档**（菜单「端口集」，选项里直接写明代价）：精简＝135/139/445/3389（旧行为）；标准＝再加 22/80/443/8080；全服务＝在场 8 端口 + 对已应答地址补探 43 端口。分两级是因为耗时：在场端口要打到每个地址（254 × N 次连接），补探只对少数活跃设备做。补探表里的 5001/8081/8123/8920/9443 等常见服务与详扫表、端口标签保持同步，不留下「角色判断认这个端口、却没有预设去探」的死规则
- **网段按链路真实前缀**：/22、/23 也扫全，超过 1024 个地址则退回本机所在 /24 并在状态行写明。Wi-Fi 优先，USB 网卡与热点也能扫，菜单「选择本机网段」切换；蜂窝段刻意排除——扫运营商 NAT 段找不到自己的设备，只烧流量。默认网关单独标成「网关 / 路由器」，排在手动/收藏之后
- 排序稳定：手动 → 收藏 → 网关 → 有开放端口 → 仅名称；条目显示设备类型线索（SSDP/mDNS 报上来的型号与厂商优先，其次 NetBIOS 角色后缀 `<1C>` 域控 / `<1B|1D>` 主浏览器 / `<20>` 文件服务器，最后才是端口组合）。**猜不出来就不显示**，不做假确定的断言；条目还带首个成功端口的 TCP 握手耗时（如 `2 ms`），也可改按 IP / 名称 / 类型排序
- 名称按优先级合并：用户备注 > NetBIOS > mDNS > SSDP friendlyName > 反向 DNS PTR；Windows 的「主机名: 用户@域名:」与 AirPlay 的「MAC@设备名」会剥成可读名字。名称查询只对已确认开放端口的主机发起，不给空闲地址付 PTR 的等待时间
- **收藏与手动添加落盘**（`lan_devices`），跨扫描、跨重启保留；手动项接受 IP 或主机名，用于「只在内网做端口转发」「AP 隔离扫不到」这类设备。主机名在后台线程解析，解析失败会在状态行明确报「无法解析」，不再伪装成「端口全部关闭」
- **条目菜单里的凭据与列表管理**：「设置登录账号」把那台机器的用户名/域/密码存进 `lan_credentials`（共享页、远程桌面连接簿、扫描页三处共用同一份 `CredentialStore`，不各存一份）；已经存过的条目多出一行「删除已存凭据」，清掉后下次进共享页会重新问；「从设备列表移除」只对收藏与手动添加的条目出现（扫出来的设备本来就会随下一轮变化，没必要"移除"），移掉的是记录、不是设备
- **设备身份跟 MAC 走**：收藏记录 MAC 与 lastSeen，DHCP 换地址后按 MAC 把收藏和已存凭据迁到新 IP；同一个 IP 上的 MAC 变了会先确认再用旧凭据。当前轮没出现的收藏显示「上次在线 X」；基线建立后新出现的设备标「新」，不会每轮都提示。没有 MAC 的设备退回 IP+名称；手动项可以自己填 MAC
- **MAC 显示与来源**：条目固定显示 `MAC ...` 或 `MAC 未获取`。能拿到 MAC 的通道是 NetBIOS NBSTAT（这次修正了 DNS 头偏移、18 字节名称项，以及名称表之后统计区的 Unit ID）、mDNS TXT 的 `deviceid`/`mac`（AirPlay 常带 `deviceid`，实测音箱显示 `C8:BF:4C:XX:XX:XX`）、SSDP 描述 XML 的 MAC 字段，以及手动「设置 MAC」。Android 10+ 普通应用读不到 `/proc/net/arp`（真机 `run-as` 验证 `Permission denied`），所以默认关闭 NBNS 的 Windows 11 等设备仍显示未获取；应用不提供 root/Shizuku 读取通道，全量 MAC 只能手动设置或从路由器租约表导入
- 单台操作：「重新探测端口」按当前预设复探（会丢掉上一轮结论，设备睡一觉回来端口不会还写着开放）；复探复用同一个 `LanHost`，端口全关也记成「已探测」，NetBIOS 角色不会因为单播没答而消失；「详扫 1–1024 端口」并行复扫 + 常见高位端口，约三秒
- **「端口详情」列出全部开放端口**（列表行只显示前 6 个，余下折成 `+N`，详扫出二十几个端口时以前没有地方看全）；点单项复制 `ip:port`，也可以一次复制全部。一台设备同时开着多个网页端口时，菜单里的「网页入口」会把它们全列出来（HTTPS 优先），而不是只给第一个
- **识别设备**：对已开放端口做一次轻量指纹——HTTP/HTTPS 抓标题、Server 头与自签证书主题，SSH 读 banner，RTSP 发 OPTIONS，3389 复用 RDP 安全层探测。结论写进条目（如「小米路由器」）并弹完整明细。只在用户点这一项时发请求，不参与每轮扫描
- **Wake-on-LAN**：向全局广播和当前网段广播发 6×0xFF + 16×MAC 魔术包（端口 9 与 7）；有 MAC 的设备直接「唤醒」，没有的可以先「设置 MAC」。MAC 来自 NetBIOS、mDNS/SSDP 或手动填写，手动添加里也有 MAC 字段
- **搜索 / 筛选 / 排序 / 导出**：顶部搜索框按名称、IP、MAC、角色实时过滤；「筛选」有共享 / 远程桌面 / 网页 / 打印机 / 仅新设备，「排序」可选默认 / IP / 名称 / 类型，选择都会保留；「导出结果」生成 CSV（IP、名称、MAC、端口、类型、上次在线、是否新）走系统分享
- **自定义端口集**：端口集菜单除了三档预设，还能填逗号/空格分隔的端口（最多 64 个），持久化后直接用于整轮扫描和「重新探测端口」
- **mDNS 解析串行化**：部分机型上 NsdManager 不允许并发 resolve，失败还会静默丢设备；现在排队逐个解析、失败重试一次，合并同一 IP 的多个服务；补了 `_device-info`、`_ipp`、`_companion-link`、`_hap`、`_matter`、`_sftp-ssh` 等类型
- **收尾诊断**：统计 TCP 活跃、NBNS 应答、mDNS 解析与 SSDP 应答；TCP 有设备但名称三路全 0 时提示可能开了 AP 隔离/访客网络，全 0 提示检查网段，只有名称提示端口可能被防火墙拦住
- **进页面就自动扫**：不用再点一次「扫描」；再点同一按钮就是「停止」
- 「只看可用设备」过滤掉仅名称发现的条目；扫描中「扫描」按钮变「停止」
- 需要 `INTERNET`、`ACCESS_NETWORK_STATE`（读链路地址）与 `CHANGE_WIFI_MULTICAST_STATE`（SSDP 组播），都是安装即生效的普通权限。扫描会向网段内每个地址发 TCP SYN、一次 UDP 137 广播、mDNS 浏览与 SSDP M-SEARCH，是真实网络行为
- Windows 侧前提：目标机需启用「文件和打印机共享」（放行 TCP 445 与 UDP 137）并开启网络发现；公用网络配置文件下这些默认全被防火墙拦掉
- 真机实测（Mi 10 Pro，192.168.1.0/24）：全服务预设约 5 秒扫完 253 个地址，找出路由器（53/80/443/8080，旧版四个端口全关时根本不会出现）、NAS、Windows 电脑，以及两台一个端口都没开、只回 mDNS 的 AirPlay 音箱；详扫又在路由器上多找出一个 784 端口
- 真机回归（同一设备）：路由器的 3 个网页端口在菜单里全部列出（`https://192.168.1.1/`、`http://192.168.1.1/`、`http://192.168.1.1:8080/`）；「端口详情」显示 53/80/443/8080 四个端口并可复制；对手动添加的空地址详扫 1–1024 后行状态是「已探测，没有开放端口」而不是「还没探测」；手动主机名 `nosuchhost` 重探时状态行报「无法解析 nosuchhost」，原有状态不被清掉
- 本轮真机回归（同一设备，192.168.1.0/24）：网关识别把路由器标成「网关 / 路由器」，并用 SSDP 显示 `MiWiFi Router · Xiaomi`；5 台设备条目带 2–5 ms RTT；「识别设备」在路由器上读出 `HTTP :443 → 200 · nginx`、标题「小米路由器」和自签证书主题；WoL 对带 MAC 的设备返回发送成功，「设置 MAC」入口正常；筛选「有网页入口」只剩路由器/NAS，按名称排序、搜索 `router`、导出 CSV 都正常；自定义端口 `784,3389` 只探出路由器 784 与两台 Windows 的 3389；lastSeen 显示「上次在线 3天前」，基线外设备标「新」并在下一轮自动转为普通条目
- MAC 显示真机结果：AirPlay 音箱从 mDNS TXT `deviceid` 显示 `MAC C8:BF:4C:XX:XX:XX`；路由器、NAS 与两台 Windows 都显示 `MAC 未获取`——这台网络里的设备不回 NBSTAT，手机应用也读不到 `/proc/net/arp`。NBSTAT 解析用合成报文单独验证过：`workstation=PC-OFFICE`、`mac=A0:AD:9F:XX:XX:XX`、`dc=true`
- **没有做厂商识别（OUI）**：安卓上能拿到 MAC 的通道只有 NetBIOS、mDNS TXT、SSDP 描述和手动填写，很多设备一条都不回；`/proc/net/arp` 自 Android 10 起被 SELinux 拦住，做不成通用来源。为 Windows 设备背一张 Apple 就占 1593 条的前缀表换不来信息，所以类型判断改走实际拿得到的 SSDP/mDNS 服务类型、型号与 NetBIOS 角色
- 已知限制：AP 隔离（客户隔离）会把广播、mDNS 与 SSDP 一起吞掉，此时只剩 TCP 探测；全拦 TCP 又不发 mDNS/SSDP 的设备扫不到
- 本轮没能在真机上验证的：按 MAC 迁移（这台网络里的 Windows 不回应 NBSTAT 的 MAC，需要 Samba/开共享的设备才能走到）、同一 IP 换 MAC 时的凭据确认、AP 隔离诊断的三种触发（当前网络没有隔离）。code 路径已编译并通过界面入口验证

### 共享文件夹（点设备 → 查看共享文件，或局域网页菜单 → 共享文件夹）

浏览一台机器的 SMB 共享，把文件取到手机上，也能把手机上的文件放回去。

- 两个入口：从扫描列表点设备选「查看共享文件」（带着那台机器的地址进去）；或页内菜单「共享文件夹」（不带地址，先问连哪儿——最近连过的主机，或手输 `\\NAS\照片`、`smb://192.168.1.5/` 这类写法，会自动剥成主机名）
- 浏览：先列共享（`\\主机\` 下的列表来自 srvsvc `NetrShareEnum`）；**面包屑是一排可以直接点的圆角块**（`共享列表 › Users › …`，当前级高亮、长按复制这一层的 UNC），点目录逐级深入，长路径横向滚动而不是折行；每台机器记住最后停留的目录（恢复失败就退回共享列表）
- **目录列表有缓存**（按访问顺序保留最近 14 层，新鲜期 60 秒）：命中就立刻上屏、一个请求都不发；过期就先显示缓存再后台静默重取，只有一条细线表示在刷新，失败也不清空屏上内容。翻共享全是「进三层、退回去看别的、再进来」这种回头路，不缓存的话每次返回都要转一遍进度条，体感就成了「点一下等一下」
- **每个目录记住滚到的位置**，退回来接着看。缓存与滚动位置在换机器时整份清掉——缓存的键只有路径，不同机器上的同名路径会撞上
- 工具条把「过滤 / 排序 / 视图 / 刷新」从 overflow 菜单提到明面上：这几项是翻共享时每分钟都要用的，藏在三点菜单里等于每次都要先开菜单再找条目。排序胶囊直接显示当前方式（名称/大小/时间），不用打开菜单才知道自己按什么排的
- 过滤改成**就地展开的输入框**（展开即抢焦点并弹键盘），边打字边筛；收起时自动清掉关键字——留着的话条目会被看不见的条件筛掉，用户只觉得「文件怎么少了」
- **系统返回键 = 上一级**：在共享里翻了多层的时侯，返回先逐级退回上一层，退到「共享列表」那一层才真的退出到扫描页。列表正在读取时那一次返回会被吃掉（避免读一半换目录，回来对不上号）。真机验证：从 `E/#工具/GitHub加速神器/` 起连按，前三次分别停在 `E/#工具/`、`E/`、共享列表，第四次才回到扫描页
- **界面是 Compose 的**（`SmbBrowseActivity` 同名重写，Intent 契约与 `EXTRA_HOST_IP`/`EXTRA_HOST_NAME` 不变，`LanScanActivity` 的调用点没动）：列表 / 网格两种视图；标题栏与 overflow 菜单仍走系统 ActionBar，两套标题栏叠在一起只会让人分不清返回键归谁管
- 列表：**带隐藏属性的条目默认显示**（这个属性由服务端说了算，有的 NAS 会把整目录都标 hidden，默认藏起来等于整页文件消失；菜单里可以关掉）；单次最多取 2000 项并明确提示截断（共享里几万个条目的目录真实存在，全塞进列表只会卡死）；状态行带所在共享的剩余空间（根层问不出来就不显示，不写「剩余 0 B」像盘满了）
- 状态行的「少了东西」分三种原因说：选目录模式（只列文件夹）、关键字过滤、隐藏属性。混着说会把人指到错的开关上——真机上「电影」目录的 256 个文件被选目录模式滤掉，提示却写成「带隐藏属性」
- 状态行把两种「少掉了」**分开说**：`共 2 项 · 另有 2 项带隐藏属性，可在菜单里显示` 和 `共 1 项 · 未过滤时 4 项`。合成一句「未过滤时 N 项」会让人去清明明没填的搜索框（真机上看到过这个困惑）
- 文件条目右侧有 **⋮ 按钮**：长按能做的事（在应用内预览 / 下载 / 另存为 / 复制路径 / 属性 / 重命名 / 删除）明面上也要有一个入口，手机上没人知道条目可以长按；列表下方同时给一行提示「点条目预览 · 长按或点右侧 ⋮ 可下载、重命名、删除」
- 目录条目用一个和文件角标同尺寸的块画 ▸，左边缘才不会一格深一格浅；目录不取缩略图（列目录已经知道它是不是目录，多一次 SMB 读只为画个图标不值）
- **缩略图**：图片由 Coil 经自定义的 `smb://` `Fetcher` 直接取字节（不绕 HTTP 那一跳），视频首帧由回环 HTTP + `MediaMetadataRetriever` 抽帧；两者都落到 `cacheDir/smb_preview`（上限固定 128 MB，按最后使用时间淘汰，菜单「预览缓存」可看占用与一键清空；`cache_mb` 这个键有 getter 也有 `setCacheLimitMb`，但界面上没有任何地方调它，所以**改不了**，要动只能改 `SmbSettings.java:177` 的那个默认值）。缩略图开关在菜单里，关掉即回到「只看名字翻共享」，省流量也省掉一大串 SMB 读
- **应用内预览**：图片（双指缩放 / 拖动 / 双击 1×↔2.5×，左右翻页）、视频与音频（Media3 + 回环 HTTP，边取边播、可任意拖动）、PDF（系统 `PdfRenderer` 逐页渲染）、文本/日志/代码（只取前 512 KB，按 UTF-8 → GB18030 → Big5 严格试解并显示实际编码）。预览页把当前目录里所有能预览的项一次带走，所以能左右翻；只有停住的那一页会真的起播放器
- 预览不了的出口：菜单里保留「用其他应用打开」与「下载到本机」，播不动的 wmv/avi、加了密的 PDF 都有地方去；超过预览上限的文件会写清有多大、上限是多少
- **常用文件夹收藏**：在任意目录用菜单「收藏当前目录」一点加入，之后从「文件夹收藏…」或无地址入口的目标选择框里一点直达（目标框里收藏排在最近主机之前——它们是每天要开的那几个地方）。收藏可改名、删除；名字默认取路径末段，跳转时标题用最近主机记住的机器名而不是 IP
- **传输是进程级队列，页面退出也照跑**：任务表在 `TransferHub`（进程单例），前台服务 `SmbTransferService` 只负责让进程活着并把进度摆到通知栏（带「全部取消」）。每条约有独立进度、取消与失败原因，默认 2 条并发（1 条会让「传一批照片」纯串行，8 条在一条千兆链路上互相抢 SMB credit，2 条刚好掩盖往返延迟）；进度每 100 ms 才推一次界面。连接的寿命也由它管：有活跃任务的机器被「钉」住（`SmbHub` 引用计数 +1），Activity 退出只减少自己那一份
- **断点续传**：下载失败/取消后，任务上会出现「重试」，从**已经写入的字节**继续（`SmbClient.download(path, offset, …)` 用随机读句柄直接定位，不把前面的字节白读一遍）。续传起点只认任务自己记录的 `doneBytes`——MediaStore 的 pending 项查不到 SIZE（真机试出来的），提交前还会核对一次总字节数，对不上就报错而不是把坏文件当成功
- **已下载与最近打开**（主页独立入口）：列公共 `下载/工具箱` 里的文件（Android 10 以下退回私有下载目录）与最近预览过的共享文件（`smb_recent_files`，上限 20 条），点一条直接打开/继续看；**长按一条**给「分享 / 复制路径 / 删除」（删除连 MediaStore 记录一起清）；标题栏菜单里有**「清空最近打开」**，只清「最近打开」那一段的预览记录，不动已经下载下来的文件。顶部还有一段**未完成**：见下条。**已知问题**：≤ Android 9 退回私有目录那条路上，「复制路径」和「删除」仍然按公共 `下载/工具箱` 拼路径（`SmbDownloadsActivity.kt:321,343-345`），而文件其实在 `Android/data/<包名>/files/Download`——复制出来的路径不存在、删除必然失败，见「注意事项」的已知问题清单
- **传输不再阻断浏览**：可以边传边翻目录、看预览、继续往队列里加任务（原来一有传输点条目就被拦住）。写操作完成后会作废该目录的缓存，不会出现「改完名翻出去再回来还是旧名」
- **下载进度能活过进程被杀**：每条下载按 1.5 秒节流把「主机 + 远端路径 + 落点句柄 + 已写字节」写进 `UnfinishedStore`。划掉应用、系统回收进程之后重开，「已下载」页顶部的「未完成」会列出下到一半的东西与字节数，点「继续」从断点接上（实测 2.6 GB 的电影下到 655 MB 时杀进程，重开后续到完成，字节数与远端一致），点「放弃」则把半截文件删掉。落点句柄之所以能重建：Android 10+ 那个 `IS_PENDING=1` 的 MediaStore 条目本来就还在，只是别的看不见
- **传输速度**：jcifs 默认的 `rcv_buf_size`/`snd_buf_size` 只有 64 KB（`SmbConstants.DEFAULT_RCV_BUF_SIZE`），那同时就是 TCP 窗口——实测同一个 64 MB 文件下载 15.0 MB/s、上传 8.3 MB/s，而链路是 1200 Mbps。抬到 1 MB 后变成 **下载 49.6 MB/s、上传 17.4 MB/s**（Mi 10 Pro / Wi-Fi 6 RSSI -52 / Win11 虚机）。两条边界也一并试出来了：**显式给 `openInputStream`/`openOutputStream` 传 1 MB 会让服务端直接回 `STATUS_INVALID_PARAMETER`**（SMB2 下单次 READ 的大小取服务端协商值，WRITE 这个会话上限就是 64 KB），所以读写的每请求粒度保持库的默认，能调的只有套接字缓冲；上传要再快只能做并行写（把顺序流切块、多句柄按偏移写），暂未做。另外「允许 SMB1」时缓冲自动退回 64 KB——SMB1 且服务端没有 LARGE_READX 时 1 MB 的 READ 老设备会拒
- **从手机出发的上传**（对齐 LocalSend 那种「先选文件再挑落点」）：菜单「从手机选文件上传…」先多选文件、再进「选目录模式」；别的应用「分享到工具箱」也走同一条路（`SmbSendActivity` 是纯跳板：接住 `SEND`/`SEND_MULTIPLE` 的 URI，转给共享页，自己不建连接也不落盘）。选目录模式下列表只留目录、顶部一条「已选 N 个文件 · 上传到这里」的横幅；确认后整批入队，页面留在原地看着每条的进度
- **类型筛选**：工具条上的胶囊按 图片/视频/音频/PDF/文本 过滤（目录始终显示——目录是路）；**递归搜索**（菜单「搜索子目录…」）：SMB 没有搜索接口，只能逐目录列，所以给了三道闸（400 个目录 / 20000 个条目 / 50 条命中），结果标题里写明扫了多少目录
- **视频续播**：按「主机 + 路径」记播放位置，起播直接跳过去，看完或只看开头几秒不记；**PDF 首页缩略图**（`PdfRenderer` 渲第一页，那份字节缓存点开 PDF 时还能复用）；列表滚动时视频/PDF 缩略图延迟 250 ms 再取，划走的条目自动取消（否则每个视频缩略图都要起一次回环代理）
- **长按多选**：长按任意条目进入多选，再点其它条目累加（点已选的取消），工具条换成「已选 N 项 / 全选 / 下载 / 删除 / 取消」；只选了一项时多出一个 `⋯`，它就是原来的完整条目菜单（目录没有右侧 ⋮，改名之类的动作否则够不着）。全选只作用于**当前过滤后看得到的**条目；批量下载每文件一条任务进同一个队列；批量删除有一次确认，文案写清「含目录时里面的内容会一起没了」。返回键先退出多选而不是退到上一层
- **查看与播放**：图片 / 视频音频 / PDF / 文本/代码都在应用内看（Coil、Media3 + 回环 HTTP、`PdfRenderer`、编码探测）。播放器按局域网流调过参：回环代理每块 256 KB（64 KB 时每读一块就要等一个 SMB 往返，高码率片子拖动后容易卡一下）、短缓冲 + 大 back-buffer、**seek 走最近关键帧**（精确 seek 会把中间数据全读一遍，网络上是「拖一下卡几秒」）、音频焦点与拔耳机自动暂停、播放时保持亮屏、控制器 ±10 秒；预览页菜单里还有**字幕轨选择**（容器内嵌的字幕在 `currentTracks` 里，同目录同主名的 `.srt/.ass/.ssa/.vtt` 由播放页自己找出来、整份落到字节缓存后挂成 `SubtitleConfiguration`——Media3 不会替用户猜同名文件，不挂就没有）、**倍速 0.5×~2×**（翻页重建播放器也记得住）、**横屏/竖屏切换**（清单里声明了 configChanges，转屏不中断播放）。播不动时给两条出路——**换系统播放器**（`VideoView` → 厂商 `MediaPlayer`，用设备自带的解码器）或**用外部播放器打开**（把本机 HTTP 流地址交给 VLC/小米视频等，浏览页条目菜单里也有「用外部播放器直接播放」，不用先进预览页）。后者走 `SmbStreamService` 前台服务：代理必须活过本页面，否则播放器下一次 seek 就会被拒；通知里带「停止」，空闲 5 分钟也会自动收摊
- **复制 / 剪切**：条目菜单与多选工具条都有「复制到… / 移动到…」，选目标目录用的还是那个「只列文件夹」的选择器。复制走 jcifs 的服务端复制（SMB2 的 `FSCTL_SRV_COPYCHUNK`），移动优先走服务端改名——**都不把数据绕手机**，同一台 NAS 内搬 1.2 GB 实测约 6 秒；跨共享改名失败时退回「复制 + 删除」。重名一律自动避让（`(2)`），绝不覆盖
- **跨主机也能复制/移动**：选目标目录时菜单里多出「换一台机器作目标…」（只在复制/移动进来时出现——上传的源是手机里的文件，换机器没有意义），选完照常翻目录，横幅上补一句「源在 192.168.1.10 上：字节要经手机中转，比同一台机器内搬慢」。同机走上面那条服务端通道，跨机就是 `SmbCrossTransfer`：读一条随机句柄、写到对面，目录递归并逐级建目录，**移动是整棵子树搬完之后才删源**（中途取消或报错时源完好）。两台机器的连接都在任务体里现取现还，页面那份 `link` 早就跟着「换机器」切走了
- **压缩包不用先下载就能看**（`SmbZip`）：点 `.zip` 直接进解压页，列条目、进子层、勾选、解压到包所在的那个目录。为什么不下载：`ZipFile` 要本地文件，`ZipInputStream` 只能从头顺扫——列一个 4 GB 的包就等于先拉 4 GB；而 ZIP 的条目表在文件末尾，读那几十 KB（中央目录 + EOCD，超 4 GB 时再经 ZIP64 定位器）就列完了，之后只取被选中那条的字节。名字支持 UTF-8 与 GBK（Windows 打的包常不带 UTF-8 标志，硬按 UTF-8 解就是一串乱码），带 `..` 或绝对路径的条目**拒绝解出**（Zip Slip），重名照旧避让不覆盖。实测 412 B 的包（含中文文件名与二级目录）在真机上列出 3 项、解出的 `docs/readme.md` 内容与编码都对
- **相册一键增量备份到共享**（`SmbBackup`，菜单「备份相册到这里」）：查 MediaStore 的照片与视频，只把共享上还没有的那些传上去，确认框先报「共 N 项 / 约 X」再动手（实测这台机器是 8115 项 / 38.7 GB）。判据用**文件名**而不是大小是刻意的：上传中途断掉留下的半截文件如果按大小判「已存在」，就永远补不回来了；按名字判则会重传一份 `(2)`，多占点空间但数据是完整的。这是单向补档，不改也不删远端任何东西
- 文件：**下载默认落在公共「下载/工具箱」目录**（Android 10+ 走 MediaStore，不需要存储权限，文件管理器/相册/播放器能直接看到，卸载应用也不会带走；Android 9 及以下退回应用私有目录）、同名自动加 `(2)`、带回远端时间戳（仅私有目录那条路，MediaStore 的 DATE_MODIFIED 不可写）、「用其他应用打开」、「另存为…」用 SAF 直接写到目标位置（不落中间文件）；下完弹「打开 / 分享 / 取消」，提示里写明落点
- 写操作：上传手机文件到当前目录（SAF 选文件）、从手机批量上传并挑目标目录（见上一条）、新建文件夹、重命名、删除（删除有二次确认，文案明说「直接在共享上生效、撤不回来」）
- 传输面板与列目录是两条独立队列：拷几百兆的同时还能继续翻目录、开预览；单条任务取消会丢掉半截文件
- **兼容设置**：允许 SMB1（默认关）、强制签名、来宾回退、连接超时、缩略图开关、「实验：改用 SMBJ 读文件」，以及**强制 SMB3 传输加密**（服务端不支持时会直接连不上，所以默认关）。改完重建连接——协议版本与签名是协商时定下来的，会话建立后改属性不会重新谈
- 连接失败按原因归类提示：权限不足 / 账号密码或域不对 / 共享或文件不存在 / 超时 / 协议版本谈不拢，并附协议原文。两个 SMB 库措辞不同（jcifs 是 "Access is denied"，SMBJ 是 `STATUS_ACCESS_DENIED`），所以关键字集在 `SmbErrors` 里合并维护
- 凭据与扫描页共用一份：勾「记住密码」→ Keystore AES-256/GCM 加密后写 SharedPreferences；不勾 → 只在进程内存里。**页面之间靠这份内存传凭据，不经 Intent 带明文密码**；浏览页与预览页共用同一条已登录连接（`SmbHub` 按主机引用计数），所以进预览不会二次弹登录框
- 文件出口自建：`LocalFileProvider` 是一个手写的只读 provider（`content://<包名>.files/相对路径`，拒绝 `..` 并再校验 canonical path，`exported=false`，授权只随单次 Intent）。跨进程传 `file://` 从 API 24 起会直接抛 `FileUriExposedException`，这条路必须走 content URI
- 明文 HTTP 只放行 `127.0.0.1` / `localhost`（`res/xml/network_security_config.xml`），不开全局 `usesCleartextTraffic`：回环服务是播放器的必需通道，按域名放行比整应用放开干净得多
- 真机实测（Mi 10 Pro / Android 13，连 Windows 11 的 `E` 共享与飞牛 NAS）：共享列表、逐级目录、大小与时间、类型角标、点击与长按菜单均跑通；**这一轮抓到并修掉四个只有真机会暴露的问题**——① 连接成功后那次列目录被自己的 `loading` 保护挡死，界面永远停在「正在读取…」；② 共享根的 `getAttributes()` 返回 0（没有目录位），共享被当成文件，点它弹菜单而不是进去；③ 根层 `getDiskFreeSpace()` 返回 0 而非「不知道」，状态行显示「剩余 0 B」；④ `clickable` 之后再叠一个只管长按的 `pointerInput` 会吃掉 down 事件，整行点不动，改用 `combinedClickable`。jcifs 的连接建立与关闭都必须在工作线程（主线程直接 `NetworkOnMainThreadException`）
- 尚未在真机上跑到的部分（下次连上设备优先补这四项）：Coil 缩略图的实际出图、预览页四种类型、网格视图、SMBJ 引擎切换。代码路径与 API 签名都已按库源码核对并通过编译，但**没有真机结论**

### 远程桌面 · 连接簿与导出（点设备 → 远程连接，或局域网页菜单 → 远程桌面连接簿）

连接本身走内置客户端（见下一节），这里只剩「记住目标」和「带得走的连接信息」。

- **连接簿**：名称 / 地址 / 端口，重复目标会被挡下；扫描页可以「存到远程桌面连接簿」；标题栏菜单里的**显示设置**决定向服务端要的桌面尺寸（预设或「适配屏幕分辨率」= 按手机像素 1:1）；它和会话工具栏的「分辨率」是同一个对话框、同一份存储，会话里改还能当场生效。账号密码不在连接簿里，仍归 `CredentialStore` 按主机存，同一份密码不在两处落盘
- **复制链接**：`freerdp://<用户>@<IP>:<端口>/connect`（域走 `?d=<域>`）只作为可粘贴的文本，交给别的设备或别的客户端用。**密码故意不进 URI**（会进对端进程命令行、也可能落日志）
- **端口连通测试**：TCP 握手 + 往返耗时。结论写在状态行而不是只弹 toast——实测 MIUI 13 会把连续第二条 toast 直接吞掉
- **安全层探测**（`RdpProbe`）：发一次 X.224 Connection Request 问服务端能用哪种安全层，问两遍（只请求 TLS、再请求 TLS|HYBRID|HYBRID_EX）才能区分「允许 TLS」和「强制 NLA」——只问后者永远只会拿到权限最高的那个。结论直接决定内置客户端要不要实现 CredSSP + NTLM，而这一点光看 3389 开不开是问不出来的。报文的 19 字节 client hello 是从本机 mstsc 抓下来的真实包，失败码表按 FreeRDP `libfreerdp/core/nego.h` 抄：`0x1 SSL_REQUIRED_BY_SERVER / 0x2 SSL_NOT_ALLOWED_BY_SERVER / 0x3 SSL_CERT_NOT_ON_SERVER / 0x4 INCONSISTENT_FLAGS / 0x5 HYBRID_REQUIRED_BY_SERVER / 0x6 SSL_WITH_USER_AUTH_REQUIRED_BY_SERVER`。解析时 RDP_NEG 数据在第 11 字节、值在第 15 字节——少算 4 字节会把 length 字段当成 failureCode 读出一个看似合理的错值（踩过一次）
- **导出 `.rdp`**：RDP 6 的「键:类型:值」逐行文本（CRLF、UTF-8 无 BOM，与 mstsc 习惯读的 ANSI 对 ASCII 内容字节一致），经分享出口发出去，在电脑上双击即可连接。**不写 `password 51:b:` 字段**——那只是可逆编码，等于把密码留在文件里
- 真机实测：对开着 3389 的 Windows 机器握手成功、往返 33 ms；导出的 `.rdp` 内容逐行核对正确；分享面板能选到网盘与文件管理器

### 远程桌面 · 内置通道（freerdpcore/ + FreeRDP 官方 Android 客户端）

不装任何外部客户端，在应用里连、在应用里看画面、在应用里操作。2.x 换的做法是：**协议层、会话界面与控制逻辑都直接用 FreeRDP 官方 Android 客户端**（FreeRDP 3.28.0，`client/Android/Studio/freeRDPCore`），而不是像 1.x 那样自绘会话界面。真机上已连上强制 NLA 的 Windows 11：点开始按钮能弹出 Windows 菜单、键盘能在 Windows 搜索框里打出文字（见「已验证」）。

- **组装方式**：`freerdpcore/` 是从上游 `freeRDPCore` 裁出来的库模块——保留 `SessionActivity` / `SessionView` / `SessionInputManager` / `TouchPointerView` / `LibFreeRDP`（JNI）这些会话界面与控制逻辑，删掉基于 Room + SQLCipher 的 aFreeRDP 主页与连接簿（连接簿仍用工具箱自己的）；原生层就是上游的 `android_*.c`，编成 `libfreerdp-android.so`
- **设置走 FreeRDP 自己的解析器**：工具箱的 `RdpSessionActivity` 只做三件事——规范化地址、取凭据（缺了先弹框）、把参数拼成 `BookmarkBase`；`LibFreeRDP` 再把书签翻成命令行交给原生端 `freerdp_parse_arguments`，不是我们逐项 set settings。实际发出去的那一串（按 `LibFreeRDP.java` 的组装顺序）：
  - **每次都发**：`/gdi:sw`（软件 GDI，:323）、`/v:`（IPv6 会自动补方括号，:341）、`/port:`、`/u:` `/d:` `/p:`（有值才发）、`/size:WxH`、`/bpp:`、`/scale:`（:399，缩放预设恒为 100，没有 UI 改它）、`/clipboard`、`/disp`（:490-491，无条件——会话内改分辨率靠它）、`/kbd:unicode:on`、`/cert:ignore`、`/log-level:`（取自上游 `DebugSettings` 的默认级别）
  - **按工具箱的书签取值才发**：`/rfx` + `/network:auto`（:439，`RdpSettings.lossyCodecs()` 为真时）、`/gfx` + `/network:auto`（:445，工具箱固定 `setGfx(true)`，同时 `setH264(false)` 所以不会出现 `/gfx:AVC444`——openh264 没编）、`/audio-mode:0` + `/sound`（:526-529，`setRedirectSound(0)` 即声音播在手机上）
  - **工具箱里永远发不出去**（条件项，但对应的开关没人能打开）：`/sec:nla|tls|rdp`（:416-425，`BookmarkBase` 的 `security` 默认 0 = 让 FreeRDP 自己谈，全仓没有 `setSecurity` 调用点）、`/tls:`（:369，同样没设置）、`/cert-name:`（:433，`certName` 写死空串）、`/client-hostname:`（:328，默认偏好是空串）、`/drive:sdcard,`（:482，`setRedirectSDCard(false)`）、`/printer:`（:494，`setRedirectPrinter(false)`，而且 `CHANNEL_PRINT_CLIENT=OFF`，加了也静默无效）、`/microphone`（:534）、`/dvc:rdpecam`（:539）、`/app:program:`（RemoteApp）、`/admin`、`/vmconnect:`、`/load-balance-info:`（网关）
  - **`/cert:ignore` 是硬编码的**（:543），不是设置项。后果：`SessionActivity.OnVerifiyCertificateEx` / `OnVerifyChangedCertificateEx`（:1118-1134）、`SessionDialogs.verifyCertificate/verifyChangedCertificate`（:180,202）、以及 `settings_app_security.xml` 里那个「接受所有证书」的偏好，**全都是走不到的死路**——自签名证书直接连、不发提示，中间人也没有任何提示。想按上游那样问一次，得把这行改成读偏好（`getAcceptAllCertificates` 默认已经是 `false`，改完默认就会弹）
- **会话页上的工具箱定制**（在 FreeRDP 会话界面之上加的一层）：左上角一排**半透明文字按钮**——收起、鼠标/触控（指针模式）、分辨率、键盘、信息、横屏/竖屏、全屏/退出全屏、断开；切换类的文字一律写「点下去会切到什么」，选择都会记住。**「收起」后只剩一枚 ☰ 把手贴右边缘垂直居中**（半透明，点一下回顶部成一排），把整块屏幕让给桌面；**长按 500 ms 可以拖着整条走**（收起后的把手也能拖），松手吸附到最近的一侧，收起/展开时按新宽度重新贴边。按钮样式与键盘、剪贴板、声音这些是 FreeRDP 的，但**手势与缩放这一层是工具箱自己的实现**（从 1.x 的会话页搬过来），见下两条。「信息」弹窗显示：主机端口、桌面尺寸与色深、**当前缩放与指针模式**、**协议（RDP 版本 / NLA / TLS / RemoteFX+RDPGFX / UDP 多传输状态）**、**帧率与流量（速率 + 会话内累计）**、引擎版本、客户端版本、已连接时长，以及启动前测的 TCP 握手耗时。向服务端请求的桌面尺寸在**分辨率**那一项里选（默认 1920×1080，也可以「适配屏幕分辨率」按手机像素要）
- **缩放只有等比放大缩小，不拉伸**：基数永远是 `min(视口/画面)` **整幅放下**（横竖屏都一样，画面一定完整，可能留黑边——宁可留黑边也不裁掉任务栏）；倍率 1×~4× 由捏合改，基数在可视区变化时重算（全屏、转屏、软键盘都算），转屏时只动基数、用户的放大倍数保留，焦点下那个远端像素在捏合时不挪位；已经放大时双击回到 1×（没放大时不拦双击——桌面图标全靠双击打开）。画面**照 1.x 的方式跟手**：光标一进边缘带（15% 或 64dp）就把画面推到位，光标随即停在带子边上，画面继续跟着手指滑——边滑边走。**松手时不补动画**（1.x 的对准只在捏合/双击缩放、转屏、换分辨率后做；每次松手都滑一下就是顿挫）。滚动量统一是「现在位置 − 想要位置」（写成 `低 − 位置` 方向就反了，光标看着像在倒退），横竖轴各算各的。判据是「画面比视口大」且按轴判，内容尺寸按 `位图 × 缩放` 算而不是读 `getWidth()`（刚改完缩放时布局还没跑，读旧值就会算错），对不准的那一帧延到下一帧再做（可滚动范围随内容尺寸变）。手感只有一个旋钮 `FOLLOW_GAIN`（现为 1.0 = 1.x 原样）。画面超出视口时单指拖动让给 `ScrollView2D` 本地平移
- **不开全屏也不给状态栏挖白**：会话根布局不再按 `statusBars | displayCutout` 的 top 留 padding——MIUI 把刘海也算进 top，实测横屏留 194 px、竖屏留 211 px，而系统真正让给状态栏只有 35 px，多出来的就是一块没人用的黑。现在桌面铺到屏幕顶、半透明状态栏压在上面，工具栏自己按 `statusBars().top` 往下让
- **想要零黑边靠分辨率对上比例，不靠拽变形**：会话工具栏的**分辨率**（或连接簿 → 显示设置，两处是同一个对话框、同一份存储 `SessionResolution`）——☑「适配屏幕分辨率」按手机此刻的实际像素要桌面，或选预设（1920×1080 / 1600×900 / 1366×768 / 1280×800 / 1024×768）。
- **改分辨率不用重连**：会话内选完点「应用」直接发 MS-RDPEDISP 的 Monitor Layout PDU（`/disp` 通道，`LibFreeRDP.sendMonitorLayout()`），桌面当场重排。1.x 认定「这台 Windows 完全不理这个 PDU」，那是 IronRDP 的发法；FreeRDP 实测有效。应用后会等 1.8 s 比对新位图尺寸再给结论，服务端真不认时提示「下次连接按它要桌面」而不是假装成功
- **两种指针模式**（工具栏一键切，选择会记住）：**触控**=点哪儿打哪儿、单指滑动=远端滚轮（带甩动惯性）、按住松手=右键、按住后再动=拖拽；**鼠标**=整块画面当触摸板推远端光标（sqrt 加速曲线：慢 0.75×、1 px/ms 1.5×、4 px/ms 封顶 2.25×）、轻点=左键、轻点后按住=tap-and-a-half 立即拖拽、按住 600 ms 松手=右键；画面铺不满时**四周黑边也归触摸板**（黑边属于 `ScrollView2D`，事件由它的 `OnTouchListener` 转给 `SessionView.handleExternalTouch()`，触摸板只吃相对位移所以不用换算坐标；直控模式不接，黑边上的拖动仍是本地平移）。两指轻点=右键、三指轻点=中键、双指滑动=横/纵滚轮（30 dp 一格）、捏合=缩放
- **光标画在远端画面里**：FreeRDP 把 `colorPointerFlag` 写死为 TRUE，服务端因此从不把光标烤进帧缓冲、也从不回传光标坐标。所以 `SessionView.onDraw()` 画完位图后自己把服务端下发的光标位图合成在画面内，坐标取自我们发出去的事件（`SessionInputManager.trackPointer()`——左/右/中键、拖动、悬停移动都记一次）；光标最小按原始尺寸画（画面缩到 0.56× 时跟着缩就只剩 18 px），服务端要隐藏光标时退回默认箭头而不是画没了，本地 `PointerIcon` 全程 `TYPE_NULL`，外接鼠标也不会出现两个光标
- **工具栏长按拖动的两个坑**：①它外面套着 `HorizontalScrollView`，长按刚到手一动，父级的 `onInterceptTouchEvent` 就把手势抢走并丢一个 `CANCEL` 下来，拖动永远进不去（实测 DOWN → armed → 立刻 CANCEL、一个 MOVE 都没有）——按下时 `requestDisallowInterceptTouchEvent(true)`；②落位只写 `leftMargin` 不够，之前调过 `setMarginStart()` 的话 RelativeLayout 取的是 start 边距，横向吸附会停在 x=0，所以 start/left 要一起写
- **UDP 传输用不了（不是配置问题）**：`libfreerdp/core/multitransport.c` 里客户端侧的处理器就叫 `multitransport_no_udp()`——服务端发 UDP 引导请求时直接回 `E_ABORT`；`RDPUDP` 只有服务端实现。`/multitransport` 开关只是把标志写进能力集，通道建不起来。所以信息面板的传输方式永远显示 `TCP`，并注明「服务端支持 UDP，FreeRDP 客户端未实现」。mstsc 里那个 UDP 是微软客户端自己实现了 MS-RDPEMT 的数据面
- **信息面板的数据来源**：协议与安全是原生层新增的 `freerdp_get_protocol_info()` 读 `instance->context->settings` 拿到的**协商结果**（不是我们请求了什么，而是谈成了什么）。帧率按每秒 `OnGraphicsUpdate` 的次数算，桌面静止时就是 0。**流量不走 `freerdp_get_stats`**——它的入参是内部的 `const rdpRdp*`，而 3.28 里 `freerdp->settings`、`freerdp->rdp` 已不是公开成员（编译直接报 no member），所以用 `TrafficStats.getUidRxBytes`，以连接建立时的值为基线只报会话期间的量；这台机不支持时明说「本机不支持流量统计」而不是显示 0
- **控制逻辑其余部分仍是 FreeRDP 的**：硬件键盘走 `KeyboardMapper` 转扫描码、剪贴板（cliprdr 文本 + 图片双向）、声音（RDPSND + OpenSL ES）、GFX 通道，都是上游实现。**从 1.x 接回来的只有输入手感那一层**：指针加速曲线、触摸板式推光标、软跟随（`SessionView` 的单指状态机 + `FOLLOW_GAIN`）。
- **两处上游界面现在够不着**（裁剪留下的口子，如实记着）：① **扩展键面板没有入口**——`SessionInputManager.toggleExtendedKeyboard()` 唯一的调用点是 `onTouchPointerToggleExtKeyboard()`（:513），而那个 `TouchPointerView` 在 `SessionActivity.java:307` 被永久 `setVisibility(GONE)`（远端光标已改由 `SessionView` 画进画面）。所以 Fn / 小键盘 / 方向键 / 特殊键那几块 `res/xml/*_keyboard.xml` 面板代码和资源都在、点不到，工具栏的「键盘」只开系统键盘。② **应用设置页整体不可达**——`ApplicationSettingsActivity` 没在任何 manifest 里声明（`freerdpcore/src/main/AndroidManifest.xml` 只有 `SessionActivity` 与 `ClipboardImageProvider`），而 `SessionDialogs.java:119→230` 会 `startActivity` 它：真走到「实验特性」拦截那一步（`SessionActivity.java:1158`）就是一个 `ActivityNotFoundException`。被读却没人能改的偏好有 `clientName`、`hideStatusBar/hideNavigationBar`、`keepScreenOnWhenConnected`、`acceptAllCertificates`、`swapMouseButtons`、`invertScrolling`、`useBackAsAltf4`、`nightMode`、`disconnectTimeout` 与 `experimental.*`——**指针模式和桌面分辨率是例外**，它们有自己的对话框（工具栏 + 连接簿 → 显示设置），存在同一份偏好文件里
- **构建**：`third_party/build-android.sh` 交叉编译 FreeRDP + OpenSSL 静态库（源码按固定版本拉取、SHA-256 校验）；`:freerdpcore` 的 `externalNativeBuild` 把 `android_*.c` 连同静态库编成 `libfreerdp-android.so`，按 `abiFilters`（`arm64-v8a` + `armeabi-v7a`）进 APK。1.x 的 Rust/`librdpcore.so` 这条线**源码已删**（`RdpNative.java` 与 `rdpcore/` 都不在了），但本机还留着当时的编译产物 `build-shim/arm64`、`build-shim/armv7`（约 16 MB 的 `.so` + `CMakeCache.txt`/`build.ninja`），已加进 `.gitignore` 不入库；`third_party/build-android.sh:7,44` 与 `freerdpcore/src/main/cpp/CMakeLists.txt:4` 的注释里还在讲「链进 `librdpcore.so`」，那是历史注释，读到时知道它不参与本次构建就行
- **必须保留 rail 通道**：`android_rail.c` 直接引用 rail 客户端入口（RemoteApp 窗口），构建脚本里 `CHANNEL_RAIL_CLIENT=ON`
- **JNI_OnLoad 要合并**：`android_freerdp.c` 与 WinPR 的 `android.c` 各有一个 `JNI_OnLoad`（后者缓存 JavaVM 给 `winpr_jni_attach_thread` 用），静态链接必然重名；链接放行后由我们的 `JNI_OnLoad` 同时写两份缓存
- **`LibFreeRDP` 只加载一个 .so**：上游 APK 里 `freerdp3` / `freerdp-client3` / `winpr3` 是分开的共享库，本工程把它们全部静态链进 `libfreerdp-android.so`，所以静态块里只保留 `System.loadLibrary("freerdp-android")`
- **1.x 认为「安卓上交叉编译 OpenSSL 必死」，这次为什么能通**（细节见 `third_party/README.md`）：缺的其实只是 GNU make 与几个 perl 核心模块——GNU make 用 NDK 自带的那个，精简 perl 缺的 `Locale::Maketext` / `ExtUtils::MakeMaker` / `Pod::*` 补进 `third_party/perl-lib`；OpenSSL 用 `no-asm no-shared` 交叉编译。另外几个真机/静态链接坑：OpenSSL 3 把 MD4/RC4 挪进 legacy provider（按官方 Android 构建开 `WITH_INTERNAL_MD4/RC4/MD5`）；32 位 ARM 的 `__thread` 在 ThinLTO 下 `-femulated-tls` 失效（关 LTO）；安卓没有 `HOME`（start 时指到应用私有目录）
- **IPv6 也能连**：地址栏收 `fe80::1` 或 `[fe80::1]`（方括号在入库时去掉）；拼 `host:port` 时按协议补成 `[v6]:port`，导出 `.rdp` 与复制 `freerdp://` 链接同理。注意 FreeRDP 的 `/v:` 解析器只认 `[地址]` 形式，裸 IPv6 会被当成 `host:port` 判成非法参数——原生层在交给 FreeRDP 前会自动补方括号
- **公网设备 / 域名 / DDNS**：启动器会先把域名解析成全部地址（IPv4/IPv6 都算），逐个做一次 TCP 试连（每条 1.5 s，最多 4 条），把**实测可达的那个地址**交给 FreeRDP——DDNS 换了地址、或域名同时有 A/AAAA 而其中一条不可达时，FreeRDP 自己解析可能撞上旧记录（真机实测报 `No route to host`）。信息面板会显示「域名 → 实际 IP」
- **域名也能连**：非字面量地址先在本进程解析（优先 IPv4），按域名连时如果域名下没有账号，会回退去查解析出来的 IP 那一条，不至于因为写法不同再输一次密码
- **会话页的旋转与全屏**：FreeRDP 的会话 Activity 自己不锁方向、声明了 `configChanges`；全屏/沉浸由它的界面处理。会话内的「全屏」开关（`forceFullscreen`）要同时参与 `onWindowInsetsChanged()` 的留白计算——只在 `hideSystemBars()` 里藏系统栏不够，留白还按设置项留着，画面就永远撑不满
- 已验证（Mi 10 Pro → 192.168.1.100，Windows 11，强制 NLA，凭据来自连接簿）：`已连通`、画面颜色正确；**点任务栏开始按钮能弹出 Windows 搜索菜单**；**硬件键盘输入能在 Windows 搜索框打出 `not`**；声音通道 RDPSND 建立、OpenSL ES 后端加载；退出会话无崩溃；竖屏缩放 0.5625、点一下后白色描边箭头出现在画面内的点击处（服务端发过 `OnPointerSetNull` 之后仍在）；全屏 + 横屏后 `sessionScrollView = 0,0-2340,1080`、`sessionView = 0,0-2340,1316`（横向零黑边）；空闲时不再刷 `onMeasure`（修复 `applyFitZoom ↔ requestLayout` 死循环前 4 s 内 1524 次）；**触控模式按住 900 ms 松手能弹出 Win11 桌面右键菜单**、轻点关掉它、竖屏单指上滑能滚信息流；**切鼠标模式后同一段滑动改为推光标**、在画面内点一下落在光标所在位置而不是手指位置；重开会话仍是上次选的指针模式；横屏整幅放下 `sessionView = 1512×851`（整张桌面含任务栏都在眼里）；勾上「适配屏幕分辨率」后信息面板显示 `桌面：1080×2340`、`sessionView = 966×2094`——竖屏 1:1 铺满、零黑边、不变形；**会话内**改 1920×1080 → 1280×800 桌面当场重排（`sessionView 1512×851 → 1361×851`），再切「适配 + 全屏」得到 `sessionView = 2340×1080` 与屏幕完全 1:1；**信息面板实测**输出 `协议：RDP 10.12 · NLA + TLS · RemoteFX + RDPGFX · UDP 可协商未启用`、`帧率：18 fps · 流量：216.3 KB/s ↓ · 会话内 22.8 MB`（视频播放时），桌面静止时帧率与速率归 0 而累计量仍在涨；**「收起」后界面上只剩 (2249, 540) 一枚把手**，点把手八个按钮回到顶部一排
- **未验证/边界**：声音是否真的出声（没耳测）、剪贴板双向、长时间稳定性；**双指捏合、双指滚动、双指轻点右键、三指轻点中键没机器验证过**（`adb shell input` 注入不了多点触控），外接鼠标/触摸板下的手感同理；其他机型的刘海/挖孔留白组合；画质档位（省流/均衡/清晰）还没有设置入口，现在固定取 `RdpSettings` 的默认「均衡」，`PRESET_SHARP` 那档根本到不了；**「适配屏幕分辨率」开着时旋转手机，桌面尺寸不会跟着换**——原因不是"重建会话要回到应用侧"，而是**那个分支的判断条件恒为假**：`SessionActivity.java:481-489` 只认 `getActiveScreenSettings().isFitScreen()`，而启动器一律走 `screen.setResolution("custom", 宽, 高)`（`RdpSessionActivity.java:75`，把适配后的手机像素当自定义尺寸传进去），`isFitScreen()` 因此永远是 `false`。修法有两条且都不必重连——adaptive 时改传 `"fitscreen"`，或者在 `onConfigurationChanged` 里改判 `SessionResolution.isAdaptive(SessionResolution.load(...))`；反正 MS-RDPEDISP 当场生效已经实测过（见上一条）
- **测试环境提醒**：这台手机上有一个会注入 `KEYCODE_BACK` 的辅助/自动化应用（logcat 里是 `Input event injection from pid 941`），会在大约几秒后把 FreeRDP 会话关掉，看起来像「远程桌面自己退出」——与界面无关，测试时先关掉这类自动化

## 依赖

| 库 | 许可 | 用途 |
| --- | --- | --- |
| `FreeRDP` 3.28.0（`third_party/` 交叉编译成静态库；`freerdpcore/` 是它官方 Android 客户端 core 的裁剪） | Apache-2.0；Android 客户端源码 MPL-2.0 | 内置远程桌面的协议层与会话界面（NLA/CredSSP、RDPSND、WinPR、触摸指针/键盘/剪贴板） |
| `OpenSSL` 3.5.9 LTS（静态、`no-asm`） | Apache-2.0 | FreeRDP 的 TLS/CredSSP 加密后端 |
| `com.github.mik3y:usb-serial-for-android:3.9.0`（JitPack） | MIT | 「固件下载」的 USB 转串口通信 |
| `org.codelibs:jcifs:3.0.4`（jcifs-ng，Central/阿里云镜像） | **LGPL-2.1** | 「局域网扫描」的 SMB 客户端，默认引擎 |
| `com.hierynomus:smbj:0.15.0` | Apache-2.0 | SMB 读文件的可选实验引擎（见「共享文件夹 · 预览」） |
| `androidx.compose.*`（BOM 2026.09.00）+ `androidx.activity:activity-compose:1.13.0` + `androidx.compose.material:material-icons-core` | Apache-2.0 | 共享那条线上的四页 Compose 界面；`ui-tooling` 只在 debug 变体里引 |
| `io.coil-kt.coil3:coil-compose:3.6.3` | Apache-2.0 | 缩略图与全屏图的加载、内存缓存 |
| `androidx.media3:media3-exoplayer / media3-ui:1.11.1` | Apache-2.0 | 视频与音频播放 |
| `androidx.appcompat:appcompat:1.7.1`、`androidx.preference:preference:1.2.1`、`androidx.recyclerview:recyclerview:1.4.0`、`androidx.lifecycle:lifecycle-viewmodel / lifecycle-livedata:2.10.0`（`freerdpcore/build.gradle:41-45`） | Apache-2.0 | **上游会话界面自带**：`SessionActivity` 继承 AppCompatActivity、`ApplicationSettingsActivity` 用 preference 框架、键盘面板用 RecyclerView、会话状态用 ViewModel/LiveData。这五个是 `freerdpcore/` 带来的，不是工具箱自己加的 |
| `com.android.tools:desugar_jdk_libs:2.1.5`（`coreLibraryDesugaring`） | **GPLv2 + Classpath Exception**（按它 POM 里 `<licenses>` 的声明；旧版文档误写作「Android SDK」） | 核心库脱糖（只为 SMBJ 的 `java.time` 引用兜底）。Classpath Exception 就是允许与独立作品一起链接分发的例外，应用这样用是它设计出来的用法，但**许可名要写对** |
| `org.jetbrains.kotlin:kotlin-stdlib`（Kotlin/Compose 编译器 2.4.21 带入） | Apache-2.0 | 那 30 个 Kotlin 文件的运行时 |

**间接依赖**（不写在 `build.gradle` 里，但确实在 APK 中，许可也一并按各自 POM 核对过）：

| 库 | 许可 | 谁带进来的 |
| --- | --- | --- |
| `org.slf4j:slf4j-api` | MIT | jcifs-ng 与 SMBJ 都用它打日志（安卓上没有 SLF4J 后端，等于静默）。版本由这两个库的 POM 传递决定，本工程未钉 |
| `org.bouncycastle:bcprov-jdk18on` | **Bouncy Castle Licence**（MIT 同许可风格，不是字面 MIT） | NTLMv2 / SMB3 加密原语，`SmbClient` 构造时 `Security.addProvider` 注册。同样是传递依赖 |
| `net.engio:mbassador`（1.3.2） | MIT | SMBJ 的事件总线 |
| `com.hierynomus:asn-one`（0.6.0） | Apache-2.0 | SMB3 的 DER 编解码 |

> 这张表的许可逐个读的是各自 POM 里的 `<licenses>` 声明（`~/.gradle/caches/modules-2/.../*.pom`），不是照抄 README 的旧值——`desugar_jdk_libs` 那行就是靠这个抓出来写错的。


安卓内核没有 CH340/CH341 驱动，usb-serial-for-android 在应用层实现了这些芯片的初始化、收发与 DTR/RTS 控制，因此用户不需要另装任何驱动 App；jcifs-ng 则补上了安卓没有 SMB 客户端协议栈的缺口。

**为什么默认还是 jcifs-ng、SMBJ 只作可选引擎**：列共享这一步只有 jcifs-ng 实现了。`\\主机\` 下的共享列表来自 srvsvc 的 `NetrShareEnum`（DCERPC over named pipe），SMBJ 到 0.15.0 仍然只有 `connectShare(名字)`（在 jar 里 grep 过，确实没有 srvsvc），共享名得手输，做不到「打开就看到列表」。已实测的取舍：

- 包名已重定位为 `org.codelibs.jcifs`，与老 jcifs 不冲突；compile 依赖只有 `slf4j-api` 与 `bcprov-jdk18on`
- `bcprov-jdk18on` 提供 NTLMv2/SMB3 的加密原语，Android 没有预置这个 Provider，运行时由 `SmbClient` 构造时调 `Security.addProvider` 注册
- 实测 jar 内不含 `java.time` / `java.util.function` / `java.nio` 引用，minSdk 24 上 jcifs 本身不需要脱糖
- 共享页用到的写操作与流式接口都按 3.0.4 的源码逐个核对过方法名与语义：`mkdir`/`delete`/`renameTo`/`getDiskFreeSpace`/`openInputStream`/`openOutputStream`/`exists`/`canWrite` 都在 `SmbResource` 上；`minVersion`/`maxVersion` 两个属性读的是**枚举名**（`SMB1`、`SMB202`、`SMB311`），写成 `"2.02"` 会在构造配置时直接抛 `IllegalArgumentException`；随机读用 `SmbResource.openRandomAccess("r")` → `SmbRandomAccess.seek/read/length`
- LGPL 合规：作为独立动态链接的库使用，用户可自行替换；需要源码按坐标 `org.codelibs:jcifs:3.0.4` 获取即可
- **SMBJ 的两条硬限制**（界面上「实验」二字就是这么来的）：① 不支持 SMB1——`SMB2Dialect` 最低只有 `SMB_2_0_2`，开着「允许 SMB1」的老设备切过去会连不上；② 列不出共享，所以即使选了 SMBJ，共享与目录枚举仍走 jcifs-ng，SMBJ 只承担 `File.read(buffer, offset, ...)` 这一段文件字节读取。它的 `java.time` 引用只在 `msdtyp.FileTime`、`java.nio.file` 只在可选的 `smbfs` 包里，工程为此开了核心库脱糖，API 24/25 才不会在加载这些类时出问题

**其余约定**：

- 包体积：debug 包 1.19 MB（无 SMB）→ 5.73 MB（加 jcifs-ng）→ 23.6 MB（再加 Compose + Coil + Media3 + SMBJ + 脱糖）。这是 debug 包（未混淆未裁剪）的数字，release 想压回去得开 `minifyEnabled` + R8 规则；共享预览这一整块功能就是这次的体积代价
- `android.useAndroidX=true`：Coil / Media3 / Compose 都建立在 AndroidX 上，`freerdpcore/` 也带着 `appcompat`（会话页继承 AppCompatActivity），所以这个开关无法回避。**但工具箱自己的页面里，只有共享那条线的四页（浏览、预览、解压、已下载）在用 AndroidX**；分贝仪、域名解析、固件下载、局域网扫描、远程桌面连接簿与启动器这几页仍然只用系统控件（`ListView` / `AlertDialog`），一行 androidx 都没 import。唯一的历史痕迹是 `IspActivity.java:584` 那句注释——它写着"本工程刻意不引入 AndroidX"，那是 1.x 的情况，现在这话对本工程不成立（对本页倒是成立的），列入下面的已知问题等下一轮顺手改掉
- `settings.gradle` 增加了 `maven { url 'https://jitpack.io' }` 并用 `content { includeGroup 'com.github.mik3y' }` 限定只从它取这一个坐标（阿里云镜像上没有该库），其余依赖仍走阿里云镜像
- 想支持更多 USB 串口芯片：`res/xml/device_filter.xml` 里加对应 VID/PID 即可，驱动库本身已内置识别

**内置远程桌面的协议层用 FreeRDP**（`FreeRDP/FreeRDP`，Apache-2.0，与本项目其它依赖不冲突），选它而不是自己写协议的理由很直接：这台目标机实测强制 NLA，而 CredSSP（NTLM + Kerberos）是 FreeRDP 已经实现完、自己写正确性代价最高的一段。2.x 更进一步：`freerdpcore/` 就是 FreeRDP 官方 Android 客户端 core（MPL-2.0）的裁剪，会话界面、触摸指针、键盘与剪贴板都是上游实现，工具箱只负责连接簿与凭据。构建它需要 NDK + CMake + GNU make + perl（OpenSSL 的 Configure），`third_party/build-android.sh` 把这套流程固定了下来。1.x 曾用 IronRDP（Rust），2.0 换引擎的原因与踩坑记录见 CHANGELOG。

## 开源项目与致谢

这个工程站在一批成熟开源项目的肩膀上。按用途逐项列出并致谢（许可均为允许再分发/商用的类型，完整依赖见上一节）。

### 远程桌面

- **[FreeRDP](https://github.com/FreeRDP/FreeRDP)**（Apache-2.0）——内置远程桌面的协议层：NLA/CredSSP、TLS、位图与指针、RDPSND 音频、WinPR 运行时都由它实现；`freerdpcore/` 直接采用它官方 Android 客户端 core 的会话界面与控制逻辑（MPL-2.0），只裁掉了 aFreeRDP 的 Room/SQLCipher 连接簿；构建参数也参考了上游 `client/Android/cmake/ExternalFreeRDP.cmake`（`WITH_INTERNAL_MD4/RC4`、rail 通道等）
- **[OpenSSL](https://www.openssl.org/)**（Apache-2.0）——FreeRDP 的 TLS 与 CredSSP 加密后端，静态链入；源码包按 SHA-256 校验
- **[IronRDP](https://github.com/IronRDP/ironrdp)**（MIT / Apache-2.0 双许可）——**1.x 的协议层**（Rust 编成 `librdpcore.so`）。2.0 已经把这条线整个换成 FreeRDP，本工程**不再链接它的任何代码**；留这一条是因为 1.x 的 CHANGELOG 与大量实测结论都建立在它上面，而且当初它的接口形状决定了会话页的写法。当年对照它确认过 RDPSND 的协商顺序、滚轮与指针事件语义；这些结论在 2.0 仍然有效（改由 FreeRDP 实现）
- **[aFreeRDP](https://github.com/FreeRDP/FreeRDP/tree/master/client/Android)**（FreeRDP 官方 Android 客户端，MPL-2.0）——「交给外部客户端」那条路上，它是本机装过、用来对照行为的参照 App；现在 `freerdpcore/` 直接是它的 core 裁剪
- **Microsoft 开放规范**——[MS-RDPBCGR]、[MS-RDPEA]、[MS-RDPEGDI]、[MS-RDPEDISP]、[MS-RDPEFS] 等，协议字段与握手顺序的依据

### 局域网与共享

- **[jcifs-ng](https://github.com/AgNO3/jcifs-ng)**（LGPL-2.1）——共享枚举（srvsvc）与 SMB 读写；LGPL 合规方式见上一节
- **[SMBJ](https://github.com/hierynomus/smbj)**（Apache-2.0）——可选的 SMB 读取引擎
- **[Bouncy Castle](https://www.bouncycastle.org/)**（MIT）——NTLMv2 / SMB3 的加密原语

### 固件下载

- **[usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android)**（MIT）——USB 转串口（CH340/CP210x/FTDI 等）
- **STM32 AN3155**（STMicroelectronics 应用笔记）——串口 ISP 协议的字段依据

### 界面与媒体

- **[AndroidX / Jetpack Compose / Material 3](https://developer.android.com/jetpack)**（Apache-2.0）——共享浏览与预览页
- **[Coil](https://github.com/coil-kt/coil)**（Apache-2.0）——缩略图与图片加载
- **[Media3 / ExoPlayer](https://github.com/androidx/media)**（Apache-2.0）——视频与音频播放
- **[desugar_jdk_libs](https://github.com/google/desugar_jdk_libs)**（Apache-2.0）——API 24/25 上的 `java.time` 兜底
- **Android SDK / NDK、Gradle、Kotlin**——工具链本身

### 原生层依赖

`libfreerdp-android.so` 不是只有 FreeRDP 和 OpenSSL——把它编出来还需要一套**构建期**工具，它们不参与运行、不进 APK，但没有它就交叉编译不过（详见 `third_party/README.md`）。这一节列全，一是致谢，二是换机器时按这张表装东西：

| 工具 / 库 | 用在哪 | 来源 |
| --- | --- | --- |
| **Android NDK** `29.0.13113456` | clang/llvm 交叉编译器；**它自带的 `make.exe` 与 `yasm.exe`**（`prebuilt/windows-x86_64/bin/`）是 OpenSSL 编译的关键——1.x 认定"安卓上编 OpenSSL 必死"，缺的其实就是这个 make | Google，Apache-2.0 |
| **CMake 3.31.5 + Ninja** | `:freerdpcore` 的 `externalNativeBuild`；`sdkmanager "cmake;3.31.5"` | Kitware / Ninja 作者，Apache-2.0 / 多许可 |
| **Git for Windows**（Git Bash） | 跑 `build-android.sh` 的 shell，以及 `perl`、`tar`、`curl`、`sha256sum`、`cygpath`、`make` 之外的路径换算；脚本靠 `MSYS_NO_PATHCONV=1` 关掉它的自动路径转换 | FSF，GPLv2 |
| **Perl 本体**（Git for Windows 的精简版） | OpenSSL 的 `Configure` 必须有 perl | Perl 作者，Artistic / GPLv1+ |
| **补进 `third_party/perl-lib/` 的 Perl 核心模块** | 精简 perl 缺的四个系列，缺一个都过不去：`Locale::Maketext` + `Locale::Maketext::Simple`（从 `Perl/perl5` maint-5.42 的 `dist/`、`cpan/` 取）、`ExtUtils::MakeMaker` **7.70**（CPAN）、`Pod::Usage` **2.05**、`Pod::Simple`（`Perl/perl5` 的 `cpan/Pod-Simple`）、`Pod::Text`（CPAN `podlators-v6.1.1`）、`Pod::Escapes` **1.07**。都是纯 Perl，只在构建期用，随 Perl 本体的 Artistic/GPLv1+ 许可 | Larry Wall 与 CPAN 各作者 |
| **Gradle 发行包**（腾讯云镜像）+ **`foojay-resolver-convention` 1.0.0** | wrapper 取 Gradle 9.8.0；守护进程 JDK 25 由这个插件按 `gradle/gradle-daemon-jvm.properties` 里的 foojay API 自动下载 | Gradle 公司（Apache-2.0）/ Foojay（Adoptium 生态） |
| **JetBrains 构建的 OpenJDK 25** | Gradle 守护进程实际用的 JDK（`toolchainVendor=JETBRAINS`） | JetBrains，GPLv2 + Classpath Exception |
| **Node.js** | 只用于 `tools/gen_icons.js` 重出图标，不参与 APK | OpenJS 基金会，MIT |
| **Android 系统库**（链接期） | `log`、`jnigraphics`、`OpenSLES`、`z`、`m`、`dl`、`atomic`（`freerdpcore/src/main/cpp/CMakeLists.txt:42-51`）——运行期由系统提供，不打包 | Android Open Source Project，Apache-2.0 |

**为什么这些也要写出来**：`build-android.sh` 能不能在新机器上跑通，取决于上面这张表而不是取决于 FreeRDP 本身；1.x 就是卡在"以为是 OpenSSL 的问题，其实缺的是 NDK 里那个 make 和三个 perl 模块"。


### 交互与设计参考

- **Windows「远程桌面连接」(mstsc)** 与 **Windows App**——连接质量对话框、触摸板手势与会话界面的参考。只观察运行界面，不反编译、不复制资源
- **[KDE Connect](https://kdeconnect.kde.org/)、[LocalSend](https://localsend.org/)、[Syncthing](https://syncthing.net/)**——共享页的多选、收藏、传输队列等交互的灵感（见 CHANGELOG v1.19）

### 特别感谢

- Devolutions 与 Teleport 维护的 IronRDP（1.x 的协议层，2.0 已替换为 FreeRDP）、FreeRDP 与 OpenSSL 的维护者，以及所有提交 issue / PR 的贡献者
- 上面每个项目的作者与社区：没有这些现成的轮子，这个「工具箱」里要自己写的协议栈会多出几个数量级

如果这里漏掉了某个项目，欢迎指出，我会补上。

## 环境要求

使用 Android Studio 自带的 JDK / SDK / Gradle，无需单独安装工具链。首次打开项目时 Android Studio 会提示安装所需组件：

| 组件 | 版本 | 由谁钉住 |
| --- | --- | --- |
| Android SDK Platform | **37** | `app/build.gradle:39`、`freerdpcore/build.gradle:14` 的 `compileSdk 37` |
| NDK | **29.0.13113456** | `freerdpcore/build.gradle:15` 的 `ndkVersion` |
| CMake + Ninja | **3.31.5** | `sdkmanager "cmake;3.31.5"`（`externalNativeBuild` 用） |
| build-tools | 36.0.0 | **工程没声明 `buildToolsVersion`**——这是 AGP 9.4.0 自己选的默认值，别当成钉住的版本写进脚本，AGP 升版它会跟着变 |
| minSdk / targetSdk | 24 / 34 | `app/build.gradle:37-38`；上调 targetSdk 前需回归前台服务、通知权限与 USB 授权 |

命令行构建的 JDK 是两层，别混成一个数：`gradle/gradle-daemon-jvm.properties` 把 **Gradle 守护进程钉成 JDK 25**（`toolchainVendor=JETBRAINS`，缺了由 `settings.gradle:12` 的 `foojay-resolver-convention` 自动下），而 `compileOptions` / `jvmTarget` 是 **17**——那只约束源码与字节码级别。所以 `JAVA_HOME` 指任意 17+ 的 JDK 也能构建，前提是守护进程那侧能拿到 25。

## 构建

命令行构建（复用 Android Studio 自带 JDK 与 Gradle，把 `<AS>` 换成本机 Android Studio 安装目录）：

```bash
export JAVA_HOME="<AS>/jbr"
<AS>/gradle/gradle-<版本>/bin/gradle.bat -p . assembleDebug
# 或者直接用工程自带 wrapper：./gradlew assembleDebug
```

或直接用 Android Studio：Build → Build APK(s)。

内置远程桌面现在**随构建一起编译**，所以构建本工程需要：Android NDK（本工程实测 `ndk;29.0.13113456`）与 CMake 3.31.5 + Ninja（`sdkmanager "cmake;3.31.5"`）；先在 Git Bash 里跑一次 `third_party/build-android.sh` 把 OpenSSL + FreeRDP 的静态库编出来（脚本会按固定版本拉源码并校验，需 Git Bash 的 perl 与 NDK 自带的 make）。之后 `assembleDebug` 由 AGP 的 `externalNativeBuild` 把 `freerdpcore/` 编成 `libfreerdp-android.so` 直接打进 APK；缺静态库时 CMake 会直接指出缺哪个 ABI 的哪份库。

说明：
- **两处镜像要分开看**：Maven 依赖走阿里云（`settings.gradle:19-23`，直连 dl.google.com 会 TLS 握手失败）；**Gradle 发行包本身走腾讯云**（`gradle/wrapper/gradle-wrapper.properties:4` 指 `mirrors.cloud.tencent.com/gradle/gradle-9.8.0-bin.zip`）。换机器时如果 wrapper 卡在下载 Gradle，是后一条而不是前一条
- 工具链：Gradle 9.8.0 + AGP 9.4.0 + Kotlin/Compose 编译器 2.4.21 + compileSdk 37；build-tools 由 AGP 决定（当前 36.0.0，工程未钉）；JDK 见「环境要求」那张表——守护进程 JDK 25、源码级别 17
- 首次构建需要 `local.properties` 里的 `sdk.dir` 指向本机 Android SDK 根目录；该文件不入库，由 AS 打开工程时自动生成

产物：`app/build/outputs/apk/debug/工具箱-<版本>.apk`（可直接安装，minSdk 24 / Android 7.0+）。版本号存在根目录 `version.properties`，每次执行 `assemble*` / `install*` / `bundle*` / `package*` 前自动把 `versionCode` 与 `versionName` 末位 +1（判定在 `app/build.gradle:16-28`，是**配置阶段**跑的），APK 文件名随之带版本；`lint` 之类不产包的任务不会触发自增。所以**只想验文档时别跑构建**——它会改掉 `version.properties`，让工作树变脏、日志版本号与已发布条目对不上；真要先跑，就把自增单独提一条。

### 发布版本（release）

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/工具箱-<版本>.apk
```

**签名**：`app/build.gradle` 读根目录 `keystore/signing.properties`（`storeFile` / `keyAlias` / `storePassword` / `keyPassword` 四个键），存在就给 release 挂上 `signingConfig`，**不存在也允许构建**——产物是未签名包，装不上去，但换机器或 CI 不会被一句"找不到密钥"卡死；缺没缺由下面的 `apksigner verify` 直接看出来。**口令绝不写进任何入库文件**：整个 `keystore/` 目录已在 `.gitignore` 里（`signing.properties` 是 `*.properties`，不在原有排除范围内，所以按目录挡而不是指望 `*.jks` 那条）。

第一次建密钥（只需一次；`-validity 10950` = 30 年）：

```bash
keytool -genkeypair -keystore keystore/release.jks -storetype PKCS12 \
  -alias toolkit-release -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=ToolKit Release, OU=<你的名>, O=<你的名>, C=CN"
```

**这把钥匙必须异地备份**：Android 按签名认应用，密钥丢了以后就不能给同一 `applicationId` 覆盖升级，只能让用户卸载重装。本工程的发布证书指纹（公开信息，校验 APK 出身用的，不是密钥）：

```
SHA-256: d978761677a1b9c28ee02463ded88d3d17c02fe64e0a9ed2314ed68c4c7f605f
SHA-1:   94dfd885dd86e53929f7a769a556dffb8273c0b3
```

已实测的校验方式（本机 build-tools 37.0.0）：`apksigner verify --print-certs <apk>` → `Verifies`、**v2 方案 true**、`certificate DN: CN=ToolKit Release…`。v1（JAR 签名）是 false：minSdk 24 起步的设备都认 v2，不需要再开 v1。

**release 包与 debug 包签名不同**，所以手机上装着 debug 版时**必须先卸载**再装 release，否则报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（签名不一致）。这也是每次换签名的代价，别把调试包和发布包来回覆盖装。

**构建会自增版本号**：`assembleRelease` 也在触发自增的任务名里，一次构建 = 一个新版本号。构建失败也一样自增（判定发生在配置阶段，与成不成功无关）——本次第一版 release 就因为 lint 失败白吃了一格，办法是构建前把 `version.properties` 改回上一个值再跑。

**lint 会挡住 release**：release 变体默认跑 `lintVitalRelease`，遇 fatal 级问题直接失败（debug 不跑，所以这种问题只在发版时暴露）。本次撞上的是一条 `ExtraTranslation`：`values-en` 里有 `rdp_adaptive_size`、`rdp_size_match_phone_format` 两条译文，默认 `values/strings.xml` 里没有对应键，而且全仓零引用——那是 1.x 自绘会话页遗留的死文案。处理方式是删掉这两条（而不是给一个已经不存在的界面补默认文案）。这也印证了「已知问题」里那条"89 个无引用字符串"：它们不影响功能，但会在发版时用 lint 的方式冒出来。

### 安装到手机

```bash
# 用 SDK 自带的新版 adb（Android 16 需 platform-tools 36+）
<SDK>/platform-tools/adb.exe install -r -t "app/build/outputs/apk/debug/工具箱-<版本>.apk"
```

`-t` 不能省：debug 包带 `testOnly` 标记，缺省会报 `INSTALL_FAILED_TEST_ONLY`（Android Studio 会自动加，命令行要手动加）。

### 无线调试 / 安装常见问题

- **AS 提示 `minSdk(API 24) > deviceSdk(API 21) on device Unknown`**：不是手机版本低。小米 HyperOS 会过滤 adb shell 读到的属性（`getprop` 返回 `ro.build.version.sdk=21`、`release=6.0.1` 等占位值），AS 据此误判。开启「开发者选项 → USB 调试（安全设置）」（需登录小米账号、插 SIM、联网）后重连即可；`adb install` 走设备端 PackageManager 校验，不受此影响。
- **编译报「程序包 android.app 不存在」/ 产物只有 v1 签名（`INSTALL_PARSE_FAILED_NO_CERTIFICATES`）**：Gradle 守护进程缓存了错误的 SDK 路径（例如指向只含 emulator、没有 platforms 的第二个 SDK 根目录）。执行 `gradle --stop` 后重新构建，并在 AS 里把 Android SDK 目录统一指向实际安装的那份。
- 校验 APK 签名方案：`<SDK>/build-tools/<版本>/apksigner.bat verify --verbose <apk>`，Android 16 要求 v2 及以上。

## 新增功能

在 `ToolBoxActivity.tools` 列表注册 `new Tool(R.string.title_xxx, XxxActivity.class)` 即可（target 传 null 为灰色占位项）；再到 `AndroidManifest.xml` 注册 Activity（`exported=false`）、在 `values/strings.xml` 与 `values-en/strings.xml` 各加一份标题。

新页面要用 Compose 时继承 `ComposeBaseActivity`（已带语言菜单与 `ToolboxTheme`，子类只实现 `Content()`，options menu 从 `Menu.FIRST + 1` 起排），别去改 `BaseActivity` 的父类——那几页仍然只用系统控件，连带迁到 AndroidX 没有收益，反而要把已实测过的行为全部重测一遍。

## 目录结构

```
app/src/main/java/com/decibelmeter/app/
  ToolBoxActivity.java # 工具箱主页（功能列表 + 待添加占位）
  MainActivity.java   # 分贝仪界面、权限、设备选择、统计与校准
  DnsActivity.java    # 域名解析：A/AAAA 查询、域名列表持久化、长按复制
  LanScanActivity.java # 局域网扫描页：网段与端口集、结果列表与类型线索、收藏/手动添加、搜索/筛选/排序/导出、详扫/指纹/WoL 操作菜单
  IspActivity.java    # 固件下载页：USB 设备选择与授权、参数持久化、文件读写(SAF)、调试/选项字节对话框
  IspSession.java     # 一次下载会话的编排：进 Bootloader、型号识别、地址校验、下载/校验/读回/选项字节、调试单步
  IspProtocol.java    # AN3155 命令帧、地址与校验和、ACK/NAK、同步重试
  BootLoaderControl.java # 用 DTR/RTS 驱动 BOOT0/NRST 的 17 种进入与退出时序
  FlashProgrammer.java# 擦除、256 字节分页写入、逐块与整体回读校验、整片读回
  ChipDatabase.java   # STM32 型号表（PID/容量/页/选项字节地址）与多义 PID 消歧打分
  FirmwareImage.java  # 固件分段模型 + Intel HEX 解析与导出、BIN 加载
  IspLog.java         # 后台线程到界面的日志/进度/取消回调接口
  LanScanner.java     # 四路发现（TCP 两级探测 / UDP 137 广播 / mDNS 浏览 / SSDP）、真实网段与网关、单机详扫、RTT 与收尾统计
  NetBios.java        # UDP 137 NBSTAT 编解码：单播查询与广播枚举、18 字节名称表、角色后缀与统计区 MAC
  SsdpDiscovery.java  # SSDP M-SEARCH、MulticastLock、UPnP 描述 XML 的 friendlyName/modelName
  LanHttp.java        # 局域网 HTTP/HTTPS 读取器：自签证书、状态/头部/证书主题、超时与响应上限
  LanFingerprint.java # 服务指纹：HTTP 标题/Server/证书、SSH banner、RTSP、RDP 安全层
  WakeOnLan.java      # 魔术包、全局与网段广播
  LanMac.java         # MAC 规范化（冒号/短横/点分/连续十六进制 → 大写冒号）
  LanHost.java        # 一台被发现的设备：IP、开放端口、机器名/MAC、SSDP/mDNS 型号、RTT、lastSeen 与在线/新设备标记
  LanServices.java    # 端口集预设与自定义端口、端口标签、Web 入口选择、设备类型推断
  LanDevices.java     # 收藏与手动添加的设备持久化（JSON）+ MAC 迁移/lastSeen/已知设备基线 + 扫描偏好
  SmbClient.java      # jcifs-ng 封装：按路径列目录/下载/上传/新建/重命名/删除/剩余空间 + 错误归类 + 随机读资源出口
  SmbSettings.java    # SMB 兼容开关（SMB1、签名、来宾、超时）、排序、视图、缩略图、引擎、缓存上限、最近主机与上次路径
  SmbBrowseActivity.kt # 共享浏览页（Compose）：目标选择、列共享与目录、面包屑、过滤排序、下载/上传/写操作、传输进度、进预览
  SmbBrowseScreen.kt  # 浏览页界面：列表/网格、缩略图条目、类型角标、进度行（快照不可变，整体替换）
  SmbPreviewActivity.kt # 预览页外壳：横向翻页、四种类型派发、取到本地交给外部应用/下载、复制 UNC 路径
  SmbPreviewPages.kt  # 预览页正文：图片缩放翻页、Media3 播放、PDF 逐页、文本与编码显示
  SmbSendActivity.kt  # 「分享到工具箱」的跳板：接住 SEND/SEND_MULTIPLE 的 URI，转给共享页进入选目录模式
  SmbStreamService.kt # 串流到外部播放器的前台服务：持有回环代理、带通知与空闲收摊
  DownloadStore.kt   # 下载落点：Android 10+ 写公共「下载/工具箱」（MediaStore + IS_PENDING），低版本退回私有目录；PfdStream 负责让续传句柄活到 close
  TransferHub.kt     # 进程级传输队列：任务表、2 线程池、取消/重试/续传、按机器钉住连接、续传记录写盘
  SmbUnfinished.kt   # 未完成下载的存档（host/路径/落点句柄/已写字节），进程被杀后还能接着下
  SmbZip.kt          # 只读包尾中央目录的 ZIP（含 ZIP64 与 GBK 名）、条目取流与解到共享（拒绝 `..` 路径）
  SmbZipActivity.kt  # 压缩包页（Compose）：列条目、进子层、勾选、解压到包所在目录
  SmbBackup.kt       # 相册增量备份：查 MediaStore 照片/视频，按文件名跳过已备份的那些
  SmbCrossTransfer.kt # 两台机器之间搬文件：随机读一条 + 写对面，目录递归、移动最后才删源
  SmbTransferService.kt # 传输的前台服务：让进程在页面退出后继续活着，通知栏显示进度、可全部取消
  SmbDownloadsActivity.kt # 已下载（公共下载目录）与最近打开（预览记录）：长按分享/复制路径/删除，顶部「未完成」可继续
  PlaybackBridge.kt  # 预览页播放器与 Activity 菜单之间的桥（倍速/字幕）；PlaybackPositions.kt 记每部片子的播放位置
  SmbPdfThumbs.kt    # PDF 首页缩略图（复用整份文件的字节缓存）
  SmbRange.kt         # 按偏移读远端文件的抽象（size/readAt/readFully）+ jcifs-ng 实现
  SmbHub.kt           # 按主机复用已登录连接（引用计数）+ SMBJ 引擎与会话缓存
  SmbHttpProxy.kt     # 本机回环 HTTP/1.1 + Range：把 SMB 文件交给播放器边取边播（只绑 127.0.0.1、URL 带一次性 token）
  SmbImages.kt        # smb:// 地址生成与解析、Coil 自定义 Fetcher、字节缓存 SmbSourceCache（LRU + 上限）
  SmbVideoThumbs.kt   # 视频首帧：回环 HTTP + MediaMetadataRetriever，内存与磁盘两级缓存
  SmbPdf.kt           # PdfRenderer 封装（先整份落缓存才能随机 seek）+ 文本编码严格试解
  SmbKind.kt          # 按扩展名分类：图片/视频/音频/PDF/文本/压缩包/其它
  SmbErrors.kt        # 两个 SMB 库的错误归类到同一套中文提示 + 分块读取助手
  ComposeBaseActivity.kt # Compose 页面基类：语言切换菜单 + options menu 转发
  ToolboxTheme.kt     # Material3 深浅色调色板（跟随系统）
  RdpActivity.java    # 远程桌面连接簿：增删改、内置连接、端口测试、导出 .rdp、复制链接、显示设置（调 SessionResolution 那个共用对话框）
  RdpSessionActivity.java # 远程桌面启动器：取凭据、解析可达地址、按分辨率设置拼书签，交给 FreeRDP 会话页
  RdpConnection.java  # 一条连接（名称/地址/端口），凭据不在此处
  RdpBook.java        # 连接簿持久化，重复目标会被挡下
  RdpLauncher.java    # freerdp:// 链接构造、TCP 连通测试、.rdp 文件写出与分享
  RdpProbe.java       # 安全层探测：发 X.224 Connection Request 问服务端支持哪层（只请求 TLS / 再请求 TLS|HYBRID|HYBRID_EX 问两遍），失败码表按 FreeRDP 的 nego.h
  RdpSettings.java    # 码率档位（省流/均衡/清晰）→ 色深与是否接受有损编码。**当前只有 getter，没有 setter**：`KEY_QUALITY` 全仓没人写，所以永远取默认「均衡」，界面上也没有这一项
  RecentFiles.kt      # 「最近打开」的持久化（`smb_recent_files`，上限 20 条）——预览页看过什么，主页那条独立入口就列什么
  SmbExternalPlay.kt  # 「用外部播放器打开」：拼出回环流地址、拉起 VLC 等外部 App，并把 `SmbStreamService` 叫起来让代理活过本页面
  PlaybackPositions.kt # 每部片子的播放位置（`smb_playback`）：看过 8 秒才记、看到 95% 以上算看完不记
  CredentialStore.java # Android Keystore AES-256/GCM 加密的凭据存储 + 会话内存
  CredentialDialog.java # 用户名/域/密码/记住 的共用对话框（扫描页、共享页、连接簿都用它）
  LocalFileProvider.java # 自建的只读文件出口（content URI + 单次授权）。**写它的理由是历史性的**：这个类诞生时工程还没引 AndroidX，而现在 `activity-compose` / `appcompat` 已经把 `androidx.core` 带进来了，换成 `androidx.core.content.FileProvider` 也完全可行——现有实现工作正常、权限面也更窄（只读、单次授权、不开放目录），所以留着没动，别被那句老注释误导
  FileShare.java      # 用其他应用打开 / 分享一个私有文件（content URI + 单次读授权）
  FileUtil.java       # 大小显示、扩展名→MIME、落盘文件名安全化
  BaseActivity.java   # 语言切换菜单基类
  LocaleHelper.java   # 应用内语言持久化与 Locale 包装
  NoiseMeter.java     # AudioRecord 采集线程 + RMS→dBFS + FFT 频段回调
  MeterView.java      # 30–120 dB 刻度条自定义 View
  CurveView.java      # 60 秒滚动分贝曲线自定义 View
  SpectrumAnalyzer.java # Hann 窗 + radix-2 FFT + 1/1 倍频程能量聚合（纯 Java，无依赖）
  SpectrumView.java   # 频段柱状图自定义 View（自动量程、峰值记忆、主频段高亮）
freerdpcore/           # FreeRDP 官方 Android 客户端 core 的裁剪（会话界面 + 控制逻辑 + JNI），共 28 个 java
  build.gradle         # library 模块；externalNativeBuild 编 libfreerdp-android.so；ndkVersion/compileSdk 在这
  src/main/cpp/        # 上游 android_*.c（freerdp / event / jni_callback / jni_utils / cliprdr / disp / rail）+ CMakeLists
  src/main/java/…/presentation/
    SessionActivity.java  # 会话页：系统栏、信息面板 showSessionInfo()、指针模式切换、动态分辨率
    SessionView.java      # 画面 + 工具箱自己的单指状态机（触控/触摸板）、缩放与软跟随、远端光标合成
    ScrollView2D.java     # 滚动容器；画面外的黑边事件由它转给 SessionView.handleExternalTouch()
    SessionInputManager.java # 输入下发、trackPointer() 记光标坐标、键盘面板调度
    FloatingToolbar.java  # 工具箱加的那排半透明文字按钮（长按可拖、收起成 ☰ 把手）
    SessionDialogs.java   # 凭据框、证书框（现在走不到）、实验特性提示
    SessionResolution.java # 桌面分辨率的唯一存储与对话框（连接簿和会话页共用同一份）
    SessionViewModel.java / TouchPointerView.java（已永久 GONE）/ ApplicationSettingsActivity.java（未在 manifest 声明，进不去）
    RailWindowManager.java（RemoteApp）/ PrintProxyActivity、PrintJobMonitor、PrintNotificationHelper（打印，同样未在 manifest 声明且 CHANNEL_PRINT_CLIENT=OFF）/ ExternalDisplayManager.java（零引用）
  src/main/java/…/application/  # GlobalApp（进程级会话表与断连超时）/ SessionState / ScreenReceiver
  src/main/java/…/services/     # LibFreeRDP.java：JNI 声明 + 命令行拼装 + bracketIfIpv6 + sendMonitorLayout
  src/main/java/…/domain/       # BookmarkBase.java（含 ScreenSettings/PerformanceFlags/AdvancedSettings）/ ConnectionReference
  src/main/java/…/utils/        # KeyboardMapper（硬件键→扫描码）/ Mouse / ClipboardManagerProxy / ClipboardImageProvider（FileProvider）；**GestureDetector.java（617 行）只剩内嵌的 `OnGestureListener` 接口被 DoubleGestureDetector 引用**（:18），检测主体没人实例化；而 **DoubleGestureDetector.java 是活的**——双指手势仍由它派发（`SessionView.java:171` 建、`:442` 喂事件、`:1230` 实现它的 `SessionDoubleGestureListener`），只是单指那套状态机被工具箱自己的实现取代了
  src/main/java/…/security/     # KeystoreHelper.java（连接簿加密用的，Room/SQLCipher 删了之后**零引用**）
  src/main/res/        # 会话界面、键盘、对话框、工具栏的布局与资源
    # 裁掉主页/连接簿之后留下的**零引用资源**仍在：layout/{home, activity_bookmark, activity_about, activity_help, activity_shortcuts, bookmark_list_item}.xml、menu/{home_menu, bookmark_menu, bookmark_context_menu}.xml、xml/{advanced, gateway, screen, performance_flags, debug}_settings.xml；settings_app_*.xml 只被那个进不去的设置页引用。想瘦 APK 先从这些下手，删之前记得 `R.xml.attrs` 对应的 `IntEditTextPreference` 类也已经不在了
third_party/           # 交叉编译 Native 依赖（见 third_party/README.md）
  build-android.sh     # OpenSSL + FreeRDP → prebuilt/<abi> 静态库（源码按需拉取、哈希校验）
  perl-lib/            # 补齐 Git for Windows 精简 perl 缺的核心模块（仅构建期）——**入库**，`PERL5LIB` 指的就是它，缺了脚本直接退出
  ExtUtils-MakeMaker-7.70/  # 上面那个模块的 CPAN 原始包，内容已整理进 perl-lib，**不入库**（本机留着当出处对照）
  perl5lib/            # 空目录（早期试 PERL5LIB 时留下的），git 本来就不跟踪空目录
  openssl-3.5.9.tar.gz.sha256 # 校验值，与脚本里的 `OPENSSL_SHA256` 同一串。**它是入库的**：`/third_party/openssl-*/` 带结尾斜杠、只匹配目录，盖不住这个文件；而校验值本身对复现构建有用（源码包 `openssl-3.5.9.tar.gz` 由 `/third_party/*.tar.gz` 排除）
  README.md            # 版本、来源、许可、踩坑记录
  freerdp/、openssl-3.5.9/、build/、prebuilt/<abi>/  # 源码与编译产物，一律不入库，按脚本重取重编
assets/icon_source_raw.png # 图标原始设计稿（2048px，右下角带生成器水印）
assets/icon_master.png     # 由设计稿紧裁出的 512px 母图，是 gen_icons.js 的输入
tools/gen_icons.js         # 图标生成脚本（纯 Node，零依赖）：母图 → 各密度方形/圆形图标 + 自适应前景 + 单色层
tools/make_icon_from_png.ps1 # 历史脚本（PowerShell + System.Drawing），裁切逻辑已由 gen_icons.js 覆盖，保留备查
tools/analyze_icon.ps1       # 辅助脚本：打印设计稿非白像素包围盒，用于确认水印与图形本体不重叠
tools/make_icon.js           # 早期纯 Node 手绘图标脚本（另一套图形），当前资源不由它生成
.gitignore                 # 入库排除规则，见「仓库与提交规范」
.gitattributes             # 换行与二进制文件属性（源码 lf、png/jar/so 标 binary），跨 Windows/Git Bash 提交保持一致
version.properties         # versionCode + versionName，构建时自增（见「构建」），入库
gradle/wrapper/            # wrapper jar（入库）+ gradle-wrapper.properties（Gradle 9.8.0，腾讯云镜像）
gradle/gradle-daemon-jvm.properties # 把 Gradle 守护进程钉成 JDK 25（JetBrains 构建），由 `updateDaemonJvm` 生成
build-shim/                # 1.x 自写 RDP shim 时代的 native 产物（`librdpcore.so` + CMakeCache/ninja，约 16 MB）——**不入库**，源码线已删，只留本机备查
_verify/                   # 真机截图与临时校验产物（本次把根目录的 `g1.png`/`g2.png` 与两份从别处落下来的 WSL 下载页 html 移了进来）——不入库
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

- **权限：12 项，其中 10 项安装即授予、只有 4 项会在运行期弹框**（`app/src/main/AndroidManifest.xml:4-27`，另有 `freerdpcore/src/main/AndroidManifest.xml` 带入的两项）：
  - **安装即授予的普通权限**：`RECORD_AUDIO`、`INTERNET`（域名解析、局域网扫描、共享访问）、`ACCESS_NETWORK_STATE`（读本机链路地址）、`CHANGE_WIFI_MULTICAST_STATE`（SSDP 要收 UPnP 组播响应）、`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`（串流与传输的前台服务；`dataSync` 类型是 targetSdk 34 起的硬要求）、`MODIFY_AUDIO_SETTINGS` 与 `ACCESS_WIFI_STATE`（freerdpcore 带入）
  - **运行期会弹框的**：`RECORD_AUDIO` 的首次麦克风授权、`POST_NOTIFICATIONS`（API 33 起，首次发起传输时申请，`SmbBrowseActivity.kt:1757-1761`——没有它前台服务的进度通知发不出去）、`READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_MEDIA_VISUAL_USER_SELECTED`（只在「备份相册到这里」时用，`SmbBackup.kt:32`；`VISUAL_USER_SELECTED` 对应 Android 14 的「只选部分照片」，不声明就只能全给或全不给）、`READ_EXTERNAL_STORAGE`（带 `maxSdkVersion="32"`，只给 ≤Android 12 的老系统用，新系统上不多问一个没用的框）
  - **不需要存储权限**：共享文件的读与写都不申请传统存储权限——选文件走系统文件选择器（SAF），下载见下一条
- **各处写死的上限**（改起来都是一个数字的事，但每个都有代价，所以先讲清为什么是这个值）：传输队列完成/失败的任务最多留 **12** 条（`TransferHub.MAX_JOBS`，同时只跑 2 条），续传存档按 **1.5 s** 节流；「最近打开」**20** 条（`RecentFiles.LIMIT`）；图片缓存缩略图 **8 MB**、原图 **64 MB**、并发 **4**（`SmbImages.THUMB_CAP`/`FULL_CAP`/`MAX_PARALLEL`）；ZIP 中央目录最多读 **24 MB**、尾部窗口 **66 KB**（再多就该先下载整包了）；SSDP 描述最多收 **8** 份、单份 **64 KB**；扫描 **48** 线程、SSDP 最多等 **4.5 s**、目标上限 **1024**；视频续播看过 **8 s** 才记、看到 **95%** 以上算看完不记；递归搜索三道闸 **400 目录 / 20000 条目 / 50 命中**；最近连接主机 **8** 台、文件夹收藏 **24** 个（`SmbSettings.RECENT_LIMIT`/`BOOKMARK_LIMIT`）；回环代理每块 **256 KB**、流服务空闲 **5 分钟**自动收摊；域名试连每条 **1.5 s**、最多 **4** 条
- **局域网扫描是真实网络行为**：一轮扫描会向网段内 253 个地址各发若干 TCP SYN，外加一次 UDP 137 广播。家里和公司内网无所谓，但公共 WiFi、别人家的网络或严格按规章管理的内网会把这种流量判成扫描行为并告警——不确定就别扫。
- **共享页的写操作是真的写**：删除直接在共享上生效、撤不回来（有二次确认，文案里写明目录会连带内容一起没了）；上传遇到同名文件自动改成 `照片 (2).jpg`，不覆盖远端已有文件。
- **下载落点分两条路**（`DownloadStore.kt:12-21,77,96-105`）：**Android 10 及以上默认写公共 `下载/工具箱`**——走 MediaStore、`IS_PENDING` 提交，不需要任何存储权限，文件管理器/相册/播放器直接看得见，**卸载应用也不会带走**；Android 9 及以下退回应用私有目录（`Android/data/<包名>/files/Download`），那条路卸载即消失。要精确控制落点用「另存为…」走 SAF 直接写到目标位置。给别的 App 打开时通过自建的只读 `LocalFileProvider` 单次授权，不开放任何目录
- **局域网凭据只加密存本机**：勾「记住密码」时用 Android Keystore 的 AES-256/GCM 加密后写 SharedPreferences，密钥不可导出，落盘只有 IV + 密文与认证标签。工程开了 `allowBackup`，SharedPreferences 会随备份走、Keystore 密钥不会，所以换机恢复后旧条目必然解不开——按「没存过」处理，重新登录一次即可。不勾就只活在进程内存里，页面间传递也不经过 Intent。`.rdp` 文件与 `freerdp://` 链接都不带密码（前者的 `password 51:b:` 只是可逆编码，等于明文）。
- **SMB1 默认关闭**：只在「兼容设置」里为老设备临时打开，界面上也写明了它是明文、签名不强制。老 NAS 谈不拢版本时的报错与「权限不足」长得一样，所以协议错误单独归了一类文案。
- **数据只存本机**：全部落 `SharedPreferences`，不联网上传、不含任何预置的第三方域名或 IP。一份文件存一件事，好排查也好单独清掉——`dns`（域名列表）、`settings`（分贝仪校准偏移）、`isp`（固件下载参数与 `recentFirmware`、`chipExpanded`）、`lan_devices`（收藏与手动添加）、`lan_credentials`（加密后的局域网凭据，见下一条）、`rdp_connections`（连接簿）、`rdp_display`（码率档位，现在只有读、没有写入口）、`smb_settings`（共享页的 14 个键）、`smb_unfinished`（下到一半的续传存档）、`smb_recent_files`（最近打开）、`smb_playback`（视频播放位置）、`freerdp_security_prefs`（freerdpcore 上游带的密钥库缓存）。卸载即清除（`smb_unfinished` 里的半截文件与 MediaStore 条目一起没）
- **域名解析会发起真实 DNS 查询**：`InetAddress.getAllByName` 走系统解析器，你输入的域名会被发往当前网络的 DNS 服务器。
- **分贝精度**：手机麦克风无声学标定，绝对精度约 ±3–10 dB，详见「测量精度说明」；本应用是参考级工具，不能替代声级计。
- **固件下载有变砖风险**：烧录会擦除片内 Flash，选项字节改写可能导致读保护、调试口关闭等后果；操作前先用「读回」备份原片内容，并确保供电稳定、串口线共地。BOOT0/NRST 自动时序依赖接线正确，接错只是进不去 Bootloader，但误改选项字节需要额外手段恢复。
- **USB 功能只能真机验证**：Android 模拟器没有 USB 宿主，`UsbManager`/OTG 相关路径（固件下载）必须在带 OTG 的实机上测；CH340/CH341、CP210x、FTDI 由 `usb-serial-for-android` 驱动。
- **体积不小**：`libfreerdp-android.so` 是 arm64 约 9.3 MB、armv7 约 7.0 MB（APK 内的压缩前大小；FreeRDP + OpenSSL 静态链入、已剥符号），debug APK 约 40 MB。
- **内置通道已连上真机验证，但功能面还窄**：**2.0 实测有效的是**——轻点、点一下落在光标处、按住 900 ms 松弹出桌面右键菜单、单指滑动滚信息流（触控模式）与推光标（鼠标模式）、**硬件键盘**在 Windows 搜索框打出文字、重开会话保留上次选的指针模式、会话内改分辨率当场重排、画面四周黑边也能推光标（Windows 11 桌面上开过开始菜单）。**别把 1.x 的验证当成这一版的**：软键盘与「锁存修饰键」功能键面板、Ctrl+Alt+Del 那几条是 1.x 自绘会话页的实测结论，2.0 这边扩展键面板现在没有入口（见「远程桌面 · 内置通道」一节），工具栏上也没有 Ctrl+Alt+Del 那一档。双指捏合/滚动/右键与三指中键到现在仍未机器验证（注不进多点触控）。位图缓存/RemoteFX/GFX 与剪贴板同步现在由 FreeRDP 客户端自己开（`/rfx`（条件）`/gfx /clipboard`），不再是没做的项。已知边界：远端光标由 `SessionView` 合成在画面内，形状随服务端下发变化，但**位置只能按我们发出的事件推算**——FreeRDP 把 `colorPointerFlag` 写死为 TRUE，服务端不回传光标坐标，所以远端自己移动光标（比如动画、程序设置光标位置）时画面里的光标不会跟着走；另外原生侧只有一份全局会话状态（同时只能连一台）。
- **强制竖屏**：所有 Activity 都固定 portrait，避免旋转导致采集线程与 USB 会话重建，因此 lint 的 `LockedOrientationActivity` 提示属刻意取舍；Android 16 起该属性在多数场景会被忽略，届时需要改成真正适配横竖屏。两个例外都不锁方向并声明 `configChanges`：内置远程桌面会话页（原生会话线程活在进程里，Activity 重建会让画面与会话对不上）和共享文件预览页（视频横屏才有意义，重建会拆掉播放器与本机回环服务，看起来就是「转一下方向就重播」）。
- **targetSdk 仍是 34**：上调前需回归前台服务、通知权限与 USB 授权行为。

### 已知问题（本轮逐条对着代码核实过，**只记录、未修改**）

这一版是文档轮，代码逻辑一行没动。下面是核对 README 时顺带查出来的真问题，每条都写清现象、位置和该怎么改，下一轮按这个清单收尾：

1. **取消掉的排队任务照样会跑完**（高）——`TransferHub.start()` 里那个 `job.cancel` 只挡在 `QUEUED→RUNNING` 的状态跃迁上（`TransferHub.kt:237`），任务体在 `:267` 无条件执行。所以「停止全部」一次能取消 10 条排队的任务，而它们**全都照样传完**；更糟的是 `:244` 开工前先落的那条续传存档、以及 `:292` 结束时又 `persistResume(true)` 一次，会让用户已经「放弃」掉的下载在下次启动时又冒出来。改法：`pool.execute { }` 的第一行就 `if (job.cancel.get()) { main.post { …CANCELED…; unpin(…) }; return@execute }`
2. **`pin()` 登录失败也给连接计数 +1**（高）——`TransferHub.kt:304-310` 用 `runCatching { SmbHub.acquire(...) }`，抛异常时照样 `hostPins[host] = refs + 1`；任务结束时 `unpin()`（:312-320）却会 `SmbHub.release(host)`，于是**把浏览页自己那份引用扣穿、连带关掉正在用的连接**。另外 `pin` 是在 `submit()` 里从主线程调的，而 `SmbHub.kt:75` 的注释明写 acquire 必须在工作线程（真机上在这里吃过 `NetworkOnMainThreadException`）。改法：只有 acquire 成功才记账，失败要把错误报给用户而不是 `runCatching` 咽掉
3. **Android 9 及以下「复制路径」是错的、「删除」必失败**（中）——`SmbDownloadsActivity.kt:321` 与 `:343-345` 按公共 `Download/工具箱` 拼 `File` 路径，可那两个分支的下载实际落在 `getExternalFilesDir(DIRECTORY_DOWNLOADS)`（`:145` 列清单时用的就是私有目录，`DownloadStore.kt:165` 也证实）。列表能列出来、却删不掉，就是这两处路径不一致
4. **外部播放器播的是上一个文件**（中）——`SmbStreamService.kt:117-122`：只要代理还活着就直接回 `running.url`，**完全没比 `EXTRA_HOST`/`EXTRA_PATH`**。空闲窗是 5 分钟（`:45`），所以「先用 VLC 播 a.mkv，退回来点 b.mkv」会拿到 a 的地址继续播。改法：服务里留住 host/path，两者不同就重启代理
5. **「适配屏幕分辨率」在转屏时不生效**（中）——判断条件恒为假，见「远程桌面 · 内置通道」那条「未验证/边界」里的定位（`SessionActivity.java:481-489` + `RdpSessionActivity.java:75`）
6. **上传只有 64 KB 串行**（中）——`SmbClient.java:325` 的注释写着「写侧不能传大缓冲…得自己切块分发，留待以后」；读侧已经把套接字缓冲抬到 1 MB（下载 49.6 MB/s），写侧还是 17.4 MB/s。要再快只能并行写：顺序流切块、多句柄按偏移写
7. **`IspActivity` 在 main thread 读写整片文件**（中）——`onActivityResult`（:598）→ `loadFirmware`（:614）→ `readAll`（:864）直接把最大 8 MB 的固件（`:640` 才做上限判断）读进内存，之后 HEX 解析与建镜像也都在主线程；`saveFlashImage` 把读回的整片 Flash 走 `toIntelHex()`（体积膨胀约 4 倍）写出去同样是主线程。这个类本来就有一个 `runTask()` 调度器（`:905-948`）和一个 `executor`（`:114`），搬过去即可
8. **一次点几十张照片时主线程跑 ContentProvider 事务**（中）——`SmbBrowseActivity.kt:1387` 在点击回调里 `DownloadSink.create()`，里面是 insert + 两次 query（`DownloadStore.kt:106-162`）；批量下载是循环调用（`:1232-1234`），30 张照片就是约 90 次事务。`doneText()`（`:1412` → `DownloadStore.kt:247`）每完成一条还要在主线程问一次大小
9. **`LanHttp` 对所有主机接受任意证书**（中）——`:247-266` 的 `X509TrustManager` 两个 `check*Trusted` 都是空实现，也不做主机名校验。给自签名的路由器/摄像头门户是刻意的，但它现在对扫描到的**任意**地址都这样。收窄到 RFC1918 目标、并在界面上写明「未验证的 TLS」就够了
10. **凭据每次都过一次 Keystore**（低）——`CredentialStore.java:175-191` 每次 `get()` 都 `KeyStore.load(null)` + `getEntry` + AES-GCM 解密，`SESSION` 缓存从来没填过；调用点全在主线程（`LanScanActivity` 四处、`RdpActivity` 三处、`SmbBrowseActivity.kt:672`）
11. **JPEG 解码跑在组合线程上**（低）——`SmbBrowseScreen.kt:749` 在 `produceState`（Main）里调 `SmbVideoThumbs.request()`，而它 `:53-61` 命中磁盘缓存时同步 `BitmapFactory.decodeFile`（`:127`）。挪到 `Dispatchers.IO` 或走现成的 `workers`
12. **两处小的死路**：`SmbPdfThumbs.clear()`（`:55`）没有任何调用点，所以菜单里的「清空缓存」清不掉 PDF 缩略图那份进程内 `LruCache`（磁盘那份归 `DownloadStore`/`cacheDir` 管，能清掉）；`SmbVideoThumbs.peek()`（`:40`）与 `has()`（`:82`）也没有调用点
13. **一句过时的注释**：`IspActivity.java:584` 写「本工程刻意不引入 AndroidX」——2.0 的 `freerdpcore` 与 Compose 都建立在 AndroidX 上，这话对本页还成立、对本工程已经不成立了，改注释就行
14. **1.x 会话页遗留的字符串资源还没清**：`app/src/main/res/values/strings.xml` 的 710 个键里 **89 个没有任何引用**，绝大多数是 1.x 自绘会话页的词汇（`rdp_quality_*`、`rdp_detail_*`、`rdp_mode_*`、`rdp_scale_*`、`rdp_keys_cad` 等）；freerdpcore 自己那份 `strings.xml` 里另有 27 条无引用，9 个 locale 各缺 106 条译文。不影响功能，但会误导后来的读者以为还有这些开关。**v2.0.72 已删其中的两条**：`values-en` 的 `rdp_adaptive_size` 与 `rdp_size_match_phone_format`——它们是唯一会**挡住发版**的一批（默认 locale 没有对应键 → `lintVitalRelease` 报 `ExtraTranslation` → `assembleRelease` 失败），其余的因为默认键还在、lint 只当冗余不报错。剩下的清掉才算真解决

## 仓库与提交规范

代码同步托管在 GitHub 与 Gitee 两处，规则如下。

**入库范围**（`.gitignore` 已落地，提交前用 `git status` 复核）：

| 类别 | 处理 |
| --- | --- |
| 编译产物 | 排除：`build/`、`.gradle/`、`.cxx/`、`/app/.cxx/`（AGP 的 native 中间目录在 `build/` 之外还单独落一份）、`.externalNativeBuild/`、`captures/`、`release/`、`debug/`、`*.apk`、`*.aab`、`*.ap_`、`*.dex`、`*.class`、`lint-results*` |
| IDE 与工具元数据 | 排除：`.idea/`、`*.iml`、`.kotlin/`（Kotlin 会话缓存）、`local.properties`（含本机 SDK 绝对路径）、`.DS_Store`、`Thumbs.db`、`desktop.ini`、`*.swp`、`*~` |
| 本机调试遗留 | 排除：`/Python/`（AS Python 插件在本目录落的解释器缓存，100MB+）、`/_verify/`（真机截图与临时校验产物，含本次移入的 `g1.png`/`g2.png`）、`*.log` |
| Native 依赖 | 排除：`/third_party/freerdp/`、`/third_party/openssl-*/`（**带结尾斜杠，只匹配那个源码目录**；源码包 `openssl-3.5.9.tar.gz` 是靠 `/third_party/*.tar.gz` 排除的，同目录的 `.sha256` 因为不是目录、也不匹配 `*.tar.gz`，所以**会入库**——那只是一行校验值，对复现有用）、`/third_party/build/`、`/third_party/prebuilt/`、`/third_party/*.tar.gz`、`/third_party/ExtUtils-MakeMaker-7.70/`（CPAN 原始包，内容已整理进 perl-lib）——按 `third_party/README.md` 重编即可 |
| Native 依赖（**要入库**） | `/third_party/perl-lib/`：构建期补齐的那批纯 Perl 模块，`build-android.sh:47` 的 `PERL5LIB` 指的就是它、`:49` 还会检查 `perl-lib/Pod` 在不在，缺了脚本直接退出。所以它是"构建必需"，不是本机缓存 |
| 1.x 遗留产物 | 排除：`/build-shim/`（自写 RDP shim 时代的 `librdpcore.so` + `CMakeCache.txt`/`build.ninja`，约 16 MB；源码线已删，产物只留本机） |
| 签名与凭据 | 一律排除：**整个 `/keystore/` 目录**（`release.jks` + `signing.properties`，后者是明文口令、且 `*.properties` 本身不在排除范围，所以按目录挡）、`*.jks`、`*.keystore`、`*.p12`、`*.pfx`、`*.pem`、`.env`、`secrets*`、`*token*`、`*.rmi`、`passwords*`；密码/令牌只允许通过环境变量或这个被忽略的目录临时传入，不写入任何入库文件 |
| 构建必需 | 保留：`gradle/wrapper/gradle-wrapper.jar`、`gradlew`、`gradlew.bat`、`gradle/gradle-daemon-jvm.properties`、`.gitattributes`、`version.properties`、`settings.gradle` 里的公共镜像地址 |

**入库前必看一眼的东西**（历史教训）：真实内网地址、设备真实 MAC、个人机器名、自己的 DDNS 域名与公网 IPv6 前缀都属于隐私——文档里一律用占位写法：示例地址 `192.168.1.100` / `192.168.1.10`、示例机器名 `PC-HOME` / `PC-OFFICE` / `NAS-HOME`、MAC 掩码 `C8:BF:4C:XX:XX:XX`、DDNS 域名写成「自己的 dynv6 域名」。这套约定由 1.x 提交 `9b48e84` 定下，2.0 的 RDP 验证记录曾把真实地址与域名原值写回去，本次已清掉。**写「清理说明」时也别把原值抄一遍**——记录清掉了什么，不需要把被清掉的东西再列出来；本次第一稿就踩了这个，脱敏条目自己带着原值，重写提交时才拦住。
**改完文档再建历史、再提交**：脱敏必须放在第一个 commit 之前，否则真实值会永久留在已推送的历史里，补一条「清理」提交是撤不干净的。已经提交但**还没推送**时发现漏抄，就重写那个未推送的提交（`--amend` 或 `reset --soft` 后重新提交都行），不要往后追加修正提交。核查要查**已提交的对象**而不只工作树：`git show <commit>:CHANGELOG.md | grep -cE '<被查字面量>'`。

**换行符（`.gitattributes`）**：`* text=auto` 之外还逐类钉了 `eol=lf`——`*.java`、`*.kt`/`*.kts`、`*.xml`、`*.gradle`、`*.properties`、`*.md`、`*.c`/`*.h`、`*.js`、`*.sha256`、`*.sh`、`*.pm`/`*.pod`/`*.pl`、`gradlew`、`.gitignore`，`*.bat`/`*.ps1` 钉 `eol=crlf`。**为什么 `*.sh` 必须钉死**：本机 `core.autocrlf=true`（Git for Windows 装机默认），只靠 `text=auto` 的话库内是 LF、**检出到工作树会变成 CRLF**，新克隆那台机器上 `./third_party/build-android.sh` 会因行尾的 `\r` 报 `bad interpreter`，`perl-lib` 里那些 `.pm` 也会被带进脏参数。补齐之前的 `.gitattributes` 只钉了 `gradlew`，没钉 `*.sh` 与 Kotlin/C 源文件。复核用 `git ls-files --eol <文件>`，看 `i/lf` 与 `attr/... eol=lf` 两列

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
