//! 工具箱内置远程桌面的原生层：IronRDP 会话 + 帧缓冲 + 输入转发，经手写 JNI 暴露给 Java。
//!
//! 协议层不自研。目标虚拟机实测强制 NLA（请求标准 RDP 或纯 TLS 时服务端直接回
//! `HYBRID_REQUIRED_BY_SERVER`），而 NLA/CredSSP（NTLM + Kerberos）正是 IronRDP 已经实现完的
//! 部分——自己写这一层的正确性代价远高于接一个维护中的库。TLS 后端选 rustls（纯 Rust）：
//! 选 native-tls 就得在安卓上交叉编译 OpenSSL，那正是当年 FreeRDP 路线卡死的地方。
//!
//! JNI 那层仍是手写而不是用 `jni` crate。理由已经不是"宿主链不动 build script"（Windows SDK
//! 装好后那条路通了），而是这一层已经在真机上验证过：字符串与整型数组的跨边界、帧号增量、
//! 输入计数都跑通过，换成另一套写法等于把已经排除的风险重新引入一遍。函数表槽位下标用脚本
//! 按 NDK 自带的 `jni.h` 数出来，并与规范里可查的槽位互相印证（GetVersion=4、FindClass=6、
//! GetStringUTFLength=168、GetStringUTFChars=169、ReleaseStringUTFChars=170、NewByteArray=176、
//! SetByteArrayRegion=208、SetIntArrayRegion=211）。改任何一个都要重新对表。
//!
//! 文本跨边界一律用 UTF-8 字节数组，不用 `NewStringUTF`：那个函数要的是「修改版 UTF-8」，
//! 中文机器名或账号会出乱码。
//!
//! 线程模型：一条原生线程持有 tokio 当前线程运行时并驱动 `RdpClient::run()`，会话事件
//! （帧、失败、断开）通过 channel 汇到同一处再写进共享状态；Java 侧按帧轮询。刻意不做
//! 「回调进 JVM」——那要管 JavaVM 句柄与 AttachCurrentThread，生命周期一错就是随机崩溃。

use ironrdp::client::config::{ConfigBuilder, Destination};
use ironrdp::client::rdp::{RdpClient, RdpInputEvent, RdpOutputEvent};
use ironrdp::input::{Database, MouseButton, MousePosition, Operation, Scancode, WheelRotations};
use ironrdp::pdu::input::fast_path::FastPathInputEvent;
use ironrdp::pdu::input::mouse::PointerFlags;
use ironrdp::pdu::rdp::capability_sets::MajorPlatformType;
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::os::raw::{c_char, c_int, c_void};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::Duration;
use tokio::sync::mpsc;

/// 与 Java 侧 `RdpNative.STATE_*` 一一对应，改一边必须改另一边。
const STATE_IDLE: i32 = 0;
const STATE_CONNECTING: i32 = 1;
const STATE_RUNNING: i32 = 2;
const STATE_FAILED: i32 = 3;
const STATE_CLOSED: i32 = 4;

/// 会话还没给出尺寸时用的兜底画布，只为让界面有东西可画。
const FALLBACK_W: usize = 1024;
const FALLBACK_H: usize = 768;

/// 默认桌面尺寸（Java 侧没给合法值时用）。
const DEFAULT_W: u16 = 1280;
const DEFAULT_H: u16 = 800;

type Jsize = c_int;
/// JNI 的 jlong，按 i64 传（与 Java 的 long 同宽）
type Jlong = i64;
type JObject = *mut c_void;
type JString = *mut c_void;
type JByteArray = *mut c_void;
type JIntArray = *mut c_void;
/// JNIEnv 是指向函数表的指针（`const struct JNINativeInterface**`）。
type JNIEnv = *mut *const JniFn;

type FnGetStringUtfLength = unsafe extern "C" fn(JNIEnv, JString) -> Jsize;
type FnGetStringUtfChars = unsafe extern "C" fn(JNIEnv, JString, *mut u8) -> *const c_char;
type FnReleaseStringUtfChars = unsafe extern "C" fn(JNIEnv, JString, *const c_char);
type FnGetJavaVm = unsafe extern "C" fn(JNIEnv, *mut *mut c_void) -> Jsize;
type FnNewGlobalRef = unsafe extern "C" fn(JNIEnv, JObject) -> JObject;

/// cpal 的 Android 后端（AAudio）要从 ndk-context 里取 JavaVM + Context，只初始化一次。
static ANDROID_CONTEXT_READY: AtomicBool = AtomicBool::new(false);
/// 日志转发到 logcat，只装一次。
static LOGGER_READY: AtomicBool = AtomicBool::new(false);
/// 本次会话是否打开了声音通道（ndk-context 初始化成功 = RDPSND 已协商）。
static AUDIO_ENABLED: AtomicBool = AtomicBool::new(false);
type FnNewByteArray = unsafe extern "C" fn(JNIEnv, Jsize) -> JByteArray;
type FnSetByteArrayRegion = unsafe extern "C" fn(JNIEnv, JByteArray, Jsize, Jsize, *const i8);
type FnSetIntArrayRegion = unsafe extern "C" fn(JNIEnv, JIntArray, Jsize, Jsize, *const Jsize);

/// JNI 函数表：按 `jni.h` 的字段顺序展开成等长的指针数组，再用槽位下标取具体函数。
#[repr(C)]
pub struct JniFn {
    slots: [*const c_void; JNI_SLOT_COUNT],
}

const JNI_SLOT_COUNT: usize = 233;
const SLOT_NEW_GLOBAL_REF: usize = 21;
const SLOT_GET_STRING_UTF_LENGTH: usize = 168;
const SLOT_GET_STRING_UTF_CHARS: usize = 169;
const SLOT_RELEASE_STRING_UTF_CHARS: usize = 170;
const SLOT_NEW_BYTE_ARRAY: usize = 176;
const SLOT_SET_BYTE_ARRAY_REGION: usize = 208;
const SLOT_SET_INT_ARRAY_REGION: usize = 211;
const SLOT_GET_JAVA_VM: usize = 219;

fn slot(env: JNIEnv, index: usize) -> *const c_void {
    unsafe { (*(*env)).slots[index] }
}

unsafe fn new_byte_string(env: JNIEnv, text: &str) -> JByteArray {
    let bytes = text.as_bytes();
    let table = *env;
    let new_array = std::mem::transmute::<*const c_void, FnNewByteArray>(
        (*table).slots[SLOT_NEW_BYTE_ARRAY],
    );
    let set_region = std::mem::transmute::<*const c_void, FnSetByteArrayRegion>(
        (*table).slots[SLOT_SET_BYTE_ARRAY_REGION],
    );
    let array = new_array(env, bytes.len() as Jsize);
    if array.is_null() {
        return std::ptr::null_mut();
    }
    set_region(env, array, 0, bytes.len() as Jsize, bytes.as_ptr() as *const i8);
    array
}

unsafe fn read_java_string(env: JNIEnv, text: JString) -> Option<String> {
    if text.is_null() {
        return None;
    }
    let table = *env;
    let get_length = std::mem::transmute::<*const c_void, FnGetStringUtfLength>(
        (*table).slots[SLOT_GET_STRING_UTF_LENGTH],
    );
    let get_chars = std::mem::transmute::<*const c_void, FnGetStringUtfChars>(
        (*table).slots[SLOT_GET_STRING_UTF_CHARS],
    );
    let release = std::mem::transmute::<*const c_void, FnReleaseStringUtfChars>(
        (*table).slots[SLOT_RELEASE_STRING_UTF_CHARS],
    );
    let length = get_length(env, text);
    if length <= 0 {
        return Some(String::new());
    }
    // isCopy 出参：不需要区分是否拷贝，但按签名必须给一个地址
    let mut is_copy: u8 = 0;
    let raw = get_chars(env, text, &mut is_copy as *mut u8);
    if raw.is_null() {
        return None;
    }
    let slice = std::slice::from_raw_parts(raw as *const u8, length as usize);
    let owned = String::from_utf8_lossy(slice).to_string();
    release(env, text, raw);
    Some(owned)
}

/// 把 Java 侧的 Application Context 交给 ndk-context——cpal 的 Android 后端（AAudio）要用它
/// 查 AudioManager / AudioTrack 的缓冲大小。
///
/// 必须换成一个**全局引用**：ndk-context 存的是裸指针，局部引用在这次 JNI 调用返回后就失效了。
/// 只初始化一次（函数内部会断言没有第二次），重复调用直接返回 true。
/// 装 logcat 日志：依赖树里的 `tracing` 开着 `log` 特性，装了这个才能看到 cpal/RDPSND
/// 的 error!/warn!（声音不响时唯一的线索）。
fn init_logging() {
    if LOGGER_READY.swap(true, Ordering::SeqCst) {
        return;
    }
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("rdpcore"),
    );
}

unsafe fn install_android_context(env: JNIEnv, context: JObject) -> bool {
    if ANDROID_CONTEXT_READY.load(Ordering::SeqCst) {
        return true;
    }
    if env.is_null() || context.is_null() {
        return false;
    }
    let table = *env;
    let get_vm = std::mem::transmute::<*const c_void, FnGetJavaVm>(
        (*table).slots[SLOT_GET_JAVA_VM],
    );
    let new_ref = std::mem::transmute::<*const c_void, FnNewGlobalRef>(
        (*table).slots[SLOT_NEW_GLOBAL_REF],
    );
    let mut vm: *mut c_void = std::ptr::null_mut();
    if get_vm(env, &mut vm) != 0 || vm.is_null() {
        return false;
    }
    let global = new_ref(env, context);
    if global.is_null() {
        return false;
    }
    ndk_context::initialize_android_context(vm, global);
    ANDROID_CONTEXT_READY.store(true, Ordering::SeqCst);
    true
}

/// 会话共享状态。所有字段只在持锁时读写。
struct Session {
    state: i32,
    error: String,
    width: usize,
    height: usize,
    /// ARGB 像素，直接由 IronRDP 的 `RdpOutputEvent::Image` 交过来
    frame: Vec<u32>,
    /// 帧序号，Java 侧据此判断有没有新画面要取
    serial: u64,
    pointer_events: u64,
    key_events: u64,
    last_pointer: String,
    /// 收到多少帧、累计多少字节（按像素数×4 估）：用来在连接详情里看真实的帧率与流量
    frames: u64,
    frame_bytes: u64,
    /// 预检那次 TCP 握手的往返耗时（毫秒），0 表示还没测到
    connect_ms: u64,
}

const fn empty_session() -> Session {
    Session {
        state: STATE_IDLE,
        error: String::new(),
        width: 0,
        height: 0,
        frame: Vec::new(),
        serial: 0,
        pointer_events: 0,
        key_events: 0,
        frames: 0,
        frame_bytes: 0,
        connect_ms: 0,
        last_pointer: String::new(),
    }
}

static SESSION: Mutex<Session> = Mutex::new(empty_session());
static STOP: AtomicBool = AtomicBool::new(false);
/// 输入事件通道：会话起来之后才有值，界面在连接前点到的输入会被丢弃而不是报错。
static INPUT: Mutex<Option<mpsc::UnboundedSender<RdpInputEvent>>> = Mutex::new(None);
/// 键鼠状态由 IronRDP 的 `Database` 维护，而不是自己记：它负责"按下过就不再重复发按下"
/// "扩展键位 E0 前缀""抬起时不带 MOVE 位"这些容易写错的细节，还带 `release_all`
/// （切到后台时不释放的话，远端会一直以为 Ctrl 还按着）。
/// `Database::new()` 不是 const fn，所以静态项用 OnceLock 兜一层。
static INPUT_DB: OnceLock<Mutex<Database>> = OnceLock::new();

/// 服务端下发的指针形状。
///
/// RDP 里光标默认不由服务端画进画面：连接时我们声明支持"服务端指针"，于是鼠标形状走独立的
/// 指针更新报文（`RdpOutputEvent::PointerBitmap`）。不实现这一段的话，鼠标模式下能移动、能点，
/// 但屏幕上根本看不见光标。
struct PointerShape {
    /// 变了才让 Java 重新取一次形状，逐帧比形状本身便宜得多
    serial: u32,
    /// 0 = 显示服务端给的位图；1 = 服务端要求隐藏；2 = 用客户端默认箭头
    kind: i32,
    width: u16,
    height: u16,
    hotspot_x: u16,
    hotspot_y: u16,
    /// 非预乘 alpha 的 ARGB，与 Android 的 `ARGB_8888` 直接对得上
    pixels: Vec<u32>,
}

impl PointerShape {
    const fn new() -> Self {
        Self {
            serial: 0,
            kind: 2,
            width: 0,
            height: 0,
            hotspot_x: 0,
            hotspot_y: 0,
            pixels: Vec::new(),
        }
    }
}

static POINTER: Mutex<PointerShape> = Mutex::new(PointerShape::new());

static THREADS_LIVE: AtomicUsize = AtomicUsize::new(0);

fn db() -> std::sync::MutexGuard<'static, Database> {
    let lock = INPUT_DB.get_or_init(|| Mutex::new(Database::new()));
    match lock.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    }
}

fn lock() -> std::sync::MutexGuard<'static, Session> {
    // 只有这一把锁，持锁期间不做任何阻塞 I/O，中毒只可能来自 panic；
    // 恢复内部值继续跑比让整个 App 崩掉更有用
    match SESSION.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    }
}

fn set_error(text: String, state: i32) {
    let mut s = lock();
    s.error = text;
    s.state = state;
}

/// 主机名与 `IP:端口` 都支持：能直接解析成地址就不走 DNS，省一次解析等待。
/// `host:port`。IPv6 字面量必须加方括号，否则 `SocketAddr` 与 DNS 解析器都看不懂
/// （`fe80::1:3389` 会被当成一个带冒号的域名）。
fn endpoint_of(host: &str, port: u16) -> String {
    if host.contains(':') && !host.starts_with('[') {
        format!("[{}]:{}", host, port)
    } else {
        format!("{}:{}", host, port)
    }
}

fn resolve(endpoint: &str) -> std::io::Result<SocketAddr> {
    if let Ok(addr) = endpoint.parse() {
        return Ok(addr);
    }
    let mut iter = endpoint.to_socket_addrs()?;
    iter.next()
        .ok_or_else(|| std::io::Error::new(std::io::ErrorKind::AddrNotAvailable, "无解析结果"))
}

/// 连接前的 TCP 预检。
///
/// 为什么还要多这一步：IronRDP 连不上时给出的 `ConnectorError` 常常笼统（"io error"），
/// 而「端口不通」和「握手被拒」对用户是两个完全不同的下一步动作。预检一次只花几毫秒，
/// 换来一条能直接照着做的提示。
/// 返回 (错误文案, 握手耗时)。耗时是 TCP 三次握手的往返，连接详情里的「往返时间」就用它。
fn pre_connect(endpoint: &str) -> (Option<String>, u64) {
    match resolve(endpoint) {
        Ok(addr) => {
            let started = std::time::Instant::now();
            match TcpStream::connect_timeout(&addr, Duration::from_millis(4000)) {
                Ok(_) => (None, started.elapsed().as_millis() as u64),
                Err(e) => (
                    Some(format!("{} 无法建立 TCP 连接：{}", endpoint, e)),
                    started.elapsed().as_millis() as u64,
                ),
            }
        }
        Err(e) => (Some(format!("{} 无法解析：{}", endpoint, e)), 0),
    }
}

struct Params {
    host: String,
    port: u16,
    user: String,
    domain: String,
    password: String,
    device: String,
    width: u16,
    height: u16,
    /// 16 或 32：16 位色能把位图载荷砍掉一半，公网带宽紧张时最有效的一档
    color_depth: u16,
    /// 是否接受 RemoteFX 有损编码：省流档开、清晰档关。关掉之后位图载荷变大，但文字更锐利
    lossy: bool,
    /// 能否放声音：ndk-context 初始化成功才开 RDPSND，否则服务端发来的音频没人接
    sound: bool,
}

fn session_thread(params: Params) {
    THREADS_LIVE.fetch_add(1, Ordering::SeqCst);
    {
        let mut s = lock();
        s.state = STATE_CONNECTING;
        s.error.clear();
    }
    *INPUT.lock().unwrap_or_else(|p| p.into_inner()) = None;
    // 新会话的远端按键状态是全新的，本机记着的"还按着"必须清掉，否则第一次按下会被
    // Database 当成重复按下而跳过
    *db() = Database::new();
    {
        // 上个会话留下的光标形状不能带进新会话；序号加一让界面重新取一次
        let mut shape = POINTER.lock().unwrap_or_else(|p| p.into_inner());
        shape.kind = 2;
        shape.width = 0;
        shape.height = 0;
        shape.pixels.clear();
        shape.serial = shape.serial.wrapping_add(1);
    }

    let endpoint = endpoint_of(&params.host, params.port);
    let (failure, connect_ms) = pre_connect(&endpoint);
    if let Some(message) = failure {
        set_error(message, STATE_FAILED);
        THREADS_LIVE.fetch_sub(1, Ordering::SeqCst);
        return;
    }
    lock().connect_ms = connect_ms;

    let runtime = match tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
    {
        Ok(rt) => rt,
        Err(e) => {
            set_error(format!("无法创建异步运行时：{}", e), STATE_FAILED);
            THREADS_LIVE.fetch_sub(1, Ordering::SeqCst);
            return;
        }
    };

    runtime.block_on(async move {
        // 电脑名上限是协议里的 32 字节 UTF-16（含结束符），也就是 15 个字符；
        // 手机型号名常常更长，截断比整条连接失败好，实在没有名字就用一个固定的
        let mut device = params.device.chars().take(15).collect::<String>();
        if device.is_empty() {
            device = "ToolKit".to_string();
        }
        let config = match ConfigBuilder::new()
            .with_destination(Destination::from_parts(params.host, params.port))
            .with_username(params.user)
            .with_domain(params.domain)
            .with_password(params.password)
            // 目标机强制 NLA：CredSSP 关掉就只会收到 HYBRID_REQUIRED_BY_SERVER
            .with_credssp(true)
            .with_tls(true)
            .with_desktop_width(params.width)
            .with_desktop_height(params.height)
            .with_color_depth(u32::from(params.color_depth))
            // 清晰档不广告 RemoteFX：服务端只能发位图，文字不糊，代价是载荷变大。
            // 空列表表示用默认 codec 集合（RemoteFX + QOI/QOIZ）
            .with_codecs(if params.lossy {
                Vec::new()
            } else {
                vec!["remotefx:off".to_string()]
            })
            // 空闲 20 秒补一次"原地鼠标移动"：既让 NAT/防火墙那层不断流，也让服务端没了
            // （电脑重启、休眠后换了会话）时这一笔写入尽早撞上 RST，界面才能在几十秒内
            // 从"已连通"变成"已断开"——不发任何东西的话 TCP 是死的也永远发现不了
            .with_fake_events_interval(Duration::from_secs(20))
            // RDPSND：客户端声明 PCM 44100/16bit 立体声，服务端把音频从这条静态通道推过来，
            // 由 ironrdp-client 里的 cpal 后端直接交给 AAudio 播放
            .with_sound(params.sound)
            // build() 认为这四个是"由前端提供"的必填项，缺一个就直接报 missing required
            // configuration。平台如实报 ANDROID；build/目录只是 Client Info 里的装饰字段，
            // 沿用 FreeRDP 一贯的值，免得服务端因为一个陌生的组合走奇怪的分支
            .with_platform(MajorPlatformType::ANDROID)
            .with_client_name(device)
            .with_client_build(2600)
            .with_client_dir("C:\\Windows")
            .build()
        {
            Ok(cfg) => cfg,
            Err(e) => {
                // {:#} 是 anyhow 的整条原因链；默认的 Display 只给最外面一句
                set_error(format!("连接参数不成立：{:#}", e), STATE_FAILED);
                return;
            }
        };

        log::info!("RDPSND 通道：{}", config.channels().sound);
        // 容量 2：帧是「最新一帧有意义」的数据，队列深了只会让界面滞后于远端
        let (tx, mut rx) = mpsc::channel::<RdpOutputEvent>(2);
        let client = RdpClient::new(config, tx);
        let input = client.input_sender();
        *INPUT.lock().unwrap_or_else(|p| p.into_inner()) = Some(input.clone());
        // 不 spawn，而是把 run() 钉在当前任务里和 rx 一起 select。
        // 一是 spawn 要求 run() 的 Future: Send，而这条 future 穿过 ironrdp-client 里几个
        // 借 &UnboundedSender 的 async fn，rustc 的高阶生命周期推导直接报"Send is not
        // general enough"（试过，整片红）；二是我本来就跑在 CurrentThread 运行时上，
        // 单任务轮询和 spawn 在这个模型下没有区别，还少一层调度
        let run = client.run();
        tokio::pin!(run);

        let mut done = false;
        while !done {
            if STOP.load(Ordering::SeqCst) {
                break;
            }
            // 200 ms 的兜底超时只为让"用户已经点了断开"能被及时发现；
            // 没有它，rx.recv() 会在远端长时间不动画面时一直挂住这个循环
            tokio::select! {
                _ = &mut run => {
                    // run 结束的那一刻，队列里通常还排着一条 Terminated：select 先选中了
                    // "future 完成"这一支，直接退出的话丢的正好是"为什么断了"的原因，
                    // 界面上就只剩一句没有下文的「已结束」。这里把它读出来再收尾
                    while let Ok(event) = rx.try_recv() {
                        match event {
                            RdpOutputEvent::Terminated(result) => {
                                let mut s = lock();
                                s.state = STATE_CLOSED;
                                if s.error.is_empty() {
                                    s.error = match result {
                                        Ok(reason) => format!("会话结束：{:?}", reason),
                                        Err(err) => format!("连接中断：{}", err.report()),
                                    };
                                }
                            }
                            RdpOutputEvent::ConnectionFailure(err) => {
                                set_error(
                                    format!("RDP 连接失败：{}", err.report()),
                                    STATE_FAILED,
                                );
                            }
                            _ => {}
                        }
                    }
                    done = true;
                }
                event = rx.recv() => match event {
                    Some(RdpOutputEvent::Image { buffer, width, height }) => {
                        let mut s = lock();
                        s.width = width.get() as usize;
                        s.height = height.get() as usize;
                        s.frames = s.frames.wrapping_add(1);
                        s.frame_bytes = s
                            .frame_bytes
                            .wrapping_add((s.width * s.height * 4) as u64);
                        // 就地补不透明 alpha：IronRDP 给的是 0x00RRGGBB，Java 的 ARGB_8888
                        // 要 0xFFRRGGBB。放在这里做一遍，取帧那条路就只剩一次 memcpy——
                        // 原来每取一帧都要新建 8 MB 的 Vec 再逐像素 push，1080p 下光这一项
                        // 每秒就是几百 MB 的分配与搬运
                        s.frame = buffer;
                        for pixel in &mut s.frame {
                            *pixel |= 0xFF00_0000;
                        }
                        s.serial += 1;
                        if s.state != STATE_RUNNING {
                            s.state = STATE_RUNNING;
                            s.error.clear();
                        }
                    }
                    Some(RdpOutputEvent::ConnectionFailure(err)) => {
                        // report() 把整条 context 链摊平成一行的原因链；直接 Display 只有
                        // 最外层的 "[context]"，真机上看到"连接失败"却不知道败在哪一步最没用
                        set_error(format!("RDP 连接失败：{}", err.report()), STATE_FAILED);
                        done = true;
                    }
                    Some(RdpOutputEvent::Terminated(result)) => {
                        let mut s = lock();
                        s.state = STATE_CLOSED;
                        if s.error.is_empty() {
                            s.error = match result {
                                Ok(reason) => format!("会话结束：{:?}", reason),
                                Err(err) => format!("连接中断：{}", err.report()),
                            };
                        }
                        done = true;
                    }
                    Some(RdpOutputEvent::PointerBitmap(pointer)) => {
                        let mut shape = POINTER.lock().unwrap_or_else(|p| p.into_inner());
                        shape.serial = shape.serial.wrapping_add(1);
                        shape.kind = 0;
                        shape.width = pointer.width;
                        shape.height = pointer.height;
                        shape.hotspot_x = pointer.hotspot_x;
                        shape.hotspot_y = pointer.hotspot_y;
                        // 解码出来是 RGBA（Accelerated 目标即非预乘），转成 Android 认的
                        // 0xAARRGGBB。alpha 为 0 的像素是"透明"，这里原样保留
                        let data = &pointer.bitmap_data;
                        shape.pixels.clear();
                        shape.pixels.reserve(data.len() / 4);
                        for pixel in data.chunks_exact(4) {
                            let alpha = u32::from(pixel[3]);
                            let red = u32::from(pixel[0]);
                            let green = u32::from(pixel[1]);
                            let blue = u32::from(pixel[2]);
                            shape
                                .pixels
                                .push((alpha << 24) | (red << 16) | (green << 8) | blue);
                        }
                    }
                    Some(RdpOutputEvent::PointerDefault) => {
                        let mut shape = POINTER.lock().unwrap_or_else(|p| p.into_inner());
                        if shape.kind != 2 {
                            shape.kind = 2;
                            shape.serial = shape.serial.wrapping_add(1);
                        }
                    }
                    Some(RdpOutputEvent::PointerHidden) => {
                        let mut shape = POINTER.lock().unwrap_or_else(|p| p.into_inner());
                        if shape.kind != 1 {
                            shape.kind = 1;
                            shape.serial = shape.serial.wrapping_add(1);
                        }
                    }
                    // 位置由本地跟踪：鼠标模式下光标是我们自己推的，触控模式下就是手指的位置
                    Some(RdpOutputEvent::PointerPosition { .. }) => {}
                    None => done = true,
                },
                _ = tokio::time::sleep(Duration::from_millis(200)) => {}
            }
        }
        let _ = input.send(RdpInputEvent::Close);
        *INPUT.lock().unwrap_or_else(|p| p.into_inner()) = None;
        // 线程退出必须把状态结掉：状态是全局的，下一次进会话页如果还停在 RUNNING，
        // 界面会指着上一帧画面报「已连通」，看着就像连上了一样
        let mut s = lock();
        if s.state == STATE_CONNECTING || s.state == STATE_RUNNING {
            s.state = STATE_CLOSED;
        }
    });

    THREADS_LIVE.fetch_sub(1, Ordering::SeqCst);
}

fn input_sender() -> Option<mpsc::UnboundedSender<RdpInputEvent>> {
    INPUT
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .clone()
}

/// 把一次输入动作交给 `Database` 换算成协议事件，再整批送进会话。
///
/// 为什么不自己拼 PDU：MS-RDPBCGR 把「位置变化」和「按键状态变化」当成两类事件（按钮事件
/// 不能带 PTR_FLAGS_MOVE，上一轮就是合并着发导致指针能悬停但点不动），扩展键要 E0 前缀，
/// 重复按下要跳过，按下与抬起要一一对应——这些都由 `Database` 负责，界面侧只管表达意图。
fn emit(ops: impl IntoIterator<Item = Operation>) {
    let Some(sender) = input_sender() else {
        return;
    };
    let mut database = db();
    let mut events = database.apply(ops);
    // 拖动时移动事件也要带上"哪个键还按着"的位。Windows 把指针事件的按键位当作**当前状态**，
    // 光发 PTRFLAGS_MOVE 会被当成"没有按键"——表现是按下之后第一下移动就把左键松开了，
    // 窗口/图标/框选全都拖不动（FreeRDP 发移动事件时同样带着按键状态位）。
    let held = held_button_flags(&database);
    if !held.is_empty() {
        for event in events.iter_mut() {
            if let FastPathInputEvent::MouseEvent(pdu) = event {
                if pdu.flags.contains(PointerFlags::MOVE) {
                    pdu.flags |= held;
                }
            }
        }
    }
    drop(database);
    if !events.is_empty() {
        let _ = sender.send(RdpInputEvent::FastPath(events));
    }
}

/// 当前按住的鼠标键对应的协议位。
fn held_button_flags(database: &Database) -> PointerFlags {
    let mut flags = PointerFlags::empty();
    if database.is_mouse_button_pressed(MouseButton::Left) {
        flags |= PointerFlags::LEFT_BUTTON;
    }
    if database.is_mouse_button_pressed(MouseButton::Right) {
        flags |= PointerFlags::RIGHT_BUTTON;
    }
    if database.is_mouse_button_pressed(MouseButton::Middle) {
        flags |= PointerFlags::MIDDLE_BUTTON_OR_WHEEL;
    }
    flags
}

/// 指针动作。`button`：0=纯移动、1=左、2=右、3=中、8/9=浏览器前进后退键；
/// `down` 非 0 表示按下，0 表示抬起。
///
/// 位置与按键变化放进同一个事务：`Database` 会先补一条移动事件、再发不带 MOVE 位的
/// 按键事件，顺序与协议要求一致。
fn send_pointer(x: i32, y: i32, button: i32, down: bool) {
    let position = MousePosition {
        x: x.max(0) as u16,
        y: y.max(0) as u16,
    };
    let Some(button) = mouse_button(button) else {
        emit([Operation::MouseMove(position)]);
        return;
    };
    let ops = [
        Some(Operation::MouseMove(position)),
        Some(if down {
            Operation::MouseButtonPressed(button)
        } else {
            Operation::MouseButtonReleased(button)
        }),
    ];
    emit(ops.into_iter().flatten());
}

fn mouse_button(value: i32) -> Option<MouseButton> {
    match value {
        1 => Some(MouseButton::Left),
        2 => Some(MouseButton::Right),
        3 => Some(MouseButton::Middle),
        8 => Some(MouseButton::X1),
        9 => Some(MouseButton::X2),
        _ => None,
    }
}

/// 滚轮：`amount` 为格数，正数向上、负数向下。
///
/// 编码上有个坑：MS-RDPBCGR 把 WheelRotation 放在 flags 的低 9 位里（不是 MousePdu 的独立
/// 字段），IronRDP 的 encode 会自己按符号补 WHEEL_NEGATIVE 位并把数值塞进 flags，所以这里
/// 只需要如实传 ±格数（120 是 Windows 一格的固定值），不要自己去拼那一位。
fn send_wheel(x: i32, y: i32, amount: i32, vertical: bool) {
    let units = amount.clamp(-256, 255);
    if units == 0 {
        return;
    }
    let position = MousePosition {
        x: x.max(0) as u16,
        y: y.max(0) as u16,
    };
    // 滚轮作用在远端光标所在的窗口上，所以先把光标挪过去
    emit([
        Some(Operation::MouseMove(position)),
        Some(Operation::WheelRotations(WheelRotations {
            is_vertical: vertical,
            rotation_units: units as i16,
        })),
    ]
    .into_iter()
    .flatten());
}

/// 扫描码按键。`scancode` 用 u16 形式：高位的 0xE000 表示扩展键（方向键、右 Ctrl/Alt、
/// Insert/Home/PageUp 这一族都在扩展区），`Scancode::from_u16` 就是按这个约定拆的。
fn send_scancode(scancode: u16, down: bool) {
    let code = Scancode::from_u16(scancode);
    emit([if down {
        Operation::KeyPressed(code)
    } else {
        Operation::KeyReleased(code)
    }]);
}

/// Unicode 按键：软键盘输入的字符走这条通道，远端不必知道手机是什么键盘布局。
fn send_unicode(character: char, down: bool) {
    emit([if down {
        Operation::UnicodeKeyPressed(character)
    } else {
        Operation::UnicodeKeyReleased(character)
    }]);
}

/// 松开所有还按着的键与按钮。
///
/// 必须有人调它：手机上来电切出去、或者用户直接划走应用时，最后一次的 Ctrl/Shift 按下
/// 事件远端只收到"按下"没有"抬起"，回来就会发现"什么都没点但全在选中文本"。
fn release_all() {
    let Some(sender) = input_sender() else {
        return;
    };
    let events = db().release_all();
    if !events.is_empty() {
        let _ = sender.send(RdpInputEvent::FastPath(events));
    }
}

// ---------------------------------------------------------------- JNI 导出
/// 库能加载就说明 jniLibs 与 ABI 都对，Java 侧据此决定给不给「内置连接」这个入口。
#[no_mangle]
pub extern "C" fn Java_com_decibelmeter_app_RdpNative_nativeAvailable(
    _env: JNIEnv,
    _class: JObject,
) -> u8 {
    1
}

#[no_mangle]
pub extern "C" fn Java_com_decibelmeter_app_RdpNative_nativeVersion(
    env: JNIEnv,
    _class: JObject,
) -> JByteArray {
    let text = format!("rdpcore {} / IronRDP", env!("CARGO_PKG_VERSION"));
    unsafe { new_byte_string(env, &text) }
}

/// 起会话。返回 0 表示线程已起；负数是参数问题（不抛 Java 异常，界面按码给文案）。
///
/// 账号、域、密码、电脑名全在这一条边界上进来，之后只存在于原生内存里：
/// 不走 Intent（Intent 会被系统日志和最近任务记录带走）、不进连接簿文件、也不写日志。
///
/// `width`/`height` 是要向服务端请求的桌面尺寸，给 0 用默认值；协议对尺寸有硬约束
/// （宽 200–8192 且必须为偶数、高 200–8192），这里先按同一套规则收敛一遍，
/// 免得界面填了个奇数宽度要等到握手才失败。
#[no_mangle]
pub unsafe extern "C" fn Java_com_decibelmeter_app_RdpNative_nativeStart(
    env: JNIEnv,
    _class: JObject,
    host: JString,
    port: Jsize,
    user: JString,
    domain: JString,
    password: JString,
    device: JString,
    width: Jsize,
    height: Jsize,
    color_depth: Jsize,
    lossy: Jsize,
    context: JObject,
) -> Jsize {
    init_logging();
    let host_str = match read_java_string(env, host) {
        Some(v) => v,
        None => return -1,
    };
    if host_str.trim().is_empty() {
        return -2;
    }
    // 声音要 cpal 的 Android 后端，它依赖 ndk-context 里的 JavaVM + Application Context
    let sound = install_android_context(env, context);
    AUDIO_ENABLED.store(sound, Ordering::SeqCst);
    log::info!("RDP 会话参数：{}×{} · {} 位 · 有损={} · 声音={}", width, height, color_depth, lossy != 0, sound);
    let (width, height) = fit_display_size(width, height);
    // 只支持 16 与 32（IronRDP 的 BitmapConfig 也只认这两个），别的值按 32 处理
    let color_depth = if color_depth == 16 { 16 } else { 32 };
    let params = Params {
        host: host_str.trim().to_string(),
        port: port.max(1) as u16,
        user: read_java_string(env, user).unwrap_or_default(),
        domain: read_java_string(env, domain).unwrap_or_default(),
        password: read_java_string(env, password).unwrap_or_default(),
        device: read_java_string(env, device).unwrap_or_default(),
        width,
        height,
        color_depth,
        lossy: lossy != 0,
        sound,
    };
    STOP.store(true, Ordering::SeqCst);
    for _ in 0..50 {
        if THREADS_LIVE.load(Ordering::SeqCst) == 0 {
            break;
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    STOP.store(false, Ordering::SeqCst);
    std::thread::spawn(move || session_thread(params));
    0
}

/// 把界面给的尺寸收敛到 display-control 允许的范围（同 `MonitorLayoutEntry::adjust_display_size`：
/// 宽必须是偶数，两者都在 200–8192）。给 0 表示"用默认值"。
fn fit_display_size(width: i32, height: i32) -> (u16, u16) {
    let fit = |value: i32, fallback: u16| -> u16 {
        if value <= 0 {
            return fallback;
        }
        let mut v = value.clamp(200, 8192) as u16;
        if v % 2 != 0 {
            v -= 1;
        }
        v
    };
    (fit(width, DEFAULT_W), fit(height, DEFAULT_H))
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_decibelmeter_app_RdpNative_nativeState(
    _env: JNIEnv,
    _class: JObject,
) -> Jsize {
    lock().state as Jsize
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_decibelmeter_app_RdpNative_nativeError(
    env: JNIEnv,
    _class: JObject,
) -> JByteArray {
    let text = lock().error.clone();
    new_byte_string(env, &text)
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_decibelmeter_app_RdpNative_nativeWidth(
    _env: JNIEnv,
    _class: JObject,
) -> Jsize {
    let s = lock();
    if s.width > 0 {
        s.width as Jsize
    } else {
        FALLBACK_W as Jsize
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_decibelmeter_app_RdpNative_nativeHeight(
    _env: JNIEnv,
    _class: JObject,
) -> Jsize {
    let s = lock();
    if s.height > 0 {
        s.height as Jsize
    } else {
        FALLBACK_H as Jsize
    }
}

/// 取一帧（ARGB8888）。传回 Java 已持有的帧号：没有新帧直接返回 0，
/// 省掉每 33 ms 一次几 MB 的跨边界复制。
#[no_mangle]
pub unsafe extern "C" fn Java_com_decibelmeter_app_RdpNative_nativeCopyFrame(
    env: JNIEnv,
    _class: JObject,
    serial: Jsize,
    pixels: JIntArray,
) -> Jsize {
    let s = lock();
    let current = (s.serial & 0x7FFF_FFFF) as Jsize;
    if current == serial || pixels.is_null() || s.frame.is_empty() {
        return 0;
    }
    let count = s.width * s.height;
    if s.frame.len() < count {
        return -2;
    }
    let set_region = std::mem::transmute::<*const c_void, FnSetIntArrayRegion>(slot(
        env,
        SLOT_SET_INT_ARRAY_REGION,
    ));
    // frame 里已经是 ARGB 了，直接整块拷过去，不再逐像素转换
    set_region(env, pixels, 0, count as Jsize, s.frame.as_ptr() as *const Jsize);
    current
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_decibelmeter_app_RdpNative_nativePointer(
    _env: JNIEnv,
    _class: JObject,
    x: Jsize,
    y: Jsize,
    button: Jsize,
    down: u8,
) {
    {
        let mut s = lock();
        s.pointer_events += 1;
        s.last_pointer = format!("{},{} btn={} {}", x, y, button, down);
    }
    send_pointer(x, y, button, down != 0);
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_decibelmeter_app_RdpNative_nativeWheel(
    _env: JNIEnv,
    _class: JObject,
    x: Jsize,
    y: Jsize,
    amount: Jsize,
    vertical: u8,
) {
    {
        let mut s = lock();
        s.pointer_events += 1;
        s.last_pointer = format!("滚轮 {} @{},{} {}", amount, x, y,
            if vertical != 0 { "纵" } else { "横" });
    }
    send_wheel(x, y, amount, vertical != 0);
}

/// 指针形状有没有变。变了解析 `meta` 并返回新序号，没变返回 0。
/// meta = [width, height, hotspotX, hotspotY, kind]，kind 见 `PointerShape`。
#[no_mangle]
pub unsafe extern "C" fn Java_com_decibelmeter_app_RdpNative_nativePointerInfo(
    env: JNIEnv,
    _class: JObject,
    known_serial: Jsize,
    meta: JIntArray,
) -> Jsize {
    let shape = POINTER.lock().unwrap_or_else(|p| p.into_inner());
    let serial = (shape.serial & 0x7FFF_FFFF) as Jsize;
    if serial == known_serial || meta.is_null() {
        return 0;
    }
    let values: [Jsize; 5] = [
        shape.width as Jsize,
        shape.height as Jsize,
        shape.hotspot_x as Jsize,
        shape.hotspot_y as Jsize,
        shape.kind,
    ];
    let set_region = std::mem::transmute::<*const c_void, FnSetIntArrayRegion>(slot(
        env,
        SLOT_SET_INT_ARRAY_REGION,
    ));
    set_region(env, meta, 0, values.len() as Jsize, values.as_ptr());
    serial
}

/// 取指针位图（ARGB，非预乘）。返回写进去的像素个数，0 表示没有可用位图。
#[no_mangle]
pub unsafe extern "C" fn Java_com_decibelmeter_app_RdpNative_nativePointerPixels(
    env: JNIEnv,
    _class: JObject,
    out: JIntArray,
) -> Jsize {
    let shape = POINTER.lock().unwrap_or_else(|p| p.into_inner());
    if shape.kind != 0 || out.is_null() || shape.pixels.is_empty() {
        return 0;
    }
    let set_region = std::mem::transmute::<*const c_void, FnSetIntArrayRegion>(slot(
        env,
        SLOT_SET_INT_ARRAY_REGION,
    ));
    set_region(
        env,
        out,
        0,
        shape.pixels.len() as Jsize,
        shape.pixels.as_ptr() as *const Jsize,
    );
    shape.pixels.len() as Jsize
}

/// 扫描码按键（功能键、方向键、Win、Ctrl+Alt+Del 这类要真实键位的）。
#[no_mangle]
pub unsafe extern "C" fn Java_com_decibelmeter_app_RdpNative_nativeScancode(
    _env: JNIEnv,
    _class: JObject,
    scancode: Jsize,
    down: u8,
) {
    {
        let mut s = lock();
        if down != 0 {
            s.key_events += 1;
        }
    }
    send_scancode(scancode as u16, down != 0);
}

/// Unicode 按键（软键盘输入的字符）。
#[no_mangle]
pub unsafe extern "C" fn Java_com_decibelmeter_app_RdpNative_nativeUnicode(
    _env: JNIEnv,
    _class: JObject,
    codepoint: Jsize,
    down: u8,
) {
    {
        let mut s = lock();
        if down != 0 {
            s.key_events += 1;
        }
    }
    match char::from_u32(codepoint as u32) {
        Some(character) => send_unicode(character, down != 0),
        // 代理对拆出来的半个字符：宁可不发，也不要往远端塞一个问号
        None => {}
    }
}

/// 松开所有键与按钮。界面在 onPause / 弹输入法 / 切模式前调。
#[no_mangle]
pub extern "C" fn Java_com_decibelmeter_app_RdpNative_nativeReleaseAll(
    _env: JNIEnv,
    _class: JObject,
) {
    release_all();
}

/// 收到的帧数（累计）。配合 `nativeFrameBytes` 让界面算出真实帧率与流量。
#[no_mangle]
pub extern "C" fn Java_com_decibelmeter_app_RdpNative_nativeFrameCount(
    _env: JNIEnv,
    _class: JObject,
) -> Jlong {
    lock().frames as Jlong
}

/// 累计收到的字节数（按 w×h×4 估，含协议开销之外的全部像素载荷）。
#[no_mangle]
pub extern "C" fn Java_com_decibelmeter_app_RdpNative_nativeFrameBytes(
    _env: JNIEnv,
    _class: JObject,
) -> Jlong {
    lock().frame_bytes as Jlong
}

/// 输入统计：真机上用来确认触控与按键确实进了原生层并拿到了会话通道。
#[no_mangle]
pub unsafe extern "C" fn Java_com_decibelmeter_app_RdpNative_nativeInputStats(
    env: JNIEnv,
    _class: JObject,
) -> JByteArray {
    let s = lock();
    let connected = INPUT
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .is_some();
    // 空字符串时给"无"：先算好引用再进 format!，否则 if/else 两个分支一个是 &str 一个是 String
    let last = if s.last_pointer.is_empty() {
        "无"
    } else {
        s.last_pointer.as_str()
    };
    let text = format!(
        "指针事件 {} · 按键 {} · 输入通道 {} · 最近 {}",
        s.pointer_events,
        s.key_events,
        if connected { "已就绪" } else { "未建立" },
        last
    );
    new_byte_string(env, &text)
}

/// 预检 TCP 握手的往返耗时（毫秒）：连接详情里显示成「往返时间」。
#[no_mangle]
pub extern "C" fn Java_com_decibelmeter_app_RdpNative_nativeConnectMs(
    _env: JNIEnv,
    _class: JObject,
) -> Jlong {
    lock().connect_ms as Jlong
}

/// 收到多少个音频包（0 = 服务端没在发；在涨 = 本机该出声了，听不见就是路由/音量）。
#[no_mangle]
pub extern "C" fn Java_com_decibelmeter_app_RdpNative_nativeAudioPackets(
    _env: JNIEnv,
    _class: JObject,
) -> Jlong {
    ironrdp_rdpsnd_native::WAVE_PACKETS.load(Ordering::Relaxed) as Jlong
}

/// 声音通道是否打开（连接详情里显示，排查"没声音"时先看这一句）。
#[no_mangle]
pub extern "C" fn Java_com_decibelmeter_app_RdpNative_nativeAudioEnabled(
    _env: JNIEnv,
    _class: JObject,
) -> u8 {
    u8::from(AUDIO_ENABLED.load(Ordering::SeqCst))
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_decibelmeter_app_RdpNative_nativeStop(
    _env: JNIEnv,
    _class: JObject,
) {
    STOP.store(true, Ordering::SeqCst);
    let sender = INPUT
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .clone();
    if let Some(sender) = sender {
        let _ = sender.send(RdpInputEvent::Close);
    }
}
