# AndroidStream

在离线局域网中，用电脑浏览器查看和控制 Root 安卓手机。手机内置网页、信令、采集和输入服务，运行不依赖 ADB、互联网或电脑客户端。

当前版本 **0.1.30-test**，目标设备为小米 15 / Android 16 / KernelSU。支持 Android 11 起，电脑使用最新版 Chrome 或 Edge。本版不再要求 codec 列表预先声明 Baseline，改由 MediaCodec 实际配置验证，未完成实机验收。

应用使用深蓝底、青绿色显示器与白色手机组成的矢量自适应图标，支持桌面形状裁切及 Android 13+ 主题单色图标；实际主题效果取决于启动器。

## 使用

1. 在手机 App 或通知中停止旧服务，再运行 `powershell -ExecutionPolicy Bypass -File scripts/install.ps1` 覆盖安装并启动 App。在 KernelSU 中允许 Root。
2. 手机和电脑连接允许互访的同一局域网，打开 App，启动服务并等待 Root 就绪。
3. 浏览器打开手机显示的完整地址 `http://手机IP:8080`。升级后强制刷新，确认页头版本。
4. 网页自动连接；需要声音时勾选“播放手机内部音频”，需要文本时展开“输入文本”。连接后可点击“关闭手机屏幕”或“点亮手机屏幕”。
5. 测试结束后点击手机 App 或通知的“停止”。升级或卸载前也要先停止服务。

网络和媒体运行在独立 Root 进程。退出或划掉 App 不会主动停止共享；重新打开 App 会查询并接管现有服务，不会自动启动未运行的服务。管理通知若被系统清理，可重新打开 App 后停止。厂商仍可能限制 Root 子进程，需实测确认；App 电池优化豁免仅辅助管理界面保活。

手机 IP 变化后重启服务，使用新地址。TCP 8080 承载网页、信令和音频；网络还需允许 WebRTC 协商出的局域网 UDP 直连。

### 功能与限制

| 项目 | 当前实现 |
| --- | --- |
| 视频 | Surface 采集 → MediaCodec 硬编 → WebRTC，固定 H.264 Baseline |
| 视频策略 | 使用手机当前逻辑显示尺寸和显示刷新率；原生组合不支持时明确报告尺寸、帧率、Level 或硬件码率约束；无应用层固定码率上限 |
| 输入 | 单指点击、拖动、滚轮、导航、音量和可映射文本 |
| 音频 | 内部播放采集，48 kHz 双声道 PCM16 → WebSocket → Web Audio |
| 恢复 | 断线重连、旋转后重建视频、失焦和断线释放输入 |
| 手机屏幕 | Root 请求关闭物理显示屏，保持系统逻辑唤醒；断开控制或停止服务时尝试恢复显示，ROM 兼容性待实测 |

仅支持局域网 IPv4、单手机、单控制端。不提供用户视频配置、云服务、STUN/TURN 或公网穿透。不支持 H.265/AV1、多点触控、任意 Unicode 输入法、Opus、严格音画同步或文件传输。

按项目要求无配对码、登录和手机确认，同一局域网设备可直接连接控制。Host/Origin 校验不属于认证。网页、信令和 PCM 音频使用未加密的 HTTP/WS，视频与控制使用 WebRTC 内置加密。网络解析也在 Root 进程运行，固定路由和消息限制不能替代权限隔离；不提供任意 shell 或文件访问接口。

### 原生显示参数

视频使用手机主屏幕当前逻辑尺寸和当前显示模式刷新率，不固定 1920/60，也不修改手机分辨率或强制最高刷新率。完整显示模式（尺寸、旋转、刷新率及模式 ID）稳定两秒后才提交，避免模式切换期间连续重建。静止画面不保证持续输出与刷新率相同的帧数。

Root 读取并保留系统显示参数。连接前检查硬件 Surface、原生尺寸、原生帧率、Baseline Level 和硬件码率范围；若任一项不支持原生组合，会报告具体失败约束，不自动缩放或降帧。应用不设固定码率上限，WebRTC sender 与编码目标使用硬件声明范围，并受 H.264 Level、WebRTC 拥塞控制和网络状况约束。实际硬件配置及手机端稳定性仍需实测。

选择能够满足原生尺寸、帧率及硬件最大码率范围的 H.264 Surface 硬编，使用该组合所需的 Baseline Level，并在 SDP 与 MediaCodec 配置中保持一致。应用不设固定码率上限；编码器和 WebRTC 带宽估计仍会按硬件能力及网络状态调节，H.264 Level 也会限制合法码率范围。

### 手机息屏

先在手机上点亮并解锁，再通过网页关闭物理屏幕。功能不发送电源按键、不调用系统休眠、不修改锁屏和亮度设置；目标是电脑画面和输入继续工作，不是让手机进入休眠。屏幕关闭期间额外持有最长两小时的显示唤醒锁，防止系统超时休眠；采集、编码和网络仍消耗资源，节电效果需实测。

按钮通过可靠 control 通道发送请求，网页根据 Root 返回结果更新提示。物理屏幕接口返回成功不等于已经测得面板状态。小米 Android 16 若不支持接口，会显示 Screen 错误，视频连接不因此主动关闭。正常断线、控制通道故障、停止服务会尝试恢复显示；控制心跳消失约五秒或息屏满两小时也会触发恢复。浏览器后台节流可能触发这一保护。

“点亮手机屏幕”用于恢复本功能关闭的面板，不负责解锁或唤醒已正常休眠的系统。强制杀死 Root、系统崩溃或显示接口失效时无法保证自动恢复，可在手机上操作电源键恢复息屏／唤醒状态。

### 连接统计

网页默认展开“连接统计”，约每秒读取一次浏览器 WebRTC getStats；折叠、隐藏页面或断线时停止采样。首个采样只建立基线，重连及旋转重建后重新计算；浏览器未提供数据时显示“—”。统计不增加 Root 采样或网络诊断消息。

| 指标 | 含义 |
| --- | --- |
| 接收码率 | 采样间隔内视频接收字节增量换算为 Mbps，不含 WS 音频 |
| 画面 | 接收视频分辨率，以及该间隔成功解码帧数换算的 FPS |
| 网络 RTT | 当前选中 ICE 连接的网络往返时间，毫秒；不是操作到画面的延迟 |
| 丢包率 | 该间隔丢失包数占接收与丢失包总数的比例；迟到造成的负丢失增量按 0 处理 |
| 解码均值 | 该间隔累计解码耗时除以成功解码帧数，毫秒 |
| 抖动缓冲 | 该间隔从抖动缓冲输出的视频帧平均驻留时间，毫秒；不是当前队列长度 |

指标只描述浏览器接收端和网络，不用于推断手机 CPU、编码耗时或全链路延迟，也不证明硬件解码。

### 常见问题

| 现象 | 处理 |
| --- | --- |
| 尚未启动服务 | 打开 App 只查找现有 Root 服务；没有服务时点击“启动服务”。管理连接拒绝或端点不存在按未运行处理；权限拒绝、超时等仍单独报错 |
| 切换 App 后断线 | 本版将网络与媒体移到独立 Root 进程；若仍复现，记录具体 App、网页错误和 Root 是否仍运行，不能仅凭 Root UID 判断不会被冻结 |
| 横竖屏切换停顿 | 当前会释放旧编码器并重建视频，不保证无缝旋转 |
| Root 服务不可用 | 检查 KernelSU 授权及启动日志，在手机停止再启动服务；版本不一致时也须先停止 |
| 画面无法建立 | 查看网页错误，检查浏览器 H.264 和手机 Surface 硬编支持；无软件静默回退 |
| 静止清晰、滑动模糊 | 查看滑动时接收码率、FPS、丢包与 RTT；码率受硬件能力、H.264 Level 和网络状况约束，实际清晰度仍需实测 |
| 关闭屏幕后电脑也黑屏或停帧 | 点击“点亮手机屏幕”恢复；记录 ROM 和错误，物理息屏下的采集兼容性需实机确认 |
| Audio 错误 | 音频关闭，视频可继续；ROM 或受保护内容可能限制内部音频采集 |

## 开发

需要 JDK 17+、SDK Platform 36 和 Android Build Tools。当前环境为 Android Studio JBR 21、Build Tools 36.1.0。使用预编译 WebRTC AAR，无需本地 NDK、CMake 或 Rust。首次下载依赖需联网。

```powershell
# 构建 APK，并运行 JUnit 和 Lint
powershell -ExecutionPolicy Bypass -File scripts/build.ps1

# 可选：通过 USB 安装并打开
powershell -ExecutionPolicy Bypass -File scripts/install.ps1

# 浏览器回归
python -m pip install -r tests/requirements.txt
python tests/browser_smoke.py
```

构建脚本自动查找 Android Studio JBR 和默认 SDK，也可设置 `JAVA_HOME`、`ANDROID_HOME`。APK 输出到 `artifacts/`。其他平台使用 `./gradlew :android:assembleDebug :android:testDebugUnitTest :android:lintDebug`。

浏览器回归默认使用本机 Edge，可用 `ANDROIDSTREAM_BROWSER` 指定 Chromium 可执行文件。它使用真实浏览器 WebRTC 回环和模拟手机端，不替代实机测试。

Root 启动日志位于 App 私有目录 `files/root-daemon.log`，每次启动覆盖，仅用于故障排查。开发时可用 `adb shell run-as dev.androidstream cat files/root-daemon.log` 读取；运行服务不依赖 ADB。

## 实现

### 链路与代码

```text
视频：Root 显示 → Surface → MediaCodec → 同进程 WebRTC → 浏览器 video
音频：Root AudioRecord → 同进程 WebSocket → 浏览器 Web Audio
控制：浏览器键鼠 → DataChannel → Root 输入注入
网页：浏览器 ← HTTP / WS → Root 服务
管理：普通 App ← 本机 IPC（状态 / 停止）→ Root 服务
```

App 经 su 启动独立 app_process，从已安装 APK 加载 RootDaemon，进程名为 `androidstream-root`。启动时忽略 SIGHUP，标准输入接 /dev/null，输出重定向到私有日志文件；不保留 App 管理的媒体或日志管道。Root 进程加载 APK 内网页，以及安装时解压的 WebRTC 原生库（Gradle useLegacyPackaging=true，运行时按安装路径 System.load）。WebRTC 关闭 Android App NetworkMonitor，依靠原生接口枚举局域网地址；此无界面运行方式仍需目标设备验证。

Root 创建按应用 UID 区分的抽象 LocalSocket，名称为 `androidstream-manager-<应用UID>`。Root 校验管理对端为应用 UID，App 校验对端为 UID 0。该 IPC 只管理状态和停止，媒体、输入和会话不依赖它；管理连接关闭或超时仅断开管理客户端。显示采集优先使用 DisplayManager 镜像接口，失败再尝试 SurfaceControl；私有 API 集中在 Root 适配层，不关闭 SELinux。

Java 源码位于 `android/src/main/java/dev/androidstream/`：

| 文件或目录 | 职责 |
| --- | --- |
| MainActivity、StreamService、RootBridge | 普通 App 管理界面、管理通知、Root 启动与管理连接 |
| RootDaemon、DaemonProtocol | Root 入口、独立生命周期、UID 校验和管理命令白名单 |
| RootSessionServer、LocalWebServer | Root 内会话、媒体重建、固定网页资源、WebSocket 和请求校验 |
| CaptureBackend、Wire、root/ | 同进程媒体交接、管理消息分帧、显示、物理屏幕电源、音频、输入与硬编适配 |
| RtcSession、RootVideoEncoder | SDP/ICE、DataChannel、编码帧接入 WebRTC |
| VideoPolicy | 内置视频策略 |
| VideoPacer、EncodedFrameQueue、EncodedBufferPool | 有界帧队列、预测链恢复和缓冲复用 |
| web/、tests/、scripts/（仓库根目录） | 原生 JS/CSS 网页、测试、构建与安装脚本 |

### 媒体策略

视频只编码一次，不通过截图/JPEG 或 CPU 读回 RGBA。编码输出直接交给同进程 WebRTC，仍经过数组和 direct buffer 复制；占位 I420 帧为 WebRTC 提供时钟，自定义 VideoProcessor 避免再次缩放或丢弃已编码帧，**并非全链路零拷贝**。移除媒体 IPC 不等于已经证明资源占用或延迟改善。

| 环节 | 当前策略 |
| --- | --- |
| MediaCodec | 请求 CBR（支持时）、GOP 5 秒、无 B 帧、实时优先级、KEY_LATENCY=1、静态重复间隔 100 ms；dequeue 最多等待 50 ms |
| 码率 | sender、连接带宽和编码目标上限取硬编 bitrateRange 上界，覆盖 M144 未设置 max 时约 2.5 Mbps 的默认值；起始估计 8 Mbps，按硬件范围收敛；sender 最小参数 1 Mbps（受硬件范围约束），编码器分配下限 100 kbps；不设应用层固定上限 |
| 编码线程 | 创建、动态参数更新和释放均在同一线程；命令线程保存最新码率/关键帧请求；媒体交接不经过 App 或管理 IPC |
| VideoPacer | 最多 6 帧，入队发现满队列或积压超过 100 ms 时清空并等待关键帧；最小提交间隔取当前目标刷新率的倒数 |
| EncodedFrameQueue | 最多 8 项，匹配误差容忍 5 ms，跳过参考帧后恢复预测链；关键帧请求间隔至少 250 ms |
| 缓冲池 | WebRTC 最后一次引用释放后回池，最多缓存 4 × 2 MiB；按精确容量 slice 交给 JNI |
| WebRTC | 保留拥塞控制、重传、pacing 和 ALR 探测；关闭原始帧 FrameDropper，使用 ProbingScreenshareBwe/1.5,40,80,40,-60,3/ |
| 浏览器 | 支持时请求 jitterBufferTarget=0 |
| PCM 音频 | 每块最多 3840 字节（20 ms），原始负载约 1.536 Mbps；发送积压时丢块，浏览器计划缓冲超过约 150 ms 时清空 |

上述值是请求或内部策略，不保证硬件采纳、实际帧率、浏览器硬解或端到端延迟。8 Mbps 仅为连接起始估计，不是固定码率或保底；后续仍按 WebRTC 拥塞控制和硬件范围调整。H.264 Level、编码器声明范围、WebRTC 整数接口及网络状况仍会限制实际码率。关键帧、编码器偏差、重传、RTP 开销和统计时间窗口可能造成短时测量变化，WS 音频也另占带宽。高起始估计在较弱 Wi-Fi 上可能短暂增加丢包或缓冲，需实机检查。

### 协议与生命周期

网页协议仅供同版本 APK 使用。管理响应包含 protocol=1 和 APK 版本；App 拒绝接管不匹配版本，固定 stop 命令仍可停止旧 Root 服务。

| 接口 | 格式与边界 |
| --- | --- |
| HTTP | 固定路由 /、/app.js、/style.css、/ws；校验本机 IPv4:端口和同源 Origin，设置 CSP；头总计 8 KiB、单行 2 KiB |
| WebSocket | UTF-8 JSON，单帧最多 16 KiB；拒绝非法 UTF-8、未掩码、分片和入站二进制 |
| 管理 IPC（双向） | 大端 uint32 长度 + UTF-8，最多 4096 字节；请求仅接受精确字符串 status 或 stop，拒绝 JSON 命令和其他操作 |
| 管理响应 | JSON 状态含版本、PID、地址、设备和 ready；停止回复 stopping 后清理退出。最多两条管理连接，读超时 15 秒 |
| 同进程媒体 | 回调交接，不经过管理 IPC；PCM 每块最多 3840 字节且 4 字节对齐，编码视频包最多 8 MiB |
| 内部视频包 | 大端 uint32 关键帧标记 + uint64 PTS 微秒 + Annex B；关键帧携带参数集 |
| control | 有序可靠；按下、抬起、取消、按键、文本、heartbeat，以及 screen/off 布尔值；屏幕结果由 WS screen 消息返回 |
| motion | 无序，maxRetransmits=0；只发送可替换的移动事件 |

媒体协商代次 mediaGeneration、几何版本 geometry.generation 和 PTS 相互独立。输入携带 gesture、递增 seq、action、几何版本和有限归一化坐标 [0,1]；按实际视频区域映射，可靠抬起携带最终坐标，早于按下的移动可丢弃。

每会话最多两个命名通道、每秒合计 500 条输入，文本最多 512 个 UTF-16 单元，按键使用白名单。DataChannel 严格校验 UTF-8 和序列化后的命令长度。Root 命令队列上限 128，服务队列上限 64；过载结束 Root 会话或停止服务，避免静默丢失释放事件。

- 旋转先取消输入，保留信令和音频；等待旧 RTC 清理及 stopVideo 入队后，用最新几何重建。RTC 初始化失败也纳入清理等待。
- App 最多等待 Root 授权 20 秒，随后等待初始化就绪最多 20 秒；管理状态约每 2 秒查询一次，不采集媒体性能。管理连接断开后可重连；服务消失时不自动重启 Root。WebRTC 中断先等待 2.5 秒；信令 12 秒无响应或恢复超时后，以 0.5–8 秒退避重连。手动断开、离开页面或致命错误停止重试。
- 停止、失焦、断线及注入异常时释放输入；Root watchdog 检查残留触摸和按键。解析错误不回传可能包含输入内容的异常文本。
- 物理屏幕电源接口集中在 DisplayAccess；Android 14+ 必要时从系统服务类加载 DisplayControl，调用系统原生库，隐藏 API 豁免仅在 Root app_process 中生效。ScreenPower 在修改硬件前登记恢复责任；失败尝试回滚，恢复失败保留责任供心跳监测重试。正常停止也尝试恢复，但不承诺异常强杀后的恢复。
- 音频停止先 stop recorder 再 join，超时结束 Root 服务，避免新旧采集重叠；网页隐藏时停止音频，可见后按勾选恢复。
- Root 持有最长 2 小时的部分 WakeLock，App 不持有媒体 WakeLock。显式停止会取消输入、停止媒体、关闭网络和管理 IPC；6 秒仍未清理完则由 Root 自行退出。App 等待管理监听消失后结束管理通知。启动过程中按停止还会等待可能尚未绑定管理端口的 Root，最多再等 20 秒；启动失败清理已创建资源。
- 保留连接状态、错误、故障日志和保活；统计仅在浏览器按需采样，不运行周期 Root 诊断。

## 验证

### 当前结果

2026-10-17，版本 0.1.30-test，versionCode 31：

| 检查 | 结果 |
| --- | --- |
| JUnit | 53 项通过，0 failures/errors；覆盖 H.264 Profile/Level 标志解析及显示模式去抖 |
| Android Lint | 0 errors / 14 warnings |
| Edge 155.0.4283.45 | HTTP WebRTC 视频、触控、导航、文本、PCM 音频、统计生命周期、静音和断开回环通过；手机信令/采集为模拟 |
| APK | v2 签名和 zipalign 16 KiB 检查通过；网页资源已打包 |
| 手机 | ADB 无设备；小米 15 / Android 16 / KernelSU 实机尚未验收 |

APK 大小 14,133,565 字节，SHA256：

```text
C5542B51904DD693A37717D20E2AEFA9B128B2F89C98FC502F77C80EF75544B3
```

### 待验证与验收

仍需实测 CPU、温度、实际 FPS/码率、弱网、输入到画面延迟和长期稳定性。重点风险包括 Root 原生库加载和无界面 Context 兼容性、原生网络枚举、SELinux 下管理 IPC、旋转停顿、JNI/交接线程释放竞态、采集错误未携带媒体代次、PCM 丢块与音画漂移，以及 WakeLock 超时和厂商后台限制。Root 不保证绕过受保护内容或 ROM 限制。

1. 检查原生尺寸与系统当前显示模式，分别测试 60 Hz、高刷新率、非整数刷新率（设备支持时）、横竖屏和系统分辨率切换；确认编码组件、请求／实际 FPS、H.264 Level 和错误提示；检查刷新率短时切换不重建、稳定约两秒后重建。验证点击、拖动、滚轮、导航、文本、失焦及按住时断网；检查输入无残留、第二控制端被拒绝。
2. 拖动或播放音频时连续旋转 20 次，快速切音频 20 次，隐藏/恢复网页并断线重连；检查坐标、重复声、线程和手机本地声音。
3. 静止 10 秒后回主页、打开复杂 App、滚动 10 秒，记录接收码率、FPS、丢包、RTT 和缓冲，检查无应用层固定码率上限时的清晰度与关键帧卡顿，弱网时仍应回落。必要时使用 Perfetto 和浏览器外部 WebRTC 工具定位。
4. 不使用小窗，切换多个 App；再退出或划掉管理 App，确认浏览器的视频、音频和控制是否持续。重新打开 App，确认地址和会话被接管且未重启采集；再测试启动过程中停止、反复启动/停止，以及停止后通知、监听和 Root 进程消失。
5. 终止 Root、撤销授权、断 Wi-Fi 或限速，检查错误、资源释放和恢复。
6. 动态运行 30 分钟，记录 App/Root CPU、内存和温度；与相同场景、网络及媒体参数下的旧版对照，不把进程迁移等同于性能改善。
7. 息屏后连续滑动、播放视频／内部音频、旋转和切换 App；测试手动亮屏、网页断开／刷新、控制通道中断、五秒心跳丢失及停止服务后的恢复。测试系统自动锁屏和本地电源键是否干扰；异常强杀时确认手机上的恢复操作可用。

测量须记录设备/ROM/KernelSU、浏览器、网络、APK 哈希、编码器、尺寸、FPS、码率、音频状态及方法。以高速摄像交叉验证输入到显示的 P50/P95，注明样本和误差；跨设备时间戳需校准，RTT 不能替代端到端延迟。

良好局域网的设计目标是 P50 ≤ 80 ms、P95 ≤ 120 ms、动态帧率接近所选显示模式目标（以设备能力为限）、30 分钟无持续积压，**不是已测结果**。下一步先完成实机基线，再按瓶颈优化媒体复制或音频压缩/同步。

## 依赖与参考

工程使用 Java 17 和原生 JS/CSS，minSdk 30，targetSdk/compileSdk 36，APK 包含 arm64-v8a 和 x86_64。

| 组件 | 固定版本 | 来源与许可证 |
| --- | --- | --- |
| WebRTC Android AAR | io.github.webrtc-sdk:android:144.7559.15 | Maven Central，[打包项目](https://github.com/webrtc-sdk/android)；WebRTC BSD 风格及内部组件各自许可证 |
| JUnit（测试） | 4.13.2 | Maven Central，[JUnit](https://github.com/junit-team/junit4)；EPL-1.0 |
| Playwright Python（测试） | 1.64.0 | PyPI，[Playwright](https://github.com/microsoft/playwright-python)；Apache-2.0 |
| Android Gradle Plugin | 8.13.2 | Google Maven；Apache-2.0 |
| Gradle Wrapper | 8.13 | services.gradle.org；Apache-2.0，分发 SHA256 固定在 wrapper properties |

本项目未修改或重编 WebRTC 原生源码，使用上游预编译 AAR；编码帧适配和 field trials 在 Java 层实现。当前尚未内嵌该 AAR 的完整第三方许可证清单，正式发布前须补齐 notices 并核实来源。

技术参考：[MediaFormat KEY_LATENCY](https://developer.android.com/reference/android/media/MediaFormat#KEY_LATENCY)、[WebRTC M144 默认码率](https://webrtc.googlesource.com/src/+/refs/branch-heads/7559/video/config/encoder_stream_factory.cc)、[M144 ALR](https://webrtc.googlesource.com/src/+/refs/branch-heads/7559/rtc_base/experiments/alr_experiment.cc)、[jitterBufferTarget](https://developer.mozilla.org/en-US/docs/Web/API/RTCRtpReceiver/jitterBufferTarget)、[scrcpy 视频](https://github.com/Genymobile/scrcpy/blob/master/doc/video.md)、[音频](https://github.com/Genymobile/scrcpy/blob/master/doc/audio.md)、[键盘](https://github.com/Genymobile/scrcpy/blob/master/doc/keyboard.md)。scrcpy 仅作设计参考，未导入其源码。

数据链审查与待验证优化方案见 [PERFORMANCE.md](PERFORMANCE.md)；协作规则见 [AGENTS.md](AGENTS.md)。
