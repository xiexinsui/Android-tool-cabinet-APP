# 工具箱（Toolbox）

安卓手机工具合集应用，当前包含「分贝仪」「域名解析」「固件下载」「局域网」（扫描 + 共享文件夹 + 远程桌面合成一个入口）四类工具，其他功能待添加。界面以系统控件（ListView / AlertDialog）实现为主，只有「共享文件夹」的浏览与预览两页用 Kotlin + Jetpack Compose（缩略图走 Coil，视频走 Media3，PDF 走系统 `PdfRenderer`），第三方依赖见「依赖」一节；内置远程桌面的协议层是 `IronRDP`（工程内的 Rust 原生层 `rdpcore/` 包一层手写 JNI），默认不参与构建。

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
- **设备身份跟 MAC 走**：收藏记录 MAC 与 lastSeen，DHCP 换地址后按 MAC 把收藏和已存凭据迁到新 IP；同一个 IP 上的 MAC 变了会先确认再用旧凭据。当前轮没出现的收藏显示「上次在线 X」；基线建立后新出现的设备标「新」，不会每轮都提示。没有 MAC 的设备退回 IP+名称；手动项可以自己填 MAC
- **MAC 显示与来源**：条目固定显示 `MAC ...` 或 `MAC 未获取`。能拿到 MAC 的通道是 NetBIOS NBSTAT（这次修正了 DNS 头偏移、18 字节名称项，以及名称表之后统计区的 Unit ID）、mDNS TXT 的 `deviceid`/`mac`（AirPlay 常带 `deviceid`，实测音箱显示 `C8:BF:4C:F6:83:EE`）、SSDP 描述 XML 的 MAC 字段，以及手动「设置 MAC」。Android 10+ 普通应用读不到 `/proc/net/arp`（真机 `run-as` 验证 `Permission denied`），所以默认关闭 NBNS 的 Windows 11 等设备仍显示未获取；应用不提供 root/Shizuku 读取通道，全量 MAC 只能手动设置或从路由器租约表导入
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
- 真机实测（Mi 10 Pro，192.168.5.0/24）：全服务预设约 5 秒扫完 253 个地址，找出路由器（53/80/443/8080，旧版四个端口全关时根本不会出现）、NAS、Windows 电脑，以及两台一个端口都没开、只回 mDNS 的 AirPlay 音箱；详扫又在路由器上多找出一个 784 端口
- 真机回归（同一设备）：路由器的 3 个网页端口在菜单里全部列出（`https://192.168.5.1/`、`http://192.168.5.1/`、`http://192.168.5.1:8080/`）；「端口详情」显示 53/80/443/8080 四个端口并可复制；对手动添加的空地址详扫 1–1024 后行状态是「已探测，没有开放端口」而不是「还没探测」；手动主机名 `nosuchhost` 重探时状态行报「无法解析 nosuchhost」，原有状态不被清掉
- 本轮真机回归（同一设备，192.168.5.0/24）：网关识别把路由器标成「网关 / 路由器」，并用 SSDP 显示 `MiWiFi Router · Xiaomi`；5 台设备条目带 2–5 ms RTT；「识别设备」在路由器上读出 `HTTP :443 → 200 · nginx`、标题「小米路由器」和自签证书主题；WoL 对带 MAC 的设备返回发送成功，「设置 MAC」入口正常；筛选「有网页入口」只剩路由器/NAS，按名称排序、搜索 `router`、导出 CSV 都正常；自定义端口 `784,3389` 只探出路由器 784 与两台 Windows 的 3389；lastSeen 显示「上次在线 3天前」，基线外设备标「新」并在下一轮自动转为普通条目
- MAC 显示真机结果：AirPlay 音箱从 mDNS TXT `deviceid` 显示 `MAC C8:BF:4C:F6:83:EE`；路由器、NAS 与两台 Windows 都显示 `MAC 未获取`——这台网络里的设备不回 NBSTAT，手机应用也读不到 `/proc/net/arp`。NBSTAT 解析用合成报文单独验证过：`workstation=DESKTOP-AMD`、`mac=A0:AD:9F:1C:92:91`、`dc=true`
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
- **缩略图**：图片由 Coil 经自定义的 `smb://` `Fetcher` 直接取字节（不绕 HTTP 那一跳），视频首帧由回环 HTTP + `MediaMetadataRetriever` 抽帧；两者都落到 `cacheDir/smb_preview`（默认上限 128 MB，按最后使用时间淘汰，菜单「预览缓存」可看占用与一键清空）。缩略图开关在菜单里，关掉即回到「只看名字翻共享」，省流量也省掉一大串 SMB 读
- **应用内预览**：图片（双指缩放 / 拖动 / 双击 1×↔2.5×，左右翻页）、视频与音频（Media3 + 回环 HTTP，边取边播、可任意拖动）、PDF（系统 `PdfRenderer` 逐页渲染）、文本/日志/代码（只取前 512 KB，按 UTF-8 → GB18030 → Big5 严格试解并显示实际编码）。预览页把当前目录里所有能预览的项一次带走，所以能左右翻；只有停住的那一页会真的起播放器
- 预览不了的出口：菜单里保留「用其他应用打开」与「下载到本机」，播不动的 wmv/avi、加了密的 PDF 都有地方去；超过预览上限的文件会写清有多大、上限是多少
- **常用文件夹收藏**：在任意目录用菜单「收藏当前目录」一点加入，之后从「文件夹收藏…」或无地址入口的目标选择框里一点直达（目标框里收藏排在最近主机之前——它们是每天要开的那几个地方）。收藏可改名、删除；名字默认取路径末段，跳转时标题用最近主机记住的机器名而不是 IP
- **传输是进程级队列，页面退出也照跑**：任务表在 `TransferHub`（进程单例），前台服务 `SmbTransferService` 只负责让进程活着并把进度摆到通知栏（带「全部取消」）。每条约有独立进度、取消与失败原因，默认 2 条并发（1 条会让「传一批照片」纯串行，8 条在一条千兆链路上互相抢 SMB credit，2 条刚好掩盖往返延迟）；进度每 100 ms 才推一次界面。连接的寿命也由它管：有活跃任务的机器被「钉」住（`SmbHub` 引用计数 +1），Activity 退出只减少自己那一份
- **断点续传**：下载失败/取消后，任务上会出现「重试」，从**已经写入的字节**继续（`SmbClient.download(path, offset, …)` 用随机读句柄直接定位，不把前面的字节白读一遍）。续传起点只认任务自己记录的 `doneBytes`——MediaStore 的 pending 项查不到 SIZE（真机试出来的），提交前还会核对一次总字节数，对不上就报错而不是把坏文件当成功
- **已下载与最近打开**（主页独立入口）：列公共 `下载/工具箱` 里的文件（Android 10 以下退回私有下载目录）与最近预览过的共享文件，点一条直接打开/继续看；**长按一条**给「分享 / 复制路径 / 删除」（删除连 MediaStore 记录一起清）。顶部还有一段**未完成**：见下条
- **传输不再阻断浏览**：可以边传边翻目录、看预览、继续往队列里加任务（原来一有传输点条目就被拦住）。写操作完成后会作废该目录的缓存，不会出现「改完名翻出去再回来还是旧名」
- **下载进度能活过进程被杀**：每条下载按 1.5 秒节流把「主机 + 远端路径 + 落点句柄 + 已写字节」写进 `UnfinishedStore`。划掉应用、系统回收进程之后重开，「已下载」页顶部的「未完成」会列出下到一半的东西与字节数，点「继续」从断点接上（实测 2.6 GB 的电影下到 655 MB 时杀进程，重开后续到完成，字节数与远端一致），点「放弃」则把半截文件删掉。落点句柄之所以能重建：Android 10+ 那个 `IS_PENDING=1` 的 MediaStore 条目本来就还在，只是别的看不见
- **传输速度**：jcifs 默认的 `rcv_buf_size`/`snd_buf_size` 只有 64 KB（`SmbConstants.DEFAULT_RCV_BUF_SIZE`），那同时就是 TCP 窗口——实测同一个 64 MB 文件下载 15.0 MB/s、上传 8.3 MB/s，而链路是 1200 Mbps。抬到 1 MB 后变成 **下载 49.6 MB/s、上传 17.4 MB/s**（Mi 10 Pro / Wi-Fi 6 RSSI -52 / Win11 虚机）。两条边界也一并试出来了：**显式给 `openInputStream`/`openOutputStream` 传 1 MB 会让服务端直接回 `STATUS_INVALID_PARAMETER`**（SMB2 下单次 READ 的大小取服务端协商值，WRITE 这个会话上限就是 64 KB），所以读写的每请求粒度保持库的默认，能调的只有套接字缓冲；上传要再快只能做并行写（把顺序流切块、多句柄按偏移写），暂未做。另外「允许 SMB1」时缓冲自动退回 64 KB——SMB1 且服务端没有 LARGE_READX 时 1 MB 的 READ 老设备会拒
- **从手机出发的上传**（对齐 LocalSend 那种「先选文件再挑落点」）：菜单「从手机选文件上传…」先多选文件、再进「选目录模式」；别的应用「分享到工具箱」也走同一条路（`SmbSendActivity` 是纯跳板：接住 `SEND`/`SEND_MULTIPLE` 的 URI，转给共享页，自己不建连接也不落盘）。选目录模式下列表只留目录、顶部一条「已选 N 个文件 · 上传到这里」的横幅；确认后整批入队，页面留在原地看着每条的进度
- **类型筛选**：工具条上的胶囊按 图片/视频/音频/PDF/文本 过滤（目录始终显示——目录是路）；**递归搜索**（菜单「搜索子目录…」）：SMB 没有搜索接口，只能逐目录列，所以给了三道闸（400 个目录 / 20000 个条目 / 50 条命中），结果标题里写明扫了多少目录
- **视频续播**：按「主机 + 路径」记播放位置，起播直接跳过去，看完或只看开头几秒不记；**PDF 首页缩略图**（`PdfRenderer` 渲第一页，那份字节缓存点开 PDF 时还能复用）；列表滚动时视频/PDF 缩略图延迟 250 ms 再取，划走的条目自动取消（否则每个视频缩略图都要起一次回环代理）
- **长按多选**：长按任意条目进入多选，再点其它条目累加（点已选的取消），工具条换成「已选 N 项 / 全选 / 下载 / 删除 / 取消」；只选了一项时多出一个 `⋯`，它就是原来的完整条目菜单（目录没有右侧 ⋮，改名之类的动作否则够不着）。全选只作用于**当前过滤后看得到的**条目；批量下载每文件一条任务进同一个队列；批量删除有一次确认，文案写清「含目录时里面的内容会一起没了」。返回键先退出多选而不是退到上一层
- **查看与播放**：图片 / 视频音频 / PDF / 文本/代码都在应用内看（Coil、Media3 + 回环 HTTP、`PdfRenderer`、编码探测）。播放器按局域网流调过参：回环代理每块 256 KB（64 KB 时每读一块就要等一个 SMB 往返，高码率片子拖动后容易卡一下）、短缓冲 + 大 back-buffer、**seek 走最近关键帧**（精确 seek 会把中间数据全读一遍，网络上是「拖一下卡几秒」）、音频焦点与拔耳机自动暂停、播放时保持亮屏、控制器 ±10 秒；预览页菜单里还有**字幕轨选择**（容器内嵌的字幕在 `currentTracks` 里，同目录同主名的 `.srt/.ass/.ssa/.vtt` 由播放页自己找出来、整份落到字节缓存后挂成 `SubtitleConfiguration`——Media3 不会替用户猜同名文件，不挂就没有）、**倍速 0.5×~2×**（翻页重建播放器也记得住）、**横屏/竖屏切换**（清单里声明了 configChanges，转屏不中断播放）。播不动时给两条出路——**换系统播放器**（`VideoView` → 厂商 `MediaPlayer`，用设备自带的解码器）或**用外部播放器打开**（把本机 HTTP 流地址交给 VLC/小米视频等，浏览页条目菜单里也有「用外部播放器直接播放」，不用先进预览页）。后者走 `SmbStreamService` 前台服务：代理必须活过本页面，否则播放器下一次 seek 就会被拒；通知里带「停止」，空闲 5 分钟也会自动收摊
- **复制 / 剪切**：条目菜单与多选工具条都有「复制到… / 移动到…」，选目标目录用的还是那个「只列文件夹」的选择器。复制走 jcifs 的服务端复制（SMB2 的 `FSCTL_SRV_COPYCHUNK`），移动优先走服务端改名——**都不把数据绕手机**，同一台 NAS 内搬 1.2 GB 实测约 6 秒；跨共享改名失败时退回「复制 + 删除」。重名一律自动避让（`(2)`），绝不覆盖
- **跨主机也能复制/移动**：选目标目录时菜单里多出「换一台机器作目标…」（只在复制/移动进来时出现——上传的源是手机里的文件，换机器没有意义），选完照常翻目录，横幅上补一句「源在 192.168.5.2 上：字节要经手机中转，比同一台机器内搬慢」。同机走上面那条服务端通道，跨机就是 `SmbCrossTransfer`：读一条随机句柄、写到对面，目录递归并逐级建目录，**移动是整棵子树搬完之后才删源**（中途取消或报错时源完好）。两台机器的连接都在任务体里现取现还，页面那份 `link` 早就跟着「换机器」切走了
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

- **连接簿**：名称 / 地址 / 端口，重复目标会被挡下；扫描页可以「存到远程桌面连接簿」。账号密码不在连接簿里，仍归 `CredentialStore` 按主机存，同一份密码不在两处落盘
- **复制链接**：`freerdp://<用户>@<IP>:<端口>/connect`（域走 `?d=<域>`）只作为可粘贴的文本，交给别的设备或别的客户端用。**密码故意不进 URI**（会进对端进程命令行、也可能落日志）
- **端口连通测试**：TCP 握手 + 往返耗时。结论写在状态行而不是只弹 toast——实测 MIUI 13 会把连续第二条 toast 直接吞掉
- **安全层探测**（`RdpProbe`）：发一次 X.224 Connection Request 问服务端能用哪种安全层，问两遍（只请求 TLS、再请求 TLS|HYBRID|HYBRID_EX）才能区分「允许 TLS」和「强制 NLA」——只问后者永远只会拿到权限最高的那个。结论直接决定内置客户端要不要实现 CredSSP + NTLM，而这一点光看 3389 开不开是问不出来的。报文的 19 字节 client hello 是从本机 mstsc 抓下来的真实包，失败码表按 FreeRDP `libfreerdp/core/nego.h` 抄：`0x1 SSL_REQUIRED_BY_SERVER / 0x2 SSL_NOT_ALLOWED_BY_SERVER / 0x3 SSL_CERT_NOT_ON_SERVER / 0x4 INCONSISTENT_FLAGS / 0x5 HYBRID_REQUIRED_BY_SERVER / 0x6 SSL_WITH_USER_AUTH_REQUIRED_BY_SERVER`。解析时 RDP_NEG 数据在第 11 字节、值在第 15 字节——少算 4 字节会把 length 字段当成 failureCode 读出一个看似合理的错值（踩过一次）
- **导出 `.rdp`**：RDP 6 的「键:类型:值」逐行文本（CRLF、UTF-8 无 BOM，与 mstsc 习惯读的 ANSI 对 ASCII 内容字节一致），经分享出口发出去，在电脑上双击即可连接。**不写 `password 51:b:` 字段**——那只是可逆编码，等于把密码留在文件里
- 真机实测：对开着 3389 的 Windows 机器握手成功、往返 33 ms；导出的 `.rdp` 内容逐行核对正确；分享面板能选到网盘与文件管理器

### 远程桌面 · 内置通道（rdpcore/ + IronRDP）

不装任何外部客户端，在应用里连、在应用里看画面、在应用里操作。**协议层不是自己写的**：接的是 IronRDP，真机上已经能连上一台强制 NLA 的 Windows 11 并实际操作它（下面「已验证」有具体结论）。

- **引擎选 IronRDP**（Rust，`Devolutions/IronRDP`）：四个候选里只有它把 **NLA/CredSSP（NTLM + Kerberos）** 实现完了，而这台虚拟机与本机都实测回 `HYBRID_REQUIRED_BY_SERVER`——不实现 CredSSP 就连不上。FreeRDP/aFreeRDP 是 C，要 cmake + ninja + NDK + 交叉编译 OpenSSL；Thincast 是 .NET/Windows 客户端，跑不到安卓上
- **只引 facade**：依赖清单里只有 `ironrdp`（features `client/rustls/connector/session/graphics/input`）+ `tokio`。`ironrdp-client` 由 facade 重导出成 `ironrdp::client`，少一层版本对齐风险。TLS 后端选 **rustls**：选 native-tls 就得在安卓上交叉编译 OpenSSL，那正是当年 FreeRDP 路线卡死的地方
- **架构**：Rust 侧一条会话线程持有 `CurrentThread` 运行时并驱动 `RdpClient::run()`，帧、失败、断开汇进一份共享状态；Java 侧按 30 fps **轮询取帧**（`state` / `copyFrame`）。刻意不做「回调进 JVM」——那要管 JavaVM 句柄与 `AttachCurrentThread`，生命周期一错就是随机崩溃，而轮询最多带来一帧延迟
- **`run()` 不 spawn，用 `tokio::pin!` 和取帧循环一起 select**：`tokio::spawn` 要求这条 future 是 `Send`，而它穿过 `ironrdp-client` 里几个借 `&UnboundedSender<RdpInputEvent>` 的 async fn，rustc 的高阶生命周期推不出来，直接报 "Send is not general enough"。反正运行时是 CurrentThread，单任务轮询与 spawn 在这个模型下没有区别
- **JNI 那层仍是手写**（`jni` crate 会多引 proc-macro 依赖），函数表槽位下标用脚本按 NDK 自带的 `jni.h` 数出来，并与规范里可查的槽位互相印证（GetVersion=4、FindClass=6、GetStringUTFChars=169、NewByteArray=176、SetByteArrayRegion=208、SetIntArrayRegion=211）
- **文本一律以 UTF-8 字节数组跨边界**，不用 `NewStringUTF`：那个函数要的是「修改版 UTF-8」，中文机器名或账号会出乱码
- **凭据只走一次 JNI**：`nativeStart(host, port, user, domain, password, device, width, height)`，账号由会话页在同一进程里向 `CredentialStore` 取（缺账号时先弹凭据框，因为 NLA 没账号握不了手）。密码不进 Intent、不进连接簿、不进日志，`device` 取 `Build.MODEL` 只作 Client Info 里的电脑名
- **键鼠状态交给 IronRDP 的 `Database`**（`ironrdp_input`），不自己记：它负责「按下过就不再重复发按下」「扩展键的 E0 前缀」「按钮事件不带 `PTR_FLAGS_MOVE`」「按下与抬起一一对应」，还带 `release_all`——切后台 / 弹输入法 / 换模式前必须调它，否则远端会一直以为 Ctrl 还按着，回来就是「什么都没点但全在选中文本」
- **按钮事件不带 `PTR_FLAGS_MOVE`** 这一条是踩出来的：MS-RDPBCGR 把「位置变化」与「按键状态变化」分成两类事件，合并着发的时候远端指针会跟着悬停出 tooltip，但点开始菜单没反应。`Database` 把位置与按键拆成两条、顺序也对
- **拖动时移动事件还得带按键位**：Windows 把指针事件的按钮位当**当前状态**看，只发 `PTR_FLAGS_MOVE` 会被当成「左键已松开」——按下之后第一下移动就断了，窗口、图标、框选全都拖不动（日志里"按下保持到抬手"也看不出来）。原生层在发送前给移动事件补上按住的键位（`LEFT_BUTTON`/`RIGHT_BUTTON`/`MIDDLE_BUTTON_OR_WHEEL`），和 FreeRDP 的做法一致
- **两种指针模式**：触控模式对齐 Win11 触摸屏——**轻点=左键、双击=双击、单指滑动=滚动（带惯性）、按住 500 ms 弹出方块提示且松手=右键、按住后拖动=拖拽、双指张合=缩放**，按下时还不按键（等判定），所以滑动页面不会变成框选；手指点在哪儿远端光标就落在哪儿，屏幕上不画鼠标箭头。鼠标模式把画面当触摸板，手势对齐笔记本：**轻点=左键、双指轻点=右键、三指轻点=中键、轻点后紧接着按住拖动=拖拽（tap-and-a-half）、双指滑动=滚动（纵向横向都行）、双指张合=缩放**。**指针带触摸板式加速**：慢速移动 0.75× 增益、快速滑动最高 2.25×（再乘灵敏度滑块），慢速点小目标稳、快速一划能横跨桌面。进入拖拽后不用先空走一段：轻点之后按住 180 ms 就直接按下左键，或移动超过半个 touchSlop（约 8 px）立即按下；两条入口只发一次按下，双击的第二下短于 180 ms 抬起不受影响。长按不动 600 ms 松手也等于右键（手机上比双指顺手）。鼠标模式下光标位置按**远端像素**累加，换分辨率或旋转都不会让光标跳走；灵敏度可调（50%–400%）
- **键盘三条通道**：软键盘文本走 Unicode 事件（远端不必知道手机是什么布局，中文输入法组合中的拼音不会被当成字符发出去）；功能键面板走扫描码（Esc/Tab/Enter/Space/Bksp/Del/Ins/Home/End/PgUp/PgDn/方向/Menu/Caps/F1–F12）；接了 OTG/蓝牙键盘时 `dispatchKeyEvent` 直接翻译成扫描码。**修饰键做成可锁存**：Ctrl/Alt/Shift/Win 点一下按住不放，关闭面板后依然有效（状态行会一直标着「按住 Win」），这样才能做 Win+E、Ctrl+C 这类组合——手机上没法一只手按 Ctrl、另一只手点屏幕
- **Ctrl + Alt + Del 是真的能用**：按扫描码序列发出去，Windows 11 认这串键位，真机上直接唤出了安全注意序列界面（锁定 / 退出登录 / 更改密码 / 任务管理器）。但那个界面会重置服务端的键盘状态，所以发完顺手清掉锁存的修饰键
- **滚轮是真的**：`VERTICAL_WHEEL` + ±120（Windows 一格的固定值）。IronRDP 的 encode 会按符号自己补 `WHEEL_NEGATIVE` 位并把数值塞进 flags 低 9 位，所以只需如实传格数，不要自己去拼那一位
- **连接详情（对标 mstsc 的「连接质量」对话框）**：点状态行打开，正文可选可复制。分三段——[客户端详情] 应用版本、本地系统、内核；[网络详情] 传输协议（如实写 TCP：IronRDP 还没有 RDP 的 UDP 传输）、往返时间（预检时 TCP 握手的实测值）、本次接收速率与累计流量、画面帧率、声音与音频包数；[远程计算机详情] 会话类型、网关、远程计算机（域名会显示解析结果，IPv6 自动加方括号）、状态、尺寸、码率、指针模式、输入统计。顶部一句「连接质量」按往返时间分档（≤30 ms 很好 / ≤80 好 / ≤150 一般），帧率不参与——静态桌面本来就是 0 帧，拿它判网络会误伤
- **码率三档**（显示对话框）：省流 = 16 位色 + RemoteFX 有损编码（位图最小）；均衡 = 32 位色 + 有损（默认）；清晰 = 32 位色 + 从 codec 列表里去掉 RemoteFX（`remotefx:off`），文字最锐利、位图载荷最大。色深与 codec 都在握手时协商，改档位自动重连一次。连接详情里同时给出"按未压缩像素算"的画面流量与 `TrafficStats` 采到的**真实接收字节**，调档位时可以直接对比
- **断线怎么被发现**：远端正重启或休眠时 TCP 常常既不 RST 也不 FIN，客户端不发数据就永远不知道对面没了（表现就是画面停住、状态还写着已连通）。现在开了 IronRDP 的 `with_fake_events_interval(20s)`：空闲时补一条**原地**鼠标移动（位置不变，画面上看不出来），既是 NAT 保活，也让死链在几十秒内撞上 RST；`run()` 结束收尾时再把队列里排着的 `Terminated` 读出来，状态行写「已断开 · … · 连接中断：<原因>」，工具栏同时把「断开」换成「重连」
- **有声音**：连接时声明 RDPSND（PCM 44.1 kHz 立体声 16 位），服务端把音频从静态通道推过来，ironrdp-client 里的 cpal 后端直接交给 Android 的 **AAudio** 播放，跟随系统媒体音量、不需要额外权限。**还有一个只有踩过才知道的前提：会话里必须同时挂上 RDPDR 通道**——Windows 只在存在设备重定向通道时才给客户端建"远程音频"设备并启动 rdpsnd；只声明 RDPSND 的话通道会被接受，但服务端一个音频包都不发（IronRDP 上游 issue #1791）。所以依赖里开了 `client-rdpdr`，客户端挂一个 Noop 的 RDPDR（不重定向任何设备）。四处安卓/协议特有的坑：
  - cpal 的 Android 后端要从 `ndk_context` 取 JavaVM 与 Application Context（查 AudioTrack 缓冲用）；`nativeStart` 里手工取 `GetJavaVM`（槽 219）和 `NewGlobalRef`（槽 21）各一次，Context 必须转成全局引用
  - `libaaudio.so` 只有 API 26 才有桩库，所以 Rust 侧**按 API 26 链接**：这个 `.so` 在 Android 7.x 上加载失败，`RdpNative.isAvailable()` 会收起内置远程桌面入口（应用其它部分不受影响）
  - 上游 `ironrdp-rdpsnd-native` 默认带 opus（`libopus_sys` 要 CMake 从源码编），工程只要 PCM，所以把 0.7.0 的源码复制到 `rdpcore/third_party/` 并把默认特性留空，用 `[patch.crates-io]` 指过去——`src/` 与原版逐字节相同，只改了 `Cargo.toml`
  - **cpal 建出来的流必须 `play()`**：AAudio 上新建的输出流默认是停着的，不 `request_start()` 回调永远不触发——上游 0.7.0 漏了这一步，表现就是"音频包一直收到、就是没声音"。本地副本已补，并在启动时打一行 `RDPSND 输出流已启动`
  - 连接详情会显示「声音：已开 / 未开 · 已收 N 个音频包」，排查看这一行：远端在放声音时 N 应该一直涨；N 不动 = 服务端没发（或没在放）；N 在涨却听不见 = 手机侧音量/蓝牙路由；显示「已开」而远端仍旧没声音，先看电脑那边（静音、播放器静音、输出设备选错）
  - 日志里能确认端到端协商：`ironrdp_rdpsnd::client` 会打出 `AudioFormat(V8, …)` 与 `Training` 两条 debug 记录，随后每包音频会有一条 `RDPSND wave packet`（只记前 3 包）
- **版本号自动递增、APK 带版本名**：版本存在根目录 `version.properties`，`app/build.gradle` 在配置阶段检查请求的任务名——`assemble*` / `install*` / `bundle*` / `package*` 会先把 `versionCode` 与 `versionName` 的末位 +1 再写进配置（所以 APK 里就是新号），lint 之类的任务不触发；APK 输出名是 `工具箱-<版本>.apk`（AGP 9 的 `VariantOutput.outputFileName`）
- **构建开关在本机的 `local.properties` 里**（`rdpNative.enabled` / `cargo` / `ndk` / `lib`），仓库里的 `gradle.properties` 不放绝对路径：没装 Rust/NDK 的人照常 `assembleDebug`，`RdpNative.isAvailable()` 返回 false，界面上「远程连接」这个入口自动收起，连接簿的复制链接/导出 `.rdp` 仍可用
- **交叉编译要备齐两套工具链**，这是接 IronRDP 之后新冒出来的成本：
  - 目标侧：`aws-lc-sys`（rustls 的默认后端）带 C 与汇编，得把 NDK clang 交给 `CC_/AS_/AR_/CFLAGS_<三元组>`，且 `--sysroot` 必须是 `D:/...` 形式（MSYS 的 `/d/...` 会被当相对路径，症状是一个头文件都找不到）
  - 宿主侧：build script 本身是宿主可执行文件，链接要 Windows SDK 的 `ucrt\x64`、`um\x64` 与 VS 的 `lib\onecore\x64`，也就是 `LIB` 环境变量。要么从 Developer Prompt 里构建，要么用 `rdpNative.lib` 显式给一份；缺了它一定链不动，构建会在配置阶段直接报错而不是让你撞一个看不懂的 link 错误
- **远端光标自己画**：连接时声明支持「服务端指针」，鼠标形状因此走独立的指针更新报文，而不是由服务端烤进画面。原生层缓存形状（非预乘 ARGB）与热点，Java 侧用一层叠加 View 画在 Surface 之上——单独一层是因为鼠标模式下光标每动一次都要重画，整帧重绘得把 3 MB 位图重新刷上 Surface，拖动会明显卡；这一层只失效自己。服务端说「用默认箭头」时自己画一个（热点在箭尖），说「隐藏」时（全屏视频一类）就不画
- **控件浮在画面上，不占布局高度**（参照微软 Windows App 的会话界面）：收起时是一枚**半透明小把手贴在右边缘、垂直居中**，点开才在顶部展开完整一排——收起 / 鼠标·触控 / 键盘 / 平移 / 横屏 / 全屏 / 功能键 / 显示 / 断开（按钮多，外面套了一层横向滚动，窄屏也挤得下）；状态行只在展开时显示，出错会自动展开。「断开」有二次确认，断开会话就退回上一页；会话已经结束时它变成「重连」。手势提示在切换模式时出现 4 秒后淡出，不再常驻挡桌面；鼠标模式底部那排按键（左/右/中/滚轮）已去掉，触摸板手势本身已经覆盖
- **自适应分辨率**（显示对话框里的勾选）：按手机**当前方向**的实际像素请求桌面（竖屏 1080×2340、横屏 2340×1080），1:1 不缩放；旋转后 400 ms 左右自动按新尺寸重连（分辨率是握手时定的，MS-RDPEDISP 在这台主机上没人理）。勾选后分辨率预设会灰掉
- **IPv6 也能连**：地址栏收 `fe80::1` 或 `[fe80::1]`（方括号在入库时去掉，同一台机器只有一种写法）；拼 `host:port` 时按协议补成 `[v6]:port`，导出 `.rdp` 与复制 `freerdp://` 链接同理
- **域名也能连**：连接簿的地址栏收 IP 或主机名。非字面量地址先在本进程解析（优先 IPv4：家用局域网里 IPv4 更稳，而且凭据是按扫描页看到的那个 IP 存的），状态行显示 `XXS → 192.168.5.73:3389`；按域名连时如果域名下没有账号，会回退去查解析出来的 IP 那一条，不至于因为写法不同再输一次密码。域名解析页长按域名还有「用远程桌面连这台」
- **横屏 / 全屏**：横屏按钮切到 `SENSOR_LANDSCAPE`（两个方向都认），再按回竖屏；全屏按钮走 immersive sticky，把状态栏与导航栏一起藏掉，系统标题栏在进页面时就隐藏了
- **双指缩放 + 双指滚动**：双指手势先判意图——两指间距变化大是缩放（最多 4×），中点位移大是滚动（两指上滑内容跟着上去，和手机一致）；判过一次就锁定，不会中途改主意。放大时焦点下的远端像素保持不动；**放大后视角会跟着光标走**——光标靠近边缘时画面朝那个方向推（不跳到中心，像摄像机跟拍），捏合/双击结束时光标若已整只在视野外则直接居中过去；平移控件开着时单指拖动是挪画面而不是点击（放大后必须能选一种）；平移模式下双击在 1× 与 2× 之间切换。平移量夹在「画面仍铺满视图」的范围内，拖到边界不会露黑边
- **画面两种缩放**：等比适应（留黑边，默认）或拉满屏幕。所有坐标换算都走同一个 `Layout`：横竖两轴分开存缩放比，拉满时两轴不同，只记一个 scale 的话点哪儿和实际点到的地方会系统性地偏。IronRDP 交出来的帧是 `0x00RRGGBB`，Java 的 `ARGB_8888` 只要补上不透明 alpha，**不需要换红蓝通道**（真机核对过颜色）
- **画质（16 位色）**：「流畅」一档让服务端按 RGB565 发位图，位图载荷减半，公网带宽紧张时最有效的一档；色深是连接时协商的，改完自动重连
- **分辨率**：预设 **1920×1080（默认）** / 1600×900 / 1366×768 / 1280×800 / 1024×768，另有一项「匹配本机」按手机物理像素算一个横屏桌面（远端一个像素对屏幕一个像素，字最锐）；「自适应手机当前分辨率」是独立的勾选，默认关。改尺寸走**重连**而不是 MS-RDPEDISP 的动态调整：协议里那条路 IronRDP 已经接上，但这台 Windows 11 主机实测完全不理那个 PDU（发出去既没有回执，也没有 Deactivation-Reactivation），所以选确定能成的做法，两三秒换一个必然生效的结果
- **会话页不锁方向且声明了 `configChanges`**：原生会话线程活在进程里，Activity 一重建就会出现「画面还在、会话已断」的错乱；远端是桌面，横屏也确实更有用
- 已验证（Mi 10 Pro → 192.168.5.73，Windows 11，强制 NLA）：`已连通`，桌面铺满整屏、控件浮在上面；横屏一键切换、全屏后桌面顶到四边；**平移模式下双击放大到 2×**、单指拖动跟手且拖到边界停住；桌面、壁纸、任务栏、图标全部正常；触控模式单击开得出开始菜单、双击启动 Edge；**鼠标模式**拖动把光标推到开始按钮上（光标跟着走，形状随上下文变：桌面是箭头、文本输入框上是 I 形）、再按「左键」点开菜单；**应用内键盘**打进去的 `notepad` 出现在远端搜索框；**锁存 Win 后按 E** 打开资源管理器；**Ctrl+Alt+Del** 唤出安全界面；**分辨率**从 1920×886 切到 1024×768 后重连生效；**拉满屏幕**立即重绘；退出会话页无崩溃、无 native abort。代价是体积：`librdpcore.so` arm64 约 13.9 MB、armv7 约 9.65 MB（未压缩）
- **还没做的**：位图缓存与 RemoteFX/RFX 编码协商（只走默认位图更新）、剪贴板同步、多路并发会话（原生侧是一份全局 `SESSION`）、`Pause`/`PrintScreen` 这类双前缀键


## 依赖

| 库 | 许可 | 用途 |
| --- | --- | --- |
| `IronRDP` 0.17（crate `ironrdp`，经 `rdpcore/` 编成 `librdpcore.so`） | Apache-2.0 / MIT 双许可 | 内置远程桌面的 RDP 协议层（含 NLA/CredSSP、rustls TLS 后端） |
| `com.github.mik3y:usb-serial-for-android:3.9.0`（JitPack） | MIT | 「固件下载」的 USB 转串口通信 |
| `org.codelibs:jcifs:3.0.4`（jcifs-ng，Central/阿里云镜像） | **LGPL-2.1** | 「局域网扫描」的 SMB 客户端，默认引擎 |
| `com.hierynomus:smbj:0.15.0` | Apache-2.0 | SMB 读文件的可选实验引擎（见「共享文件夹 · 预览」） |
| `androidx.compose.*`（BOM 2026.09.00）+ `androidx.activity:activity-compose` | Apache-2.0 | 共享浏览页与预览页 |
| `io.coil-kt.coil3:coil-compose:3.6.3` | Apache-2.0 | 缩略图与全屏图的加载、内存缓存 |
| `androidx.media3:media3-exoplayer / media3-ui:1.11.1` | Apache-2.0 | 视频与音频播放 |
| `com.android.tools:desugar_jdk_libs:2.1.5` | Android SDK | 核心库脱糖（只为 SMBJ 的 `java.time` 引用兜底） |

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
- `android.useAndroidX=true`：Coil / Media3 / Compose 都建立在 AndroidX 上，无法回避。分贝仪、域名解析、固件下载、局域网扫描、远程桌面这几页**仍然只用系统控件**，一行 androidx 都没引，打开这个开关不影响它们编译与运行
- `settings.gradle` 增加了 `maven { url 'https://jitpack.io' }` 并用 `content { includeGroup 'com.github.mik3y' }` 限定只从它取这一个坐标（阿里云镜像上没有该库），其余依赖仍走阿里云镜像
- 想支持更多 USB 串口芯片：`res/xml/device_filter.xml` 里加对应 VID/PID 即可，驱动库本身已内置识别

**内置远程桌面的协议层用 IronRDP**（`Devolutions/IronRDP`，Apache-2.0 / MIT 双许可，与本项目其它依赖不冲突），选它而不是自己写协议的理由很直接：这台目标机实测强制 NLA，而 CredSSP（NTLM + Kerberos）是 IronRDP 已经实现完、自己写正确性代价最高的一段。`rdpcore/` 只是把它包成安卓能用的样子：会话线程、帧缓冲、手写 JNI、输入编码。构建它需要 rustup + Android targets + NDK，外加宿主侧的 Windows SDK（build script 要链 PE），因此开关默认关闭，不影响没有这套工具链的人编译本工程。

## 开源项目与致谢

这个工程站在一批成熟开源项目的肩膀上。按用途逐项列出并致谢（许可均为允许再分发/商用的类型，完整依赖见上一节）。

### 远程桌面

- **[IronRDP](https://github.com/Devolutions/IronRDP)**（Devolutions / Teleport 维护，Apache-2.0 / MIT）——内置远程桌面的协议层：NLA/CredSSP、TLS、位图与指针、输入编码、RDPSND 音频都由它实现；`rdpcore/` 只是把它的 facade 包成安卓可用的会话线程与手写 JNI。`rdpcore/third_party/ironrdp-rdpsnd-native` 是它 0.7.0 的本地补丁副本（关掉 opus 默认特性、补上 `stream.play()`），许可不变。特别感谢上游 issue #1791 指出「会话里没有 RDPDR 通道时 Windows 不会推音频」，这条让我们少走了很久弯路
- **[FreeRDP](https://github.com/FreeRDP/FreeRDP)**（Apache-2.0）——没有链接它的代码，但 RDPSND 的协商顺序、滚轮与指针事件语义都以它为对照实现；aFreeRDP 也是早期「交给外部客户端」那条路的依托
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

- **[tokio](https://github.com/tokio-rs/tokio)**（MIT）、**[rustls](https://github.com/rustls/rustls)**（Apache-2.0/ISC）、**[aws-lc](https://github.com/aws/aws-lc)**（Apache-2.0/ISC）——异步运行时与 TLS
- **[cpal](https://github.com/RustAudio/cpal)**（Apache-2.0）——安卓 AAudio 音频输出
- **[ndk-rs](https://github.com/rust-mobile/ndk)** / **[ndk-context](https://github.com/rust-mobile/ndk)**（Apache-2.0/MIT）——安卓原生音频接口
- **[jni-rs](https://github.com/jni-rs/jni-rs)**（MIT/Apache-2.0）、**[log](https://github.com/rust-lang/log)** / **[tracing](https://github.com/tokio-rs/tracing)** / **[android_logger](https://github.com/nercury/android_logger-rs)**——Java 互操作与日志转发

### 交互与设计参考

- **Windows「远程桌面连接」(mstsc)** 与 **Windows App**——连接质量对话框、触摸板手势与会话界面的参考。只观察运行界面，不反编译、不复制资源
- **[KDE Connect](https://kdeconnect.kde.org/)、[LocalSend](https://localsend.org/)、[Syncthing](https://syncthing.net/)**——共享页的多选、收藏、传输队列等交互的灵感（见 CHANGELOG v1.19）

### 特别感谢

- Devolutions 与 Teleport 维护的 IronRDP，以及所有提交 issue / PR 的贡献者
- 上面每个项目的作者与社区：没有这些现成的轮子，这个「工具箱」里要自己写的协议栈会多出几个数量级

如果这里漏掉了某个项目，欢迎指出，我会补上。

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

只有打开内置远程桌面（`local.properties` 里 `rdpNative.enabled=true`）才额外需要：rustup（含 `aarch64-linux-android` / `armv7-linux-androideabi` targets）、Android NDK（本工程实测 `ndk;29.0.13113456`）、以及宿主侧能链接 PE 的 Windows SDK + MSVC 运行库（给依赖树里的 build script 用）。这几样齐了之后 `assembleDebug` 会顺带交叉编译 `rdpcore` 并把 `.so` 放进 `jniLibs`；缺任何一样，构建会在配置阶段直接说明缺什么，而不是让链接器报一堆看不懂的错。

说明：
- `settings.gradle` 已配置阿里云镜像仓库（直连 dl.google.com 会 TLS 握手失败）
- 工具链：Gradle 9.8.0 + AGP 9.4.0 + compileSdk 37 + build-tools 36.0.0；命令行构建用 JDK 21（`JAVA_HOME` 指向任意 17+ 的 JDK 亦可）
- 首次构建需要 `local.properties` 里的 `sdk.dir` 指向本机 Android SDK 根目录；该文件不入库，由 AS 打开工程时自动生成

产物：`app/build/outputs/apk/debug/工具箱-<版本>.apk`（可直接安装，minSdk 24 / Android 7.0+）。版本号存在根目录 `version.properties`，每次执行 `assemble*` / `install*` / `bundle*` 前自动把 `versionCode` 与 `versionName` 末位 +1，APK 文件名随之带版本；`lint` 等任务不会触发自增。

## 安装到手机

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
  RdpActivity.java    # 远程桌面连接簿：增删改、内置连接、端口测试、导出 .rdp、复制链接
  RdpSessionActivity.java # 远程连接会话页：轮询取帧、缩放/平移、两种指针模式、键盘与功能键
  RdpNative.java      # librdpcore.so 的 Java 封装：加载失败时自动收起内置入口
  RdpConnection.java  # 一条连接（名称/地址/端口），凭据不在此处
  RdpBook.java        # 连接簿持久化，重复目标会被挡下
  RdpLauncher.java    # freerdp:// 链接构造、TCP 连通测试、.rdp 文件写出与分享
  CredentialStore.java # Android Keystore AES-256/GCM 加密的凭据存储 + 会话内存
  CredentialDialog.java # 用户名/域/密码/记住 的共用对话框（扫描页、共享页、连接簿都用它）
  LocalFileProvider.java # 自建的只读文件出口（工程不引 AndroidX，故不用 androidx FileProvider）
  FileShare.java      # 用其他应用打开 / 分享一个私有文件（content URI + 单次读授权）
  FileUtil.java       # 大小显示、扩展名→MIME、落盘文件名安全化
  BaseActivity.java   # 语言切换菜单基类
  LocaleHelper.java   # 应用内语言持久化与 Locale 包装
  NoiseMeter.java     # AudioRecord 采集线程 + RMS→dBFS + FFT 频段回调
  MeterView.java      # 30–120 dB 刻度条自定义 View
  CurveView.java      # 60 秒滚动分贝曲线自定义 View
  SpectrumAnalyzer.java # Hann 窗 + radix-2 FFT + 1/1 倍频程能量聚合（纯 Java，无依赖）
  SpectrumView.java   # 频段柱状图自定义 View（自动量程、峰值记忆、主频段高亮）
rdpcore/               # 内置远程桌面的原生层（Rust，协议层是 IronRDP）
  Cargo.toml           # cdylib；默认不参与构建，开关在 local.properties 的 rdpNative.enabled
  src/lib.rs           # 手写 JNI（槽位下标按 NDK 的 jni.h 数出）+ 会话线程 + 帧缓冲 + 输入编码
app/src/main/jniLibs/<abi>/librdpcore.so # 上面编出来的产物，已进 .gitignore
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

- **权限只有三项**：`RECORD_AUDIO`（分贝仪）、`INTERNET`（域名解析、局域网扫描、共享访问）、`ACCESS_NETWORK_STATE`（局域网扫描读本机链路地址）。三项都是安装即授予的普通权限，运行期只会弹一次麦克风授权；固件下载不需要存储权限，共享文件的读与写也不需要——选文件走系统文件选择器（SAF），下载落在应用私有目录。
- **局域网扫描是真实网络行为**：一轮扫描会向网段内 253 个地址各发若干 TCP SYN，外加一次 UDP 137 广播。家里和公司内网无所谓，但公共 WiFi、别人家的网络或严格按规章管理的内网会把这种流量判成扫描行为并告警——不确定就别扫。
- **共享页的写操作是真的写**：删除直接在共享上生效、撤不回来（有二次确认，文案里写明目录会连带内容一起没了）；上传遇到同名文件自动改成 `照片 (2).jpg`，不覆盖远端已有文件。
- **下载的文件在应用私有目录**（`Android/data/<包名>/files/Download`），卸载即消失；要留在手机上请用「另存为…」写到公共位置。给别的 App 打开时通过自建的只读 `LocalFileProvider` 单次授权，不开放任何目录。
- **局域网凭据只加密存本机**：勾「记住密码」时用 Android Keystore 的 AES-256/GCM 加密后写 SharedPreferences，密钥不可导出，落盘只有 IV + 密文与认证标签。工程开了 `allowBackup`，SharedPreferences 会随备份走、Keystore 密钥不会，所以换机恢复后旧条目必然解不开——按「没存过」处理，重新登录一次即可。不勾就只活在进程内存里，页面间传递也不经过 Intent。`.rdp` 文件与 `freerdp://` 链接都不带密码（前者的 `password 51:b:` 只是可逆编码，等于明文）。
- **SMB1 默认关闭**：只在「兼容设置」里为老设备临时打开，界面上也写明了它是明文、签名不强制。老 NAS 谈不拢版本时的报错与「权限不足」长得一样，所以协议错误单独归了一类文案。
- **数据只存本机**：域名列表、校准偏移、ISP 参数、收藏设备、连接簿全部写 `SharedPreferences`，不联网上传、不含任何预置的第三方域名或 IP；卸载即清除。
- **域名解析会发起真实 DNS 查询**：`InetAddress.getAllByName` 走系统解析器，你输入的域名会被发往当前网络的 DNS 服务器。
- **分贝精度**：手机麦克风无声学标定，绝对精度约 ±3–10 dB，详见「测量精度说明」；本应用是参考级工具，不能替代声级计。
- **固件下载有变砖风险**：烧录会擦除片内 Flash，选项字节改写可能导致读保护、调试口关闭等后果；操作前先用「读回」备份原片内容，并确保供电稳定、串口线共地。BOOT0/NRST 自动时序依赖接线正确，接错只是进不去 Bootloader，但误改选项字节需要额外手段恢复。
- **USB 功能只能真机验证**：Android 模拟器没有 USB 宿主，`UsbManager`/OTG 相关路径（固件下载）必须在带 OTG 的实机上测；CH340/CH341、CP210x、FTDI 由 `usb-serial-for-android` 驱动。
- **内置远程桌面默认不构建，且体积不小**：开关在本机 `local.properties` 的 `rdpNative.enabled`（仓库里不放），没装 Rust/NDK/Windows SDK 的人编译不受影响，界面上那个「远程连接」入口会自动收起。打开后 `librdpcore.so` 是 arm64 约 13.9 MB、armv7 约 9.65 MB（IronRDP + rustls + aws-lc 的代价）。
- **内置通道已连上真机验证，但功能面还窄**：触控/鼠标两种指针模式、锁存修饰键的功能键面板、Ctrl+Alt+Del、分辨率与缩放都实测有效（Windows 11 桌面上开过开始菜单、打出过 notepad、用锁存的 Win 打开过资源管理器、唤出过安全界面）；此外远端光标是自绘的独立图层（形状随服务端下发变化）；还没有的是位图缓存与 RemoteFX、剪贴板同步，且原生侧只有一份全局会话状态（同时只能连一台）。
- **强制竖屏**：所有 Activity 都固定 portrait，避免旋转导致采集线程与 USB 会话重建，因此 lint 的 `LockedOrientationActivity` 提示属刻意取舍；Android 16 起该属性在多数场景会被忽略，届时需要改成真正适配横竖屏。两个例外都不锁方向并声明 `configChanges`：内置远程桌面会话页（原生会话线程活在进程里，Activity 重建会让画面与会话对不上）和共享文件预览页（视频横屏才有意义，重建会拆掉播放器与本机回环服务，看起来就是「转一下方向就重播」）。
- **targetSdk 仍是 34**：上调前需回归前台服务、通知权限与 USB 授权行为。

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
