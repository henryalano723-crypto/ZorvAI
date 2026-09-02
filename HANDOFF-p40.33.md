# ZorvAI P40 通用控制修复：p40.33 工作交接

更新时间：2026-09-01

> 当前有效状态以文末“2026-09-01 p40.38 最新接手状态”为准；中间 p40.34 及更早内容仅保留为历史证据。

## p40.34 联系人分区过滤（2026-09-01，优先于下方旧状态）

- 根因已确认：视觉模型对整张搜索页的同名文字命中数量与 `visual_verified` 判断会波动；旧逻辑在模型回传 `visual_verified=true` 时仍可能绕过本地候选过滤，直接采用坐标。
- `candidate_options` 现在要求每个同名文字命中都携带 `section`：`contact`、`group`、`chat_history`、`web_search` 或 `other`。
- `SELECT_CONTACT` 阶段不再信任模型声称的数量或 `visual_verified`：本地只保留 `section=contact` 且名称精确匹配的行；同一坐标上的字号/高亮重复命中只计一次。
- 过滤后恰好一个真实联系人时直接进入；两个以上真实联系人时才生成编号询问；没有可验证联系人时只重新观察一次，随后安全终止。
- 新增整页混合分区、两个真实联系人及同坐标高亮去重测试。完整 `:app:testFullDebugUnitTest` 共 290 项，0 失败、0 错误；`git diff --check` 通过。
- 版本：`1.16-p40.34 / 2026082930`；提交 `cca2f4f` 已推送至 `origin/fix/v1.16-p40`。
- 唯一一次正式构建：GitHub Actions run `33436996962`，结果 `success`。
- 正式 APK：`E:\LocalAI\downloads\ZorvAI\ZorvAI-v1.16-p40.34-run33436996962\app-full-release.apk`，大小 `287392657` 字节，SHA-256 `CA5D68327328421D7AD9CAF98564A3E843893FC325570905D4CD2BEA5745366B`。
- APK v2/v3 签名有效，证书 SHA-256 仍为 `7bb02d764febd05e0fca9d7256f90bb17e268def0de0af52a2d13830516aad24`；仅 `arm64-v8a`，35 个 `.so`。
- 已在设备 `VEG0220924009874` 用 `adb install -r` 保数据覆盖安装；首次安装时间仍为 `2026-08-30 18:31:31`。Zorv/Gotcha 无障碍均真实 Bound，Binding/Crashed 为空；Shizuku server PID `21335` 且 Zorv 授权为 `granted=true`；讯飞仍是默认输入法；安装后无新的 Zorv crash。
- 验证边界：代码、完整单元测试、唯一正式构建、签名、ABI、保数据安装与离线服务链已完成。尚未在微信真实搜索页做一次“不发送消息”的视觉端到端复测，因此不能宣称真实页面波动问题已最终验收。

## 工作现场

- 设备：`VEG0220924009874 / p40.30`
- 系统：华为 P40，HarmonyOS 4.2 / EMUI 14.2 兼容环境，Android 12 API 31
- 后续唯一工作目录：`E:\LocalAI\.zorvai-v1.16-p40-p40.33`
- 基线提交：`83712d62eb04e445be49797d56bfd9c5cd46cbb2`
- 当前安装版本：`1.16-p40.32 (2026082928)`
- 当前工作树为 detached HEAD；完成后用明确 refspec 推送到 `origin/fix/v1.16-p40`。

不要修改旧目录 `E:\LocalAI\.zorvai-v1.16-p40`。它停在较早提交 `41f6bd2`，还保留多项未提交/未跟踪修改，不能 reset、覆盖或粗暴复制。

C 盘的 `...\work\p40.31-build-openssl` 只是此前为保护旧 E 盘脏工作区创建的干净副本；p40.33 已迁回上述 E 盘新工作树。

## 最新真机事实

用户后来那次完整发送指令是用户手动发出的，不是 Zorv 自动续跑。Zorv 正确解析联系人“灵儿”并调用 `send_message_in_app`，但微信停在 `com.tencent.mm.plugin.fts.ui.FTSMainUI`，搜索框始终为空。本次失败提前到了“搜索框聚焦/填写联系人”阶段。

该页面是自绘页面：截图能看到搜索框，但节点树只有空根节点。不要只继续修联系人点击。

禁止用 `uiautomator dump` 做在线诊断；它会在这台鸿蒙 P40 上触发无障碍服务解绑再重绑，污染现场。只使用截图、Zorv 日志及无干扰系统状态检查。

Zorv Agent IME 已启用：`com.ai.assistance.quro/.service.QuroAiKeyboardService`；默认输入法仍是讯飞。因此当前更像没有可靠建立真实焦点或输入降级链未完成，不是 Agent IME 未授权。

鸿蒙 `com.huawei.hiai/.accessibility.VanAccessibilityService` 会自动加入并绑定，可能竞争，但证据不足时不能直接定罪。

## p40.32 已完成

- 真实前台只信任原始 `rootInActiveWindow`，不让陈旧 `actionableRoot` 决定窗口身份。
- 要求连续两次获得目标应用根窗口。
- 使用 `moveTaskToFront(taskId, 0)` 恢复任务，不再使用 `MOVE_TASK_WITH_HOME`。
- 截图后恢复 Zorv 前台，降低鸿蒙在模型/网络等待期间休眠 Zorv 的概率。
- 联系人选择已改为 Shizuku `input tap` 优先、无障碍备用，并验证点击后页面稳定变化。
- 模型回错 `resume_stage` 时丢弃旧坐标、恢复目标应用、重新截图，不再直接终止事务。

正式构建 run：`33392036363`。APK：

`C:\Users\admin\Documents\Codex\2026-08-31\zorvai-p40-e-localai-zorvai-v1\outputs\ZorvAI-v1.16-p40.32-run33392036363\app-full-release.apk`

APK SHA-256：`A949E55837C396842734905680FA8C7BF5908FE184DA0825EFFF9BC845C7F350`

签名证书 SHA-256：`7bb02d764febd05e0fca9d7256f90bb17e268def0de0af52a2d13830516aad24`

## 用户要求 p40.33 本轮全部完成

1. 搜索框点击改为 Shizuku 优先。
2. 验证真实焦点后才输入。
3. Agent IME 输入后必须回读。
4. 截图和坐标绑定页面版本。
5. 失败只允许一次备用通道，然后重新观察。
6. XML 有效时使用结构化节点。
7. XML 为空时自动切换截图视觉。
8. 点击、输入、滑动共用统一验证策略。
9. 成功路径可以缓存，但每次必须重新核验。
10. 不写微信专用逻辑，不增加平行路径。

统一目标：

`XML/节点树 + 截图双通道观察 → 带版本目标 → 选择执行通道 → 动作 → 稳定验证 → 成功继续或重新观察`

设计参考只取长处：Zorv 的原生工具/Shizuku/事务安全；Zafiro 的带版本目标；MobileClaw 的截图视觉/SoM/坐标映射；Neuron 的观察→计划→动作→验证→有限重规划。

## 已审计出的缺口

文件：`app/src/main/java/com/ai/assistance/quro/core/tools/QuroToolsMessaging.kt`

- `SELECT_CONTACT` 已使用 Shizuku 点击优先。
- `VERIFY_SEARCH_FIELD`、`VERIFY_CONVERSATION`、`VERIFY_DRAFT` 仍直接调用 `dispatchPointClick`。
- 坐标目前只校验截图尺寸范围，没有校验属于哪一版截图。
- `PasteFocusedTextTool` 已有 Agent IME、InputConnection 检查和视觉变化要求，但没有接入统一聚焦/备用通道执行器。
- 通用 `tap_screen` 有节点/视觉验证；`swipe_screen` 仍主要把手势派发当作结果。

## 当前半成品修改

尚未编译、测试或提交：

1. 新增 `app/src/main/java/com/ai/assistance/quro/core/tools/VerifiedUiActionExecutor.kt`，目前只是骨架，包含：
   - `SHIZUKU` / `ACCESSIBILITY` 路由。
   - `SAFE_TO_REPEAT` / `DISPATCH_ONCE` 风险等级。
   - 最多一次备用路由。
   - 成功路由缓存，但每次仍运行验证。
   - 页面版本/坐标范围检查与连续稳定变化判断。
2. `QuroToolsMessaging.kt` 只新增了：工具参数 `observation_version`、事务字段 `observationVersion`、联系人编号回复回传当前版本。

仍需完成：

- `captureVisualStage()` 生成单调递增观察版本并写入 JSON。
- `resumeVisualTransaction()` 拒绝缺失/过期版本并重新截图。
- `requiredVisualPoint()` 接入版本校验。
- 全部视觉点击阶段接入统一执行器。
- 搜索框/消息框先证明目标 InputConnection，再且只输入一次。
- 输入后截图回读；不确定状态禁止再次输入。
- `tap_screen` / `swipe_screen` 接入共同稳定验证策略。
- 双通道观察模式和成功路由缓存落地。
- 补齐单元测试。

当前预期 `git status --short`：

```text
 M app/src/main/java/com/ai/assistance/quro/core/tools/QuroToolsMessaging.kt
?? app/src/main/java/com/ai/assistance/quro/core/tools/VerifiedUiActionExecutor.kt
?? HANDOFF-p40.33.md
```

## 安全与发布约束

- 动作“已派发”不等于执行成功，必须通过节点、视觉或语义结果验证。
- 聚焦等幂等动作可在未验证时使用一个备用通道。
- 发送、删除、支付等不可逆动作派发后结果不确定时绝不换通道重试，必须重新观察。
- Agent IME 在证明目标应用存在 InputConnection 前禁止输入。
- `commitText` 已成功但回读不明时，禁止再次输入。
- 缓存只能保存上次成功的执行通道，不能缓存坐标或成功结论。
- 不运行 `uiautomator dump`。

至少补充并通过：页面版本、过期坐标、备用通道上限、不可逆动作不重试、成功路由仍重新验证、连续两帧变化、XML/截图模式、焦点和单次输入测试；保留原有联系人编号/语音序号/“都不是”和 `ExternalUiTargetSessionTest`；最后执行 `git diff --check`。

建议版本：`1.16-p40.33`，versionCode 在 `2026082928` 基础上递增一次。

本地测试全部通过后才提交推送。只触发一次 `build-patched-apk.yml` 正式构建；成功后核对固定签名并覆盖安装，不得为试错重复触发 GitHub 编译。

## 2026-09-01 p40.38 最新接手状态

已完成并推送提交：`729e71a fix(agent): bind contact choices to visual rows`

目标分支：`origin/fix/v1.16-p40`

版本：`versionCode=2026082934`，`versionName=1.16-p40.38`

正式 GitHub Actions 仅触发一次并成功：

- Run：`33508498178`
- Job：`99858214073`
- 结论：success，17m59s
- GitHub 测试、签名 release、artifact 上传全部成功

APK：`E:\LocalAI\downloads\ZorvAI\ZorvAI-v1.16-p40.38-run33508498178\app-full-release.apk`

APK 大小：`287395384` bytes

APK SHA-256：`E45A6429429D97B95BFF9F0BFECBFE68C0B0C4CCA3F28E0FC50868E97AC7F3FB`

### 本轮真机证据与最终整改

- 同一微信搜索结果页进行了两次只读视觉测试，两次都正确得到 1 位联系人，全程未点击结果、未进入聊天、未发送消息。
- 实际页面的联系人位于通用标题“最常使用”下，联系人身份由结果行内“联系人”标签证明；页面另有“群聊”“聊天记录”和网络搜索中的多个“灵儿”。
- p40.38 不写死“最常使用”或微信：每个可点击结果整行只生成一个候选和一个坐标。
- 本地代码只在最近分区标题或同一行矩形内的可见角色标签证明其为联系人时保留候选；非联系人分区优先否决。
- 同一行重复 OCR/高亮命中按行矩形去重，一个候选只对应一个点击坐标。
- 一个真实候选自动使用该坐标；多个真实候选保存编号→坐标映射。用户输入 `1/2/...` 时由本地待选择状态机直接恢复对应坐标，不把数字输入目标 App。
- 修复只读命令“打开微信，查看当前搜索结果……”被错误编译成新 App 内搜索的问题。
- 本地定向测试执行两轮，均 `BUILD SUCCESSFUL`；GitHub 工作流已纳入 `AppSearchIntentCompilerTest`。

### p40.38 安装后真机只读验收（2026-09-01 21:27—21:33）

- 在同一真实微信搜索结果页连续执行两次完全相同的只读指令；页面含 1 个“灵儿 / 联系人”行、1 个名称包含“灵儿”的群聊，以及多条聊天记录和网络搜索命中。
- 第一次返回联系人数量 `1 位`；第二次虽然描述格式略有变化，最终仍返回联系人数量 `1 位`，均未把群聊、聊天记录或网络搜索计入联系人。
- 两次执行期间前台始终停留在 `com.tencent.mm.plugin.fts.ui.FTSMainUI`，未点击搜索结果、未进入聊天、未发送任何微信消息。
- 验收后 Zorv/Gotcha 无障碍仍为 Bound，Binding/Crashed 为空，近期无 Zorv FATAL/ANR。

### 接手后必须检查

1. p40.38 已于 `2026-09-01 21:17:46` 用保数据覆盖方式安装到设备 `VEG0220924009874`；不要重新触发 GitHub 构建。
2. 已核对包版本为 `2026082934 / 1.16-p40.38`，首次安装时间仍为 `2026-08-30 18:31:31`，应用数据未被卸载清除。
3. 已核对 Zorv 与 Gotcha 无障碍均为 Bound，`Binding services:{}`、`Crashed services:{}`；Zorv 进程 PID 为 `14314`。
4. 默认输入法仍为讯飞 `com.iflytek.inputmethod/.FlyIME`；安装后的近期日志无 Zorv FATAL/ANR 标记。
5. p40.38 的同页两轮只读视觉验收已经通过；任何外发消息仍未获授权。
6. 正式联系人点击链路如需测试，只能在用户明确授权具体任务后进行；发送动作必须单独符合用户授权。

## 2026-09-01 p40.39 冷启动窗口恢复修复

- 用户明确授权从打开微信到结束，向联系人“灵儿”且只发送一次正文“发错”。p40.38 首次复用已打开聊天页时因视觉核对无法确认而安全终止；第二次先关闭微信再从头执行，微信实际经过 `WeChatSplashActivity` 成功进入 `LauncherUI`，但 Zorv 返回 `❌ [恢复现场] 无法获得目标应用窗口`，在点击搜索前终止。两次均未输入、未发送，“发错”尚未发出。
- 真机时间证据显示冷启动后约 2 秒才出现微信目标包窗口；旧 `rootForAutomation` 稳定等待为 `12 × 125ms`，约 1.5 秒，因而会在华为冷启动完成前误判失败。
- p40.39 采用通用最小修复：`rootForAutomation` 支持调用点传入稳定等待次数；仅 `send_message_in_app` 打开目标 App 后的首次窗口恢复使用 `40 × 125ms`（上限约 5 秒），普通读屏、点击、恢复和后续阶段仍使用原短等待，不写死微信包名或页面坐标。
- 新增冷启动经历 28 个 launcher 样本后才出现目标窗口的回归测试。定向测试通过；完整 `:app:testFullDebugUnitTest` 共 302 项，0 失败、0 错误；`git diff --check` 仅有 CRLF 提示，无空白错误。
- 版本为 `versionCode=2026082935`、`versionName=1.16-p40.39`；提交 `c21dc1e075e4a3c20ae5bcde4eafd5af306e7d2a` 已推送到 `origin/fix/v1.16-p40`。
- p40.39 唯一正式 GitHub Actions 构建已成功：Run `33527086923`、Job `99920575837`，18m43s；不得再次触发本版本构建。
- 正式 APK：`E:\LocalAI\downloads\ZorvAI\ZorvAI-v1.16-p40.39-run33527086923\ZorvAI-v1.16-p40.38-full-release\app-full-release.apk`。内层 artifact 目录名仍为工作流旧标签 p40.38，但 APK 实读版本为 p40.39；大小 `287395450` 字节，SHA-256 `06562FE7521109FF2E72CE33E3D76D7EC47BDEDC52B266B9F1B32A5D6D968CAC`；v2/v3 签名有效，证书 SHA-256 仍为 `7bb02d764febd05e0fca9d7256f90bb17e268def0de0af52a2d13830516aad24`。
- 当前阻塞：构建完成后设备 `VEG0220924009874` 从 ADB 消失，Windows 也未枚举到华为 USB 设备；首次 p40.39 安装命令在传输前失败，手机仍为 p40.38，应用数据未受影响。
- 下一步：恢复手机 ADB 连接后用 `adb install --no-streaming -r` 保数据覆盖安装上述 APK，核对 p40.39、无障碍 Bound、讯飞默认输入法及无崩溃；然后在用户已明确给出的同一授权范围内重新执行一次从打开微信到只发送“发错”的完整端到端测试，并核对聊天只新增一条。

## 2026-09-02 p40.41 联系人序号直接绑定已保存坐标

- p40.40 当前源码提交为 `ab8dedb`：目标窗口恢复时优先选择当前聚焦/活动应用窗口，降低系统窗口、输入法窗口干扰。
- p40.41 把首次搜索结果中生成的联系人编号与坐标冻结在本地 `VisualTransaction.pendingContactChoices` 中。
- 用户用文字或语音回复 `1/2/...` 时，内部续接调用只携带 `selected_contact_choice_index`；本地代码按序号读取已经保存的坐标，不再要求模型返回 `candidate_options`，也不再因模型重新识别而改变候选数量。
- 序号无效或超过已保存候选数量时拒绝点击；有效序号通过原目标应用窗口检查后直接进入既有联系人点击与页面变化验证链路。
- 联系人点击仍需证明页面稳定变化后才会定位消息输入框；正文只允许输入一次，发送动作只派发一次，结果不确定时禁止换通道重复发送。
- 版本为 `versionCode=2026090202`、`versionName=1.16-p40.41`。
- 新增“序号解析为本地已保存坐标且不需要新视觉候选”的单元测试；定向测试通过，完整 `:app:testFullDebugUnitTest` 共 305 项，0 失败、0 错误、0 跳过；`git diff --check` 仅有 CRLF 提示，无空白错误。
- 本节写入时尚未触发 p40.41 正式 GitHub Actions 构建，也尚未安装或完成真机链路验收；正式构建仍只能触发一次。
- 2026-09-02 15:03 已通过无线 ADB 启动 Shizuku 13.5；设备存在 `shizuku_server` 进程，系统包权限显示 Zorv 的 `moe.shizuku.manager.permission.API_V23: granted=true`。
