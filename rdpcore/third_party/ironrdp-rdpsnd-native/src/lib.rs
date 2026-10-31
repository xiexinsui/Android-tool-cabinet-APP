#![cfg_attr(doc, doc = include_str!("../README.md"))]
#![doc(html_logo_url = "https://cdnweb.devolutions.net/images/projects/devolutions/logos/devolutions-icon-shadow.svg")]
// `anyhow` and `tracing-subscriber` are dev-deps used only by the `cpal`
// example binary, but `unused_crate_dependencies` still flags them on the
// lib target. The `[lib] test = false` setting makes a `#[cfg(test)]`
// workaround dead code, so the suppression has to apply unconditionally.
#![allow(unused_crate_dependencies)]

pub mod cpal;

/// 收到多少个音频包。连接详情里显示它：数字在涨却听不见，就是音量/蓝牙路由的问题；
/// 数字一直是 0，说明服务端压根没发（比如远端没在放声音）。
pub static WAVE_PACKETS: core::sync::atomic::AtomicU64 = core::sync::atomic::AtomicU64::new(0);
pub mod error;

pub use error::{RdpsndNativeError, RdpsndNativeErrorKind, RdpsndNativeResult};
