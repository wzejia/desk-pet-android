# 09 · M3 / M4 计划书：语音链路 / ROM 加固

> **【2026-08-03 注】M3 半篇已由助手化切片 S4/S5 按对账表执行完毕并收口**
> （语音链路+KWS 常开；当前基线 358 测）；**M4 半篇（ROM 加固/保活）仍指向未来工作**，
> 是本手册唯一前瞻内容。

## M3：语音链路（估时 2-3 人周，知识包 §9）

> **开工前铁律（教训 #47）**：Kotlin 是第三栈，先拿 `docs/voice-param-reconciliation-2026-07-28.md`
> 参数对账表逐项对照，实现后按表验收。若发现知识包 §3.4 与桌面端 `pet/chat/voice_listen.py`
> 当前 HEAD 不一致，以 voice_listen.py 为准并回写知识包（部署机 VAD 排查结论可能改了参数）。

### 任务拆解

1. **采麦管线**：AudioRecord 16kHz/Int16/mono，块 100ms = 1600 样本 = 3200 字节；
   **单条采麦流 KWS 与 VAD 复用**（半双工第三层，教训 #33/37）；音频回调只推队列、decode 在消费线程
2. **VAD**（照抄最小解，教训 #17，别加自适应层）：
   speech_level=0.05 / 判起 3 块(300ms) / 判停 8 块(800ms) / min_utterance 3 块 /
   listen_timeout 30s / max_utterance 8s / pre_roll 3 块
   - **pre-roll deque 容量 = 6**（判起 3 + pre_roll 3，同长则触发前保留实为 0，教训 #16）
   - 只累积说话块，尾部静音不进 buffer（防 SenseVoice 幻觉，教训 #14）
   - 超时日志必带「期间最大 level」（≈0.0001=权限/没开麦，0.01-0.05=阈值问题，教训 #19）
3. **ASR provider**：multipart `file`(utterance.wav) + **`language="zh"` 显式**（教训 #13）+
   `serviceName="sensevoice"`（教训 #15）；16k PCM 包 WAV 头；空文本视为失败；
   纯标点结果不送对话（教训 #21）
4. **TTS 分句+消毒**（§3.1 全表照抄）：
   - 切点 `。！？!?;`+`\n`，MIN=4/MAX=30，ASCII 中间切口回退词边界；token 流**逐字符**喂
   - 白名单清洗 markdown/emoji；清洗后无 alnum 跳过合成（教训 #1）；**气泡用原句、朗读用清洗句**
   - text 数组逐句一帧送流式（整段单元素=伪流式 17.8s，教训 #3）；**直连 9803 勿经 166 转发**
     （30s 总时长截断，教训 #2）
   - 流式先缓冲全帧再播；残帧+兜底全量=重复（教训 #7/#10）；多帧 WAV 剥头拼 PCM
   - 失败码 500=无可发音字符；流式 404 永久记住走非流式；单句失败重试 1 次仍败跳过（教训 #9）
5. **播放+reveal**（§3.3）：AudioTrack 24kHz/Int16/mono 阻塞写（天然背压）；
   句时长 `len(pcm)/2/24000` 按字数均分逐字；降级纯文字 4.5 字/秒；收尾等 latency+0.05s
6. **KWS**：sherpa-onnx Android AAR（官方一等支持；集成细节见
   `docs/research-sherpa-onnx-android-2026-07-28.md`）；模型 5 文件打包 assets；
   threshold=0.5、RMS 门控 0.015 **做成可配置+校准入口**（单机校准值，手机麦差异大）；
   num_threads=2/cpu；命中后 reset_stream
7. **状态机+半双工**：四态 idle/listening/processing/speaking；三层半双工
   （状态分发丢块 + KWS pause flag + 单流复用）；TTS 播完回 LISTENING（30s 超时）；
   唤醒词前缀剥除（小朵小朵/小朵/小度小度）
8. **前台服务合规**（决策 P2 已接受成本）：microphone 类型 FGS（Android 14 强制类型声明）+
   常驻通知 + 麦克风权限引导；11+ 后台启动 FGS 不能拿麦（需用户先开 App 路径）

### M3 验收草案

- 唤醒词真麦命中率（门控可调校准后）+ 误触发率主观可接受
- 全链路：唤醒→听音（首字不丢）→ASR（中文不误判）→LLM→分句→TTS 首音 ~2.5s 基线→边念边出字
- 外放场景不自激（半双工三层生效）；TTS 播放期麦块全丢
- 无真麦环境用教训 #50 回环法验链路（TTS 合成→PCM→ASR 转写→比对）

## M4：ROM 加固（估时 2-4 人周）

> 测试机已定 ColorOS（PJD110）——ColorOS 引导路径第一优先级，已有两条实证约束
> （禁 shell 授权 / 禁 am 拉起非导出服务，见 07 文档）。厂商清单见
> `docs/research-rom-permissions-2026-07-28.md`。

1. **权限引导页填实**：小米/vivo「后台弹出界面」、OPPO 自启动、华为 ProtectActivity 逐厂商文案+跳转
2. **保活四件套**：FGS + START_STICKY + 电池优化豁免 + 厂商自启动白名单
   （国产 ROM 杀后台无 100% 技术解——知识包 §8 标注的最大不确定性，预期管理写进用户文档）
3. **耗电**：闲置降帧、熄屏暂停策略
4. **干净机真机验收**：release 包卸载重装全流程；依赖收集必漏清单核对（so ABI / ProGuard /
   权限声明，教训 #43）；打包警告清零
5. **多 ROM 真机矩阵**：按内部用户实际机型铺开

## 里程碑收口纪律（每个 M 都做）

- `/ce-code-review` 独立复核
- 非平凡问题解决 → 写 `docs/solutions/`（ce-compound）
- 协议/素材变动 → 回写桌面端知识包（只读引用但允许回写文档）
