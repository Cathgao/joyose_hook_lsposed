# Joyose NoLimit (Joyose 彻底免限帧 LSPosed 模块)

专为小米 / 红米设备（HyperOS / MIUI）打造的高性能、零副作用 LSPosed 模块。
**彻底屏蔽 Joyose 的温控限帧写入功能，同时 100% 完整保留 Game Turbo 游戏工具箱、插帧超分、触控优化与游戏网络加速！**

---

## 🌟 核心痛点与本模块的解决方式

在 HyperOS / MIUI 设备上，玩游戏时的降帧卡顿主要由 **Joyose** 引起：
- Joyose 内部拥有独立的温控、电池与 PID 动态监测线程。一旦温度达到阶梯，Joyose 就会向 `/data/system/mcd/df` 写入包名与限制帧率（如 `com.miHoYo.Yuanshen 30`），并通过调度器底层强制锁定帧率。
- **为什么不直接冻结 Joyose？**
  如果冻结或删除 Joyose，将直接导致 **Game Turbo 侧边栏消失、游戏插帧/超分辨率失效、高触控采样率失效、双 WiFi 游戏网络加速失效**。
- **为什么修改数据库依然效果不彻底？**
  部分云控与动态 PID 会绕过固定阶梯，直接动态调用限帧函数。

### 本模块的解决方案：精准底层 Hook
通过反编译与逆向分析 Joyose 核心 Dex，我们发现 Joyose 内**所有**动态温控与限帧下发最终统一汇聚在同一个底层工具类：
`com.xiaomi.joyose.utils.w` (内部原名 `MiDFUtils`)

本模块通过 LSPosed 框架在内存中进行微创手术：
1. **Hook `w.f(Context, String pkg, float fps, int reason)`**：当检测到向目标游戏下发限帧时，直接阻断（`setResult(null)`），既不写入 `/data/system/mcd/df`，也不向下游调度器广播。
2. **Hook `w.g(Context, String pkg, int fps, int reason)`**：同上，全面阻断整型限帧入口。
3. **Hook `w.d()`**：恒定返回 `-1`（无限制状态），使其他读取当前限帧状态的组件永远读取到“满血无限制”。
4. **启动清理**：Joyose 启动时自动将残留的 `/data/system/mcd/df` 清空为 0 字节。
5. **放行解除信号**：当 Joyose 主动解除限帧（`pkg == null`）时正常放行。

---

## ✨ 模块特性

- **零功能破坏**：完全不改动系统温控文件或数据库，Game Turbo 侧边栏、GPU 参数调节、画质增强、触控优化全部完好如初。
- **零额外耗电**：纯粹的方法级拦截，没有常驻守护进程，没有定时轮询脚本，不增加任何待机功耗。
- **自动作用域**：内置 `xposedscope` 清单，在 LSPosed 管理器中开启时会自动选中 `com.xiaomi.joyose`。
- **跨版本兼容**：采用双层查找机制（标准类名 + 特征签名回退），兼容不同 HyperOS / MIUI 版本的 Joyose。
- **内置状态看板**：提供轻量级应用界面，支持一键检测 LSPosed 激活状态、读取 `/data/system/mcd/df` 现状以及一键 Root 重启 Joyose。

---

## 🚀 安装与使用

### 前置要求
- 已解锁 Bootloader，并获得 Root 权限（KernelSU / Magisk / APatch）。
- 已安装 **Zygisk**（或 Zygisk Next）与 **LSPosed**（推荐 LSPosed v2.2.1 或以上）。

### 使用步骤
1. **安装 APK**：
   下载或编译生成的 `JoyoseNoLimit.apk` 并安装至手机。
2. **在 LSPosed 中激活**：
   - 打开 LSPosed 管理器。
   - 进入模块列表，找到 **Joyose NoLimit**。
   - 打开开关启用模块，确认作用域已勾选 **Joyose** (`com.xiaomi.joyose`)。
3. **重启 Joyose**：
   - 打开「Joyose NoLimit」App，点击 **「重启 Joyose 进程使 Hook 生效」**；
   - 或在电脑端终端执行：
     ```bash
     adb shell su -c "pkill -9 -f com.xiaomi.joyose"
     ```
4. **验证 Hook 是否生效**：
   在电脑终端执行以下命令监控拦截日志：
   ```bash
   adb logcat -s LSPosed-JoyoseNoLimit
   ```
   当手机温度升高触发 Joyose 限帧时，会看到实时的拦截日志：
   ```text
   [JoyoseNoLimit] Intercepted & Blocked w.f -> pkg=com.miHoYo.Yuanshen, limitFps=45.0, reason=2
   ```
   游戏将保持满血流畅运行，不会被 Joyose 强行锁定帧率！

---

## 🛠️ 项目构建 (Building)

本项目支持全平台快速编译与自动化 CI/CD。

### 方式 1：Windows 本地编译
- 双击运行根目录下的 `build.bat`，或在 PowerShell 中执行：
  ```powershell
  .\build.ps1
  ```
- 脚本会自动使用 Gradle Wrapper 或 WSL 工具链完成构建，产物生成在根目录 `JoyoseNoLimit.apk`。

### 方式 2：Linux / macOS / WSL 快速编译
- 极速编译（无需下载大型 Gradle 依赖，秒级出包）：
  ```bash
  chmod +x build.sh && ./build.sh
  ```
- 标准 Gradle 构建：
  ```bash
  chmod +x gradlew && ./gradlew assembleRelease
  ```

### 方式 3：GitHub Actions 自动构建
- 仓库已内置 `.github/workflows/build.yml`。
- 每次推送代码（Push）或发布标签（Tag 如 `v1.0.0`），GitHub Actions 均会自动编译并发布免限帧 APK。

---

## 📄 开源许可

本项目遵循 MIT 开源协议。
