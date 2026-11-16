# 大肥鱼 Companion · M4 功能测试报告

- 版本：`0.1.0 · M4`（Debug APK）
- 包名：`com.dafeiyu.companion`
- 测试日期：2026-11-16
- 测试环境：Windows 电脑上的 Android 模拟器 `TestAVD`，Android 15（API 35），1080×1920，headless
- 模型接口：本机 OpenAI 兼容 mock 服务（`127.0.0.1:18766`，通过 `adb reverse` 映射到模拟器）
- 测试范围：多 AI 房间的建群、群聊调度、消息持久化、删除隔离，以及主聊天、设置、深色模式等回归路径
- 不包含：性能压测、长稳测试、真实模型服务质量、上架合规审查

## 结论

多 AI 房间的关键路径可用：可以创建房间、选择至少两张角色卡、让三个角色按人设依次接话；`@` 提及会改变接话顺序；明确的「先别回」指令不会触发回复；多房间数据相互隔离，删除房间会级联清理消息；进程重启后房间与消息仍然存在。

本轮共执行 19 项检查，19 项通过。测试中发现的 1 个阻塞级缺陷（主线程网络调用）已修复并回归通过。

## 用例与结果

| 编号 | 检查项 | 预期 | 结果 | 证据 |
|---|---|---|---|---|
| TC-01 | 冷启动 | 启动页结束后进入主聊天，不崩溃 | 通过 | 模拟器截图 |
| TC-02 | 主聊天发送与回复 | 用户消息落库，角色返回一条回复 | 通过 | 截图、logcat |
| TC-03 | 签到与投喂 | 签到鱼粮 +20，投喂 -5，状态即时刷新 | 通过 | 截图 |
| TC-04 | 设置页入口 | 「多 AI 房间」显示「已上线」，可进入房间列表 | 通过 | [05-settings.png](screenshots/05-settings.png) |
| TC-05 | 房间空态 | 无房间时显示说明与「＋新建房间」 | 通过 | 录屏 `clip1` |
| TC-06 | 建群弹窗 | 可输入房间名，展示角色卡复选框，少于 2 张时不可建群 | 通过 | [04-create-room.png](screenshots/04-create-room.png) |
| TC-07 | 创建三人群 | 选择大肥鱼、小鲸鱼、小海星，房间创建成功，成员显示正确 | 通过 | [02-room-list.png](screenshots/02-room-list.png) |
| TC-08 | 三人依次接话 | 一条用户消息触发多个角色按调度顺序回复 | 通过 | [test-05-three-replies.png](screenshots/test-05-three-replies.png) |
| TC-09 | 连续发言上限 | 同一角色连续发言不超过 2 条 | 通过 | [test-01-room-replies.png](screenshots/test-01-room-replies.png) |
| TC-10 | `@` 提及 | `@小鲸鱼` 后小鲸鱼优先接话，随后其他角色补话 | 通过 | [test-02-mention.png](screenshots/test-02-mention.png) |
| TC-11 | 沉默指令 | 发送「你们先别回，我去拿个碗」后无 assistant 消息落库 | 通过 | [test-03-no-reply.png](screenshots/test-03-no-reply.png) |
| TC-12 | 双房间隔离 | 第二个房间的消息只写入自己的 `room_id` | 通过 | [test-04-two-rooms.png](screenshots/test-04-two-rooms.png) |
| TC-13 | 删除房间 | 删除「夜宵摊」后房间数变为 1，该房间消息数为 0 | 通过 | 截图、SQLite 查询 |
| TC-14 | 进程重启持久化 | 强制停止并重启后，房间、成员、消息仍存在 | 通过 | 重启后截图 |
| TC-15 | 新建角色卡 | 文字创建「小海星」，生成 `card_23542743.json`，可被拉进群 | 通过 | 截图、文件检查 |
| TC-16 | 深色模式 | 主聊天深色主题下文字、气泡、输入框对比度正常 | 通过 | [test-06-dark.png](screenshots/test-06-dark.png) |
| TC-17 | 数据库结构 | `rooms`、`room_members`、`room_messages` 三表存在，外键级联生效 | 通过 | SQLite `.schema` |
| TC-18 | 调度单测 | `RoomSchedulerTest` 8 个 JVM 单测全部通过 | 通过 | Gradle 测试输出 |
| TC-19 | 回包装载 | 新 APK 覆盖安装后保留原有数据，设置页显示 `0.1.0 · M4` | 通过 | [05-settings.png](screenshots/05-settings.png) |

## 缺陷与修复

### D-01（阻塞，已修复）主线程发起网络请求

- 现象：群聊发送后没有任何回复，logcat 无明确错误，异常消息为 `null`。
- 定位：`CompanionViewModel` 在 `viewModelScope`（主线程）直接调用 `RoomChatCoordinator.send`，触发 `NetworkOnMainThreadException`。
- 修复：将 `roomCoordinator.send(...)` 整体放入 `withContext(Dispatchers.IO)`。
- 回归：同一用例连续执行多次，回复正常落库。

### D-02（环境，已处理）模拟器无法访问本机 mock

- 现象：mock 仅绑定 `127.0.0.1` 时，模拟器通过 `10.0.2.2` 不可达。
- 处理：mock 改为 `--host 0.0.0.0`，并在模拟器执行 `adb reverse tcp:18766 tcp:18766`。

### D-03（环境，已处理）覆盖安装签名冲突

- 现象：`INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
- 处理：卸载旧 Debug 包后重装；后续同签名包使用 `install -r` 保留数据。

## 未覆盖项

- 5 分钟时间分隔的显示边界。
- 超长消息的换行与气泡最大宽度。
- 模型接口不可用时的错误提示条、重试与超时。
- 群聊「回复指定角色」：数据层已有 `reply_to` 字段，UI 未暴露入口，属于未实现而非回归缺陷。
- 真实模型连续多轮的人设稳定性与内容安全评估。

## 测试素材说明

群聊截图中的重复台词来自脚本化 mock 服务按关键词匹配返回固定台词，用于稳定录制演示视频，不代表产品会重复回复。真实模型回复由所配置的接口决定。
