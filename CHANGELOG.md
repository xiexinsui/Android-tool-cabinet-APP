# 更新日志

每次推送在顶部追加一条，分类用「新增 / 修改 / 修复 / 移除」。提交信息主题与本文件对应条目保持一致。

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
