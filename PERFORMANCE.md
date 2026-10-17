# 数据链审查与性能优化

基于 0.1.19-test 的代码审查，日期 2026-10-17。本文记录优化分析与待验证方案。原生显示参数、硬编尺寸／帧率适配已实现；设备策略与验证状态见 [README.md](README.md)。其余候选优化仍待验证，尚无手机性能测量结果。当前使用、实现参数、构建命令和验证状态以 README 为准。

## 优先结论

先检查视频排队与时间戳、输入被媒体启停阻塞的问题，再做编码策略对照，随后处理音频积压。保持当前 Root 常驻服务、WebRTC 视频、DataChannel 控制和内置网页架构。

Surface 采集和硬编码已经避免 CPU 读回画面；剩余复制、排队和线程调度仍有优化空间。Root 身份、新协议或减少一次复制本身不能证明性能改善。

## 视频链路

```text
Root 显示 → Surface → MediaCodec
→ 编码 byte[] → VideoPacer → EncodedFrameQueue
→ 占位 I420 帧触发 WebRTC 编码回调
→ direct ByteBuffer 池 → JNI / WebRTC RTP → 浏览器 video
```

相关代码：[RootCapture.java](android/src/main/java/dev/androidstream/root/RootCapture.java)、[RootVideoEncoder.java](android/src/main/java/dev/androidstream/RootVideoEncoder.java)、[VideoPacer.java](android/src/main/java/dev/androidstream/VideoPacer.java)、[EncodedFrameQueue.java](android/src/main/java/dev/androidstream/EncodedFrameQueue.java)、[EncodedBufferPool.java](android/src/main/java/dev/androidstream/EncodedBufferPool.java)。

### 已确认的行为

- 视频只做一次像素编码。占位 I420 为 WebRTC 提供提交时钟，不代表重新编码屏幕像素。
- MediaCodec 输出复制到新的 byte[]，之后再复制到池化 direct buffer。不是全链路零拷贝。
- VideoPacer 有界，但积压时间只在入队时检查；出队不复查，因此当前时间阈值不是严格的帧驻留上限。
- 帧提交受到最小间隔约束。正常匀速输入不一定产生额外一帧延迟，突发到达时可能等待；这层与 WebRTC RTP 包 pacing 的职责不同。
- 提交时以当前对齐时钟重写时间戳，未保留编码输出原始 PTS 的时间分布，可能掩盖之前的排队时间。
- 编码循环还承担周期几何查询。dequeue 的等待参数是最大阻塞时间，不是每帧固定增加的延迟。

### 优化方向

1. 测量编码输出到 WebRTC 提交的驻留时间，检查突发、静止后滑动和关键帧场景。
2. 保留原始 PTS，并明确映射到 WebRTC 所需的时钟域；验证单调性、旋转重建和媒体代次切换。
3. 根据测量调整提交节奏和过期帧处理。丢弃参考帧后须等待关键帧恢复预测链，不能只丢任意旧 P 帧继续解码。
4. 考虑将几何查询移出编码线程，维持旋转检测时效与资源释放顺序。
5. 只有分配或 GC 被证实为瓶颈时，再考虑编码缓冲复用、direct buffer 直写或 JNI 改造。

码率增加会按比例增加压缩数据复制量；不能据此直接认定复制就是手机高占用的主因。

## 按手机原生显示参数决定视频规格（已实现，待实机验证）

原生显示参数方案已落实，具体读取、对齐、能力校验、不支持时的处理和显示变化策略统一维护在 [README 原生显示参数](README.md#原生显示参数)与[媒体策略](README.md#媒体策略)。不新增用户配置，保留单一内置自适应策略。

后续对照原有固定策略，验证原生尺寸、横竖屏、60／高刷新率模式、显示参数变化、协商兼容性和资源释放。分别记录请求与实际输出的尺寸／FPS，以及清晰度、输入到画面延迟、CPU 和温度；不能仅以硬件声明支持作为验收结果。更高原生规格可能增加功耗或降低每帧可用码量，是否改善体验必须实测。

## 编码效率

相关代码：[VideoCodecs.java](android/src/main/java/dev/androidstream/root/VideoCodecs.java)、[VideoPolicy.java](android/src/main/java/dev/androidstream/VideoPolicy.java)。

当前选择首个符合硬件、Surface、Profile 和尺寸／帧率条件的编码器，不代表它已被实测为最优。候选实验按顺序独立进行：

| 对照 | 目标 | 需要检查 |
| --- | --- | --- |
| CBR 与 VBR | 同等观感下减少平均码率，或改善动态清晰度 | 滑动细节、关键帧峰值、码率恢复、延迟与温度 |
| Baseline 与 High / CABAC | 提高压缩效率 | 实际输出 Profile、保持无 B 帧、浏览器兼容、编码耗时与重排序 |
| 合格硬编组件之间对照 | 确认目标手机的合适实现 | 实际组件名、输出帧率、功耗、长期稳定性 |

保持相同分辨率、目标 FPS、硬件码率范围和网络条件，一次只改变一个变量。高压缩率不保证低延迟；请求某个 MediaFormat 参数也不保证硬件采用。上述候选均未实现或验证，不增加用户配置或第二条编码路线。

## 输入链路

```text
浏览器键鼠 → pointermove 按 rAF 合并
→ motion / control DataChannel → JSON 校验
→ 同进程命令队列 → 再次解析 → Binder 输入注入
```

相关代码：[RtcSession.java](android/src/main/java/dev/androidstream/RtcSession.java)、[RootCapture.java](android/src/main/java/dev/androidstream/root/RootCapture.java)、[InputAccess.java](android/src/main/java/dev/androidstream/root/InputAccess.java)、[app.js](web/app.js)。

输入与视频／音频启停、屏幕操作共用命令执行线程。媒体停止含线程等待，可能推迟后续输入及释放；等待上限不代表每次都等满。输入注入本身不逐次启动 su 或 shell。

优先隔离耗时媒体生命周期操作，保留启停次序、会话隔离和清理屏障；不能以异步化引入新旧编码器重叠或旧会话输入。移动可合并，按下、抬起和取消必须正确处理，所有队列继续有界。

同进程交接可使用已校验的结构化命令，减少重复 JSON 序列化／解析，但保留网络边界的完整校验。暂不优先引入二进制输入协议。

## 音频链路

```text
AudioRecord REMOTE_SUBMIX → 20 ms PCM16 块
→ WebSocket 发送队列 → 与信令共用 TCP
→ 浏览器 PCM 转 float → AudioBuffer / AudioBufferSourceNode
→ Web Audio 输出
```

相关代码：[AudioAccess.java](android/src/main/java/dev/androidstream/root/AudioAccess.java)、[RootCapture.java](android/src/main/java/dev/androidstream/root/RootCapture.java)、[LocalWebServer.java](android/src/main/java/dev/androidstream/LocalWebServer.java)、[app.js](web/app.js)。

### 已确认的行为

- 原始 PCM 约 1.536 Mbps，通常每秒 50 块；每块发送封装和网页播放都有对象分配。
- Root 复用采集数组，但入发送队列前已同步复制，不是下一次采集覆盖在途数据。
- 队列满时丢新块，旧块继续发送，可能保留已经过时的声音。清空 Java 队列也无法撤回已进入 TCP 的积压数据。
- 音频和信令共用 TCP，存在队头阻塞影响。
- 采集缓冲容量不等于固定播放延迟。网页有计划播放缓冲，但无完整的音频 PTS 和严格音画同步，长期可能漂移。

### 优化方向

先按音频时效预算处理积压，评估是否应丢弃最旧的未发送块，并减少不必要的封装分配。验证连续性、爆音、断线和隐藏／恢复页面行为。

长期可评估接入 WebRTC Opus 音频，以利用现有实时传输机制；这是音频链路改造，不能作为简单替换宣称收益。AudioWorklet 通常要求安全上下文，当前局域网 HTTP 部署不能直接视为满足条件。

## 网页、管理与物理屏幕

| 链路 | 审查结论 | 优化优先级 |
| --- | --- | --- |
| 浏览器视频 | 原生 video 播放，无逐帧 Canvas；实际硬解仍需验证 | 保留，检查浏览器解码与抖动缓冲 |
| 连接统计 | 仅展开、可见、连接期间采样，无 Root 性能轮询 | 保持精简 |
| HTTP 网页资源 | 启动加载内存，不逐帧读取磁盘 | 低 |
| App 管理 IPC | 仅状态／停止，媒体不经过 App | 低，无须迁移媒体或引入共享内存 |
| App 状态刷新、启动时钟任务 | 有减少无效唤醒的空间 | 排在媒体和输入之后 |
| 物理息屏 | 面板省电与编码／网络耗能是不同问题 | 单独实测功耗及采集兼容性 |

## 验证与实施顺序

1. 建立手机基线：静止、静止后回主页、连续滑动、视频播放、旋转、音频开关和后台切换。
2. 优先测量视频排队和输入阻塞，再分别修改时间戳／提交策略、媒体启停调度。
3. 独立对照编码模式和 Profile，比较清晰度、平均／峰值码率、延迟与温度。
4. 处理音频积压和对象分配，再决定是否值得改造音频传输。
5. 仅在证据支持时深入 JNI、媒体缓冲和 WebRTC 占位帧适配。

使用 Perfetto、浏览器外部 WebRTC 工具和短期调试采样定位，不增加常驻逐帧日志或周期 Root 诊断协议。正式性能记录包含设备、系统、浏览器、网络、代码／APK 版本、实际编码器、媒体参数、音频和屏幕状态，以及测量方法。

队列驻留、编码耗时、RTT 和解码均值分别衡量局部环节。端到端延迟须测实际输入到画面变化，记录 P50/P95、样本量和误差；跨设备时间戳须校准。桌面测试和浏览器回环不能替代小米 15 / Android 16 / KernelSU 实机验证。

每步改动需验证关键逻辑、断线释放、旋转及长期稳定性，再决定保留或回退。本文不记构建命令、APK 哈希或版本验收流水，避免与 README 重复维护。
