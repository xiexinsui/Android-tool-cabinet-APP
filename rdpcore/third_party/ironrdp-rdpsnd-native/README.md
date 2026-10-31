# ironrdp-rdpsnd-native（本地补丁副本）

来源：[IronRDP](https://github.com/Devolutions/IronRDP) 的 `ironrdp-rdpsnd-native` 0.7.0，
源码取自 crates.io，许可证见 `LICENSE-MIT` / `LICENSE-APACHE`。

本地改动两处：

1. `Cargo.toml`：把上游的 `default = ["opus"]` 改成空。上游默认会拉
`opus2` → `libopus_sys`，后者用 CMake 从源码编 libopus；这套工具链在 Android 交叉编译里
要额外装 CMake，而且每条 ABI 都要多编一个 C 库。内置远程桌面只需要 PCM 播放，所以关掉它。

2. `src/cpal.rs` 的 `DecodeStream::new` 里补了 `stream.play()`：上游建完 cpal 流就把它丢在那儿，
   而 AAudio 建出来的流默认是停着的，不 `play()` 回调永远不跑（症状：音频包在收，却没有声音）。
3. `src/cpal.rs` 加了几条 `info!` 日志：`get_formats()` 里的「RDPSND 协商开始」说明通道真的在跑，
   `wave()` 里只记前 3 包的「RDPSND wave packet」用来确认服务端到底有没有把音频发过来。

`rdpcore/Cargo.toml` 里用 `[patch.crates-io]` 指向这份副本。升级 IronRDP 时：
重新从对应版本复制这三个文件、同步版本号与依赖，然后确认 `cpal.rs` 里没有新增的默认特性依赖。
