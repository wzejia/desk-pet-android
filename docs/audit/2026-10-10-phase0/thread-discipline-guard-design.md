# 线程纪律 3 件·守卫测试设计（Phase 0 交付，2026-10-10）

> Spec `refactor-structure.spec.md` §5.1/§6 Phase 0 点名交付：**设计**（施工在 Phase 2 评审定案 / Phase 3 修，变异锚测试先行）。
> 方法论与 PetOverlayState 活性记账守卫同款（2026-10-10 已落地验证）：**并发守卫逻辑抽纯 JVM 类，Android 类薄接线**——仓内无 Robolectric/androidTest（构建配置实测），线程类守卫只有这条路可 JVM 测。
> 变异锚纪律：每件设计给出「删守卫必红」的具体变异形态；变异存活 = 不是真守卫 = 删（PetOverlayStateTest 变异 2a 实证过此纪律）。

## 件 1：ocr[202] onWake TOCTOU（destroyed 读与 startListening 不原子）

**现状**（VoiceForegroundService，现行 :215-225 一带）：唤醒回调（引擎解码线程）读 `@Volatile destroyed` 后才 `session.startListening(...)`；onDestroy（主线程）可插入两者之间——startListening 在已 null 掉 kwsSink、已 stopIdleCapture 的 session 上重新武装采麦 = **孤儿 capture 无主清理**。

**守卫设计**：抽 `WakeEntryGate`（纯 JVM）——把「进入唤醒处理」与「teardown」做成互斥临界区：

```kotlin
class WakeEntryGate {
    /** 唤醒处理临界区：close 已调则 no-op 返回 false；否则持读锁执行块（close 会等待其完成）。 */
    fun runExclusive(block: () -> Unit): Boolean
    /** teardown 侧：关闭门并等待 in-flight 临界区完成（onDestroy 调）。幂等。 */
    fun close()
}
```

service 接线：`onWake` 内 destroyed 预检保留（快路径省锁），`session.startListening(...)` 包进 `gate.runExclusive`；`onDestroy` 在清 kwsSink 前 `gate.close()`。

**JVM 测试**（WakeEntryGateTest，CountDownLatch 构造确定性交错）：
1. close 先完成 → runExclusive no-op 返 false；
2. runExclusive 在途（barrier 卡住）→ close 阻塞至块完成才返回；
3. close 后再 close 幂等；
4. 压力锚：双线程 N 轮随机交错，断言「close 后块执行次数=0」。

**变异锚**：`runExclusive` 去锁（check 与执行分离）→ 测试 2/4 红。

**修复归属**：Phase 3（root 地界，主对话施工；与 ocr[26] stopSelf(startId)、ocr[27] 校准不可取消同窗定案）。

## 件 2：ocr[205] 裸 Thread + 手动 @Volatile 生命周期（init/shadow-init/校准不可取消）

**现状**（VoiceForegroundService `startKwsIfNeeded` 等）：init 走裸 `Thread{}`，生命周期靠 `destroyed` @Volatile + lock + initGate 信号量手工拼装；校准 `runBlocking` 挂 init 线程不可取消（ocr[27]）；stale `stopSelf()` 竞态（ocr[26]）。三件同根：生命周期事件（destroy）无法传播到裸线程。

**守卫设计**：service-scoped `CoroutineScope(SupervisorJob() + Dispatchers.Default)` 注入（构造参数，测试可传 TestScope），init/shadow-init/校准改 `scope.launch`；破坏性动作（engine 赋值、校准写阈值）前查 `coroutineContext.isActive`。`onDestroy` 单点 `scope.cancel()`。ocr[26]/[27] 随此刀一并消解（stopSelf(startId) 保留显式修）。

**JVM 测试**（KwsBootstrapTest，runTest + 虚拟时钟）：
1. bootstrap 进行中 cancel → onReady 不回调（engineFactory 结果被丢弃）；
2. cancel 后再 bootstrap → 立即拒绝；
3. 正常路径 onReady 恰好一次。

**变异锚**：onReady 回调删 `isActive` 检查 → 测试 1 红。

**修复归属**：Phase 3（root 地界，主对话施工；改 service 生命周期骨架，评审定案后动）。

## 件 3：ocr[53] VoiceClientFactory 进程级 scope 无 teardown

**现状**（VoiceClientFactory singleton 内）：`CoroutineScope(SupervisorJob() + Dispatchers.Default)` 进程级创建、永不 cancel、不暴露——只在「singleton 永不重建」的隐式契约下安全；未来引入 reset 路径（登出/设置触发重建/测试 teardown）即漏。

**守卫设计**（轻）：scope hoist 为 object 命名属性 `processScope` + KDoc 显式声明进程级契约 + `@VisibleForTesting internal fun resetForTest()`（cancel 旧 scope + session 单例失效 + 重建空 scope）。

**JVM 测试**（VoiceClientFactoryProcessScopeTest）：
1. resetForTest 后旧 `processScope.isActive == false`；
2. reset 后新 scope 活跃、session 重建非同一实例。

**变异锚**：resetForTest 漏 cancel → 测试 1 红。

**修复归属**：Phase 3（voice 子包地界，可并行票据）。

## 备案

- 三件全部「抽纯类 + 薄接线」，不为测试引入 Robolectric/新依赖（spec §5.4 同款边界：不新建测试基建）。
- 压力锚（件 1 测试 4）的 N 取 10_000 轮上限 + 失败即红——确定性交错测试为主，压力测试只做冗余面。
- 件 1/件 2 改 VoiceForegroundService 生命周期骨架，与 spec Phase 2 大文件拆分评估（root 文件不在 >750 清单，无冲突）；件 3 落 voice 包，Phase 2 子包化后归 pipeline 组。
