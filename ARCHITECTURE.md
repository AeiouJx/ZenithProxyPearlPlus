# PearlPlus-ng 代码结构与功能说明

> 对应版本：`plugin_version=2.2.1` / `mc=1.21.4` / `maven_group=dev.zenith.pearlplus`
> 本文按「文件 → 类 → 方法（带行号）」三级组织，可直接对照源码阅读。

---

## 0. 一句话概括

ZenithProxy 的珍珠（ender pearl / stasis chamber）自动化插件：**自动发现并登记玩家新抛下的珍珠 → 玩家私语 `/w bot load <id>` → 机器人寻路到 stasis 活板门、瞄准、点击开箱 → 归还/补货/放权**。

---

## 1. 文件清单

| 路径 | 行数 | 角色 |
|---|---|---|
| `src/main/java/dev/zenith/pearlplus/PearlPlusPlugin.java` | 52 | 插件入口，装配所有模块与 tick 回调 |
| `src/main/java/dev/zenith/pearlplus/PearlPlusConfig.java` | 64 | 持久化数据模型（= `plugins/config/pearlplus.json`） |
| `src/main/java/dev/zenith/pearlplus/command/PearlPlusCommand.java` | 439 | `pp` / `pearlplus` 管理命令（brigadier） |
| `src/main/java/dev/zenith/pearlplus/module/AutoDetectModule.java` | 782 | **模块①** 珍珠自动发现与登记 |
| `src/main/java/dev/zenith/pearlplus/module/AutoLoadModule.java` | 178 | **模块②** 私语指令解析与触发装载 |
| `src/main/java/dev/zenith/pearlplus/module/PearlManager.java` | 905 | **核心类** 珍珠数据 CRUD + 装载流程 + 背包/归还 |
| `src/main/java/dev/zenith/pearlplus/module/GrimInteract.java` | 97 | **底层工具** 寻路 / 瞄准 / 安全发包 |
| `src/main/java/dev/zenith/pearlplus/module/PearlRestockModule.java` | 292 | **模块③** 从容器自动补货 |
| `src/main/java/graalvm/dev/zenith/pearlplus/PearlPlusReflectionFeature.java` | 78 | 构建期：GraalVM native-image 反射注册 |
| `src/main/templates/dev/zenith/pearlplus/BuildConstants.java` | 模板 | 构建期注入 VERSION / PLUGIN_ID 等常量 |
| `src/main/resources/META-INF/native-image/dev.zenith/pearlplus/native-image.properties` | 1 | 声明 `--features=PearlPlusReflectionFeature` |

---

## 2. 装配关系（谁调用谁）

```
                        Zenith EVENT_BUS
                              │
        ┌─────────────────────┼─────────────────────────┐
        │                     │                         │
   ClientBotTick        ClientBotTick.Stopped     WhisperChatEvent
        │                     │                         │
        │                     │                         │
 PearlPlusPlugin:40        Plugin:44              AutoLoadModule:25
 ├─ PearlManager.tickPendingLookClick()   (静态)   └─ onWhisper()  ← 唯一入口
 ├─ PearlManager.tickPendingClaimReleases()        
 │                      │                          
 │  AutoDetectModule.onTick → scanForPearls()      
 │  AutoDetectModule.onTick → checkStoredPearlsForMissingEntities()
 │  PearlRestockModule.onTick → 状态机推进
 │
 │  AutoLoadModule.onWhisper
 │      └─► PearlManager.loadPearl(pearl, requester, uuid)
 │              ├─ findNearestTrapdoorAround()
 │              ├─ GrimInteract.pathIntoReach()      → Baritone GoalNear
 │              └─ startLookThenClick()  ──► 静态 pendingLookClick
 │                      （下一 tick 由 Plugin:40 驱动 tickPendingLookClick）
 │                            └─ GrimInteract.lookAt / hasInteractableTarget / useItemOnIfLookingAt
 │                            └─ onPearlLoaded()
 │                                 ├─ releaseClaimAfterLoad() → pendingClaimReleases
 │                                 ├─ handlePearlDropAfterLoad() → DropItem / 讨要私语
 │                                 ├─ PearlRestockModule.requestAfterLoad()   [可选]
 │                                 └─ BARITONE.pathTo(startPos)              [可选]
```

关键设计点：`pendingLookClick` / `pendingClaimReleases` 是 **`PearlManager` 的静态字段**，`PearlManager` 实例本身随 `Module` 创建但只在事件回调里临时 `new` 出来当无状态工具用（`PearlManager:399`）。

---

## 3. PearlPlusPlugin — 插件入口

`PearlPlusPlugin.java`

| 成员 | 行 | 作用 |
|---|---|---|
| `API` / `PLUGIN_CONFIG` / `LOG` | 24-26 | 全局静态句柄，被其他所有类 `static import` |
| `@Plugin(...)` | 14-21 | 元数据：`id=BuildConstants.PLUGIN_ID`、`mcVersions="*"`（标记全版本兼容） |
| `onLoad(PluginAPI)` | 29 | **唯一生命周期方法**（Zenith 约定） |
| ↳ `registerConfig` | 33 | 注册 `PearlPlusConfig`，落盘为 `plugins/config/pearlplus.json` |
| ↳ `registerCommand` | 34 | 挂载 `PearlPlusCommand`（别名 `pp`） |
| ↳ `registerModule` ×3 | 35-37 | `AutoLoadModule` / `AutoDetectModule` / `PearlRestockModule` |
| ↳ `ClientBotTick` 订阅 | 40-43 | 每 tick 驱动两个静态状态机（点击、放权） |
| ↳ `ClientBotTick.Stopped` 订阅 | 44-47 | 机器人断开时清空上述状态，避免残留误点 |

---

## 4. PearlPlusConfig — 数据模型

`PearlPlusConfig.java`

```
pearlplus.json
├─ defaultPearlId : String = "Base"
├─ debug          : boolean = false
├─ players : Map<UUID, PlayerPearls>
│    └─ PlayerPearls { playerName:String, defaultPearlId:String, pearls: Map<String, StoredPearl> }
│         └─ StoredPearl { pearlId:String, x:int, y:int, z:int }
├─ whitelist : Map<UUID, WhitelistedPlayer>   // 仅 username + uuid
├─ autoLoad   : AutoLoadConfig
├─ autoDetect : AutoDetectConfig
└─ restock    : RestockConfig
```

| 内部类 | 行 | 说明 |
|---|---|---|
| `AutoLoadConfig` | 18-26 | 装载侧开关，见 §10 全表 |
| `AutoDetectConfig` | 28-33 | `final` 类，登记侧开关 |
| `RestockConfig` | 35-40 | 补货容器坐标，`enabled` 与 `set-restock-container` 联动 |
| `PlayerPearls` | 42-46 | 单玩家的珍珠仓库；**key 是玩家 UUID**，是所有权限校验的根 |
| `StoredPearl` | 48-53 | 单条珍珠记录（无 stack 概念，就是一个坐标） |
| `WhitelistedPlayer` | 55-63 | 唯一有构造函数的类，供 JSON 反序列化 |

---

## 5. PearlManager — 核心类（905 行，分 6 组理解）

### 5.1 静态状态与内部记录

| 成员 | 行 | 作用 |
|---|---|---|
| `CLAIM_RELEASE_TIMEOUT_MS = 5_000` | 32 | 装载后等待珍珠消失的放权超时 |
| `pendingLookClick` | 34 | 单例：待执行的「瞄准→点击」任务 |
| `pendingClaimReleases` | 35-36 | `synchronizedList`：待放权的珍珠队列 |
| `record PendingLookClick` | 40-52 | 13 字段：目标坐标、剩余瞄准 tick、剩余锁定 tick、珍珠、请求者名、owner UUID、起点坐标、notifier |
| `record PendingClaimRelease` | 54-61 | owner UUID、pearlId、x、z、deadline |

### 5.2 数据 CRUD

| 方法 | 行 | 作用 |
|---|---|---|
| `findPearl(uuid, pearlId)` | 70 | 查单条 → `Optional<PlayerPearl>` |
| `listPearls(uuid)` | 81 | 列该玩家全部珍珠（返回副本） |
| `recordPearl(uuid, name, id, x, y, z)` | 88 | **写/覆盖**；首次写入时把 `defaultPearlId` 初始化为该 id |
| `removePearl(uuid, id)` | 102 | 公开删除入口，转发 `releaseStoredPearl` |
| `releaseStoredPearl(uuid, id)` | 106-119 | 静态私有：删记录；若删的是默认 id 则顺延到剩余第一个；仓库空则整个玩家条目移除 |
| `renamePearl(uuid, old, new)` | 121-142 | 改名；新 id 已存在则拒绝；若改的是默认 id 则同步默认 |
| `defaultPearlId(uuid)` | 144-167 | **优先级决策**：①配置默认且（有珍珠 或 `autodefault` 关）→ ②`autodefault` 开则返回**第一颗在场**的珍珠 → ③配置默认 → ④任意一个 |
| `setDefaultPearl(uuid, id)` | 169 | 静默设置（id 不存在则忽略） |
| `resolvePearlId(uuid, id)` | 177 | **大小写不敏感**地解析用户输入的 id |
| `nextAvailablePearlId(uuid, name)` | 605-628 | 生成 `Base` / `Base2` … `Base9999`；`defaultPearlId` 为 null 时退化为用玩家名 |
| `countPresentPearls(uuid)` | 872 | 该玩家**在场**珍珠数（用于「你还剩几颗」反馈） |
| `getUuidFromUsername(name)` | 893 | 反查 UUID（遍历内存 map，非外部 API） |

### 5.3 存在性判定

| 方法 | 行 | 作用 |
|---|---|---|
| `isPearlPresent(pearl)` | 186-197 | 实体缓存里找 `ENDER_PEARL` 且 `floor(x)==pearl.x && floor(z)==pearl.z`（**只比 XZ，不比 Y**） |
| `isWithinPresenceRange(pearl)` | 199-210 | 先卡 `temporaryRemovalRange`（默认 32 格）距离，避免把远处缓存珍珠当「在场」 |
| `hasPearlEntityAt(x, z)` | 530-538 | 静态简化版，不做距离检查，放权判定用 |

### 5.4 活板门查找（Stasis 定位）

| 方法 | 行 | 作用 |
|---|---|---|
| `findNearestTrapdoorAround(pearl, radius)` | 213-286 | 在 `(2r+1)³`（调用处 r=3 → 343 格）内三重循环：`ChunkCache.getChunkSection` → `stateId`（0 跳过）→ `BLOCK_DATA.getBlockDataFromBlockStateId` → **名称以 `_trapdoor` 结尾** → 取距离平方最小者；找不到返回 null |

`blockNameAt(x, y, z)`（670-681）是同款逻辑的字符串版，供 debug 使用。

### 5.5 装载主流程

| 方法 | 行 | 作用 |
|---|---|---|
| `loadPearl(pearl, requester, ownerUuid)` | 288-343 | **装载入口**。见下方时序 |
| `startLookThenClick(...)` | 345-362 | 把 `PendingLookClick` 装进静态槽位（Baritone future 完成回调里调用） |
| `cancelPendingLookClick()` | 364 | 机器人断开时清空 |
| `tickPendingLookClick()` | 372-417 | **每 tick 推进**：瞄准 → 校验可交互 → 发包 → 成功则 `onPearlLoaded` |
| `onPearlLoaded(...)` | 451-485 | 成功回调：通知 → 放权 → 丢珍珠/讨要 → 补货或回家 |
| `releaseClaimAfterLoad(uuid, pearl)` | 487-513 | 场上已无珍珠 → 立即 `removePearl`；否则入队等消失（5s deadline） |
| `findOwnerUuid(pearl)` | 515-528 | 按对象引用 `containsValue` 反查所属 UUID（`ownerUuid` 为 null 时的兜底） |
| `tickPendingClaimReleases()` | 419-449 | 每 tick 检查队列：珍珠已消失 → 放权；到期仍在 → 保留 claim 并记日志 |
| `cancelPendingClaimReleases()` | 368 | 断开时清空队列 |

**`loadPearl` 逐行拆解（288-343）**

```
289  pearl == null            → 直接返回
293  未连接 / 在队列中         → Discord「Bot is not online」+ 返回
300  hasActivePlayer()        → Discord「Player is controlling」+ 返回   ← 防止抢真人控制权
309  dropPearlAfterLoad       → ensurePearlsAvailable() 先确保手上有珍珠
314  current = 玩家当前 blockPos()   ← 记起点，用于回家
316  trapdoorPos = findNearestTrapdoorAround(pearl, 3)
320  找到  → target = 活板门坐标；info 打印
326  没找到  → target = 存储坐标本身（兜底），并 info 说明
334  debugClickTarget(...)      ← 仅 debug=true 时推送 Discord 调试卡片
335  GrimInteract.pathIntoReach(target).addExecutedListener(→ startLookThenClick)
339  Discord「Loading Pearl」通知（注意：先通知再等寻路完成）
```

**`tickPendingLookClick` 逐行拆解（372-417）**

```
374  pending == null                → 退出
377  lookAt(x, y, z)                 ← 每 tick 都在瞄准
378  lookTicksRemaining > 0         → 倒数并退出（初始 5 tick）
389  hasInteractableTarget          → 非空气且有交互碰撞盒；否则「No interactable block」中止
397  useItemOnIfLookingAt           → 射线命中才发包；成功 → onPearlLoaded 并清空
403  lookLockTicksRemaining <= 0    → 「Not looking at the trapdoor」中止（初始 20 tick）
411  否则锁定 tick -1 后重试
```

### 5.6 背包与珍珠流动

| 方法 | 行 | 作用 |
|---|---|---|
| `hasPearlsInHotbarSlot0()` | 684-700 | 索引 **36** = 热键 0 格 |
| `hasAnyEnderPearls()` | 702 | 薄封装 |
| `countEnderPearlsInInventory()` | 706-724 | 扫索引 `[9, min(size,46))` 累加 `ENDER_PEARL` 数量 |
| `ensurePearlsAvailable()` | 727-751 | 若 0 格无珍珠 → `findEnderPearlInInventory()` → `INVENTORY.submit(MoveToHotbarSlot(slot, SLOT_1))`，priority 1000 |
| `findEnderPearlInInventory()` | 845-864 | 返回第一颗珍珠所在索引 |
| `isEnderPearl(Object)` | 803-842 | 同时兼容 `ItemStack` 与 `Container`；匹配 `ENDER_PEARL`/`ender_pearl`/contains |
| `dropPearlFromHotbarSlot0()` | 754-764 | `DropItem(36, DROP_FROM_SELECTED)`（丢整叠，不是丢一个） |
| `sendOutOfPearlsMessage(name)` | 767-775 | 私语「I'm all out of pearls, can you give me some?」 |
| `handlePearlDropAfterLoad(name)` | 783-801 | 装载后统一入口：有珍珠就丢一颗，没有就讨要 |

> ⚠️ `isEnderPearl` 的 826-827 每次调用都 `info()` 打物品名和 id，属于日志噪音。

### 5.7 展示文本

| 方法 | 行 | 作用 |
|---|---|---|
| `pearlsList(uuid)` | 540-558 | 私语用逗号列表，**未检出的珍珠 id 后加 `*`** |
| `pearlsListWithCoords(uuid)` | 560-577 | Discord 用，受 `CONFIG.discord.reportCoords` 控制是否显示坐标 |
| `pearlsListWithCoordsAllPlayers()` | 579-603 | `pp list` 用，遍历所有玩家 |
| `info(msg)` | 630 | 转发 `LOG.info` |
| `debug(msg)` | 634 | 受 `PLUGIN_CONFIG.debug` 门控 |
| `debugClickTarget(...)` | 641-668 | debug 时输出「存的坐标 vs 点的坐标 vs 方块名」并推 Discord |

---

## 6. AutoDetectModule — 模块① 自动登记（782 行）

### 6.1 生命周期与事件

| 成员 | 行 | 作用 |
|---|---|---|
| `STABLE_LOCATION_DURATION_MS = 3_000` | 22 | 静止判定时长 |
| `STORED_PEARL_REMOVAL_GRACE_MS = 60_000` | 23 | 断线/启用后的注销宽限期 |
| `POSITION_HISTORY_LIMIT = 8` | 24 | 位置历史环形长度 |
| `trackedPearls` | 26 | `entityId → TrackedPearl` |
| `acknowledgedColumns` | 28 | 已成功登记的列 |
| `pendingReconnectGrace` | 29 | 断开后置位，下 tick 续宽限期 |
| `suppressStoredPearlRemovalUntil` | 30 | 绝对时间戳形式的宽限期 |
| `enabledSetting()` | 33 | 跟随 `autoDetect.enabled` |
| `registerEvents()` | 38-51 | `ClientBotTick` → `scanForPearls()`；`Stopped` → 清表 + 置重连宽限 |
| `onEnable()` | 54-57 | 延长宽限期 + `markExistingPearls()` |
| `onDisable()` | 60-63 | 清表 + 清宽限 |

### 6.2 扫描管线

| 方法 | 行 | 作用 |
|---|---|---|
| `scanForPearls()` | 103-168 | **每 tick 主流程**，五步：① 续宽限期 ② 清理消失的实体 ③ 新建/更新跟踪 ④ `attemptAutoRegistration` ⑤ `checkStoredPearlsForMissingEntities` |
| `markExistingPearls()` | 73-101 | 启用模块时对**当前已存在**的珍珠建跟踪（避免重启后全部重登记） |
| `handlePearlRemoval(tracked)` | 170-180 | 实体消失 → 日志 + 清 `acknowledgedColumns` + 委托 temp 注销 |
| `blockPositionOf(entity)` | 481-487 | `floor(x), **round(y)**, floor(z)`；Y 用 round 是历史 bug 修复 |

### 6.3 归属识别（三级回退）

| 方法 | 行 | 作用 |
|---|---|---|
| `resolveOwnerInfo(pearl, entities)` | 398-404 | 先试抛掷者 ID；失败且 `distanceCheck` 开 → 试最近玩家 |
| `resolveOwnerFromProjectileOwner` | 406-425 | `ProjectileData.getOwnerId()` → 实体表取 UUID → `TabListCache` 取名字。**2b2t 可用** |
| `resolveOwnerFromClosestPlayer` | 427-469 | 遍历 `EntityType.PLAYER`，**排除机器人自己**，取距离平方 `< 2.0`（约 1.41 格）最近者 |
| `resolveOwnerName(uuid)` | 471-479 | `CACHE.getTabListCache().get(uuid) → PlayerListEntry.getName()` |
| `determineBotName()` / `determineBotUuid()` | 331-363 | 读 `CACHE.getProfileCache().getProfile()` |

**OwnerInfo 合并工具**

| 方法 | 行 | 作用 |
|---|---|---|
| `mergeOwnerInfo(preferred, fallback)` | 373-383 | UUID 优先取 preferred，名字优先取有值的那个 |
| `selectOwner(a, b, fallback)` | 369-371 | `merge(a, merge(b, fallback))` |
| `isDifferentOwner(thrower, stored)` | 385-396 | UUID 都存在则比 UUID，否则比名字（忽略大小写），都没有则返回 false（视为同主） |

### 6.4 登记决策

| 方法 | 行 | 作用 |
|---|---|---|
| `attemptAutoRegistration(now)` | 209-284 | 见下方决策树 |
| `findStoredPearlByColumn(x, z)` | 568-574 | 全表扫描 `(x,z)` 命中的珍珠，**取 y 最大**者（stasis 竖井取最高点） |
| `nextAvailablePearlId` 调用 | 259 | 委托 `PearlManager` |
| `sendRegistrationWhisper(name, id)` | 286-304 | 私语 `/w <bot> <loadCommand> <pearlId>`，失败降级为「Pearl Registered as X.」 |
| `sendRegistrationNotification(...)` | 306-319 | Discord 卡片，坐标用 `\|\|...\|\|` 折叠 |
| `sendForeignOwnershipWhisper(...)` | 321-329 | 私语「Pearl spot already belongs to X.」 |
| `sendRemovalWarning(...)` / `sendUnregisterWhisper(...)` | 583-607 | 注销时的 Discord 警告 + 私语通知 |

**`attemptAutoRegistration` 决策树（209-284）**

```
遍历 trackedPearls
├─ 211 归属无名字 或 uuid == null → 记一次日志「Waiting for thrower's name/uuid」，跳过
├─ 221 !isStable(now)            → 跳过（还在飞 / 未达静止或弹跳特征）
├─ 226 该列已有记录（existingStored.isPresent）
│    ├─ 229 是异主 且 未通知过 → sendForeignOwnershipWhisper + markConflictNotified
│    ├─ 234 继承已有 pearlId 与 owner
│    ├─ 239 同主 && 有 id && 有 uuid → recordPearl() **更新坐标**（珍珠可能被踢过）
│    └─ 251 acknowledgedColumns.add()
├─ 257 新列
│    ├─ 258 无 id → nextAvailablePearlId()；返回 null 则跳过
│    ├─ 266 info 日志「Registering pearl ...」
│    ├─ 275 recordPearl()
│    ├─ 278 sendRegistrationWhisper() → markRegistrationNotified
│    ├─ 281 acknowledgedColumns.add()
│    └─ 282 sendRegistrationNotification() → Discord
```

### 6.5 temp 模式注销

| 方法 | 行 | 作用 |
|---|---|---|
| `isTemporaryModeEnabled()` | 65 | 读 `autoDetect.temporaryMode` |
| `onTemporaryModeToggle(enabled)` | 69 | 只打日志 |
| `handleTemporaryRemoval(tracked)` | 182-207 | temp 关闭直接返回；未连接/在队列返回；列上无记录返回；缺 id/owner/uuid 返回；超出 `temporaryRemovalRange` 返回；**否则警告 + 私语 + `removePearl`** |
| `checkStoredPearlsForMissingEntities(entities)` | 493-554 | **反向**扫描：把配置里所有珍珠与实体缓存比对，宽限期外且 32 格内且找不到实体 → 注销。逐项 `new HashMap<>(...)` 拷贝以避免 ConcurrentModificationException |
| `extendStoredPearlRemovalGracePeriod()` | 489-491 | `now + 60s` |
| `withinRemovalRange(pos)` | 556-566 | 欧氏距离 ≤ `temporaryRemovalRange` |

### 6.6 内部类型

| 类型 | 行 | 说明 |
|---|---|---|
| `record BlockPosition(x,y,z)` | 609-613 | `sameColumn()` 只比 XZ |
| `record Column(x, z)` | 615 | 列标识 |
| `record OwnerInfo(uuid, name)` | 617-628 | `hasName()` / `describe()`（输出 `名 (UUID)`） |
| `record StoredPearlEntry(ownerUuid, ownerName, pearl)` | 630-634 | 扁平化查询结果 |
| `class TrackedPearl` | 636-780 | 见下 |

**`TrackedPearl` 内部状态（636-780）**

| 成员 | 行 | 作用 |
|---|---|---|
| `position` / `history` / `lastMovedAt` / `moved` | 638, 644-645, 649 | 当前位置 + 最近 8 点历史 + 最后移动时刻 |
| `owner` / `pearlId` | 639-640 | 已解析归属与分配 id |
| `waitingForNameLogged` / `conflictNotified` / `registrationNotified` | 641, 648, 644 | 三个「只提示一次」标志 |
| `updatePosition(newPos, ts)` | 659-669 | 变了才更新时间戳并压入历史（超 8 丢最旧） |
| `isStable(now)` | 731-733 | `now - lastMovedAt >= 3000` **或** `hasBouncePattern()` |
| `registrationPosition()` | 735-740 | 弹跳模式取两格中较高者，否则当前位置 |
| `hasBouncePattern()` | 754-772 | **弹跳特征**：历史 ≥5 点；`latest` 与 `previous` 同列且不同；且 `latest == third == fifth`、`previous == fourth`（A-B-A-B-A 交替） |
| `highestRecentBouncePosition()` | 774-779 | 比较末两点 Y，取高者 |
| `blockX/blockY/blockZ` | 742-751 | 快捷访问 |

> 设计意图：stasis chamber 里珍珠会在两个方块间来回弹（尚未真正 stasis 定住），因此「持续静止」和「两格弹跳」都必须视为「已就位」。

---

## 7. AutoLoadModule — 模块② 私语指令（178 行）

| 成员 | 行 | 作用 |
|---|---|---|
| `pearlManager` | 15 | `new PearlManager(this)` |
| `enabledSetting()` | 18-20 | 跟随 `autoLoad.enabled` |
| `registerEvents()` | 23-27 | 只订阅 `WhisperChatEvent` |
| `onWhisper(event)` | 29-177 | **全部逻辑在单方法内**，顺序如下 |

**`onWhisper` 解析顺序（29-177）**

```
30  !enabled 或 event.outgoing()  → return       ← 只处理入站私语
32-35 rawMessage(保留大小写) / msg(小写) / lowerParts / parts 四份切分
40-41 loadCommand = autoLoad.loadCommand，空则 "load"，比较用小写

44-49 【白名单闸门】msg.startsWith(loadCommand) 且 whitelistEnabled 且 uuid 不在 whitelist
        → 静默 return（不提示，防探测）

51-58 "pearls" → 回复 pearlsList(uuid)（带 * 标记）

60-78 "default" 首词
        ├─ 无珍珠记录 → 仅日志
        ├─ 参数 <2 → 提示「Specify a pearl ID」
        ├─ resolvePearlId 失败 → 「Pearl not found.」
        └─ setDefaultPearl + 「Default pearl set to X.」

80-109 "rename" 首词
        ├─ 无记录 / 参数 <3 → 「Usage: rename <oldId> <newId>」
        ├─ oldId 解析失败 → 「Pearl not found.」
        ├─ newId 已存在（忽略大小写）→ 「A pearl with that id already exists.」
        └─ renamePearl → 成功/失败两条反馈

111  !msg.startsWith(loadCommand) → return       ← 以上都不匹配才算 load

113-117 uuid 无记录 → 仅日志「No pearls assigned to X」

119-129 参数个数限制
        ├─ !allowNoiseAfterPearl(strict) 且 >2 段 → 日志拒绝
        └─ allowNoise 且 >3 段              → 日志拒绝

131-143 确定目标珍珠
        ├─ 1 段（纯 "load"）→ defaultPearlId(uuid)
        ├─ 2 段 → resolvePearlId(uuid, parts[1])；解析失败且允许噪声 → 退回 default
        └─ 否则 → null

145-149 requestedPearl 为 null 或 不在本人仓库 → 日志「Unauthorized load ...」+ 私语「No authorized pearls found.」

151  var pearl = playerEntry.pearls.get(requestedPearl)

153-157 Discord「Recieved Whisper」卡片（含发送者与珍珠 id）

160-167 反馈文案：countPresentPearls(uuid) - 1；若恰为 1 则换成「Don't forget to drop down a new pearl, this is your last one!」

169-173 私语二选一
        ├─ 未检出珍珠 → 「No pearl detected. Attempting to load anyways.」
        └─ 检出       → 「Loading pearl X... You have N pearls left.」

175  pearlManager.loadPearl(pearl, name, uuid)    ← 交棒
```

> `allowNoiseAfterPearl=true` 时形如 `load Base abc`（3 段）是合法的，末段被丢弃，作用是规避服务器反刷屏。`pp strict on` 关闭。

---

## 8. GrimInteract — 底层交互工具（97 行，package-private final）

| 方法 | 行 | 作用 |
|---|---|---|
| `LOOK_TICKS = 5` | 26 | 点击前固定瞄准 tick 数 |
| `LOOK_LOCK_TIMEOUT_TICKS = 20` | 27 | 射线始终命不中时的最大重试 tick |
| `pathIntoReach(x,y,z)` | 31-34 | `BARITONE.pathTo(new GoalNear(x,y,z, (reach-1)²))`，`rangeSq` 下限 2 |
| `lookAt(x,y,z)` | 36-45 | `RotationHelper.rotationTo(World.blockInteractionCenter(...))` → `INPUTS.submit`，**priority = `Baritone.getPriority() + 1`**（压过寻路旋转） |
| `hasInteractableTarget(x,y,z)` | 47-54 | 非空气 && `BLOCK_DATA.getInteractionBoxesFromBlockStateId()` 非空 |
| `isLookingAt(x,y,z)` | 56-59 | 射线命中且三坐标完全相等（**当前无调用方**，`useItemOnIfLookingAt` 内联了同样判断） |
| `useItemOnIfLookingAt(x,y,z)` | 65-90 | **安全发包核心**：射线未命中目标 → 拒绝；命中才在 `startPredicting()` 作用域内发 `ServerboundUseItemOnPacket(x,y,z,face,MAIN_HAND,0,0,0,false,false,prediction.currentSequence())`，随后异步发 `ServerboundSwingPacket` |
| `debug(msg)` | 92-96 | 受 `debug` 门控 |

> **反作弊兼容设计**：只发 `UseItemOn`、**永不**发 `UseItem`。后者在裸手状态会触发投掷 —— 2.2.x 之前的 bug 就是这样把珍珠甩出去的（见 commit `d5de468` "Look at blocks before UseItemOn so Grim loads cannot throw pearls"）。`prediction.currentSequence()` 是 Grim 区块预测序列号，用对才不会被判异常。

---

## 9. PearlRestockModule — 模块③ 自动补货（292 行）

| 成员 | 行 | 作用 |
|---|---|---|
| `CONTAINER_OPEN_TIMEOUT_MS = 5_000` | 28 | 等待容器打开上限 |
| `INVENTORY_PRIORITY = 1000` | 29 | 与珍珠挪动一致 |
| `enum State` | 31-38 | `IDLE / PENDING / PATHING / LOOKING / WAITING_OPEN / WITHDRAWING` |
| `enabledSetting()` | 49-51 | 跟随 `restock.enabled` |

| 方法 | 行 | 作用 |
|---|---|---|
| `isBusy()` | 66 | `state != IDLE` |
| `requestAfterLoad(startPos)` | 70-94 | **外部唯一入口**（由 `PearlManager:473` 调用）。注释说明为何进 `PENDING` 而不直接 `LOOKING`：该回调在 Baritone executed listener 里执行，同一 tick 内 `InteractWithProcess.onLostControl()` 会取消新的点击 |
| `onTick(event)` | 96-113 | 非 IDLE 才动；`!canOperate()` → `abort("Bot is not ready")`；否则 switch 推进 |
| `startPathing()` | 115-126 | `GrimInteract.pathIntoReach(restock.x,y,z)` → `PATHING` |
| `tickPathing()` | 128-144 | future 完成且 accepted → 初始化两个 tick 计数 → `LOOKING`；否则「Could not reach」 |
| `tickLooking()` | 146-171 | 与 `PearlManager` 同一套：瞄准 5 tick → `hasInteractableTarget` → `useItemOnIfLookingAt`；成功则设 `waitOpenUntilMs` 进 `WAITING_OPEN`；20 tick 未命中 → 失败 |
| `tickWaitingOpen()` | 173-200 | `openContainerId() != 0` 时用 `InventoryActionMacros.withdraw(containerId, ::isEnderPearlStack, 1)` 生成动作，**追加 `CloseContainer`**，一起 `INVENTORY.submit`；无珍珠或 5s 超时 → 失败 |
| `tickWithdrawing()` | 202-215 | future 完成 → 校验背包真有珍珠 → 成功 `finish` |
| `abort(reason)` | 217-222 | 非 IDLE 才 finish 失败 |
| `finish(title, desc, success)` | 224-245 | 记下 `startPos` → `reset()` → 通知 → `returnToStartPos` 开启则回家 |
| `reset()` | 247-255 | 全部字段归零 |
| `canOperate()` | 257-260 | 比 `loadPearl` 多一条 `!hasActivePlayer()` |
| `openContainerId()` | 262-267 | `CACHE.getPlayerCache().getInventoryCache().getOpenContainerId()`（0 = 未打开） |
| `isEnderPearlStack(stack)` | 269-273 | 严格 `stack.getId() == ItemRegistry.ENDER_PEARL.id()`，排除 `Container.EMPTY_STACK` |
| `hasEnderPearlsInInventory()` | 275-291 | 扫 `[9, min(size,46))` |

---

## 10. PearlPlusCommand — 管理命令（439 行）

- `commandUsage()`（30-58）：定义名称 `pearlplus`、分类 `MODULE`、别名 `pp`
- `register()`（61-392）：`.requires(Command::validateAccountOwner)` —— **所有子命令都需要账户所有者身份**，这是管理面唯一的鉴权

| 命令 | 行 | 写入的配置字段 | 副作用 |
|---|---|---|---|
| `<toggle>` | 65-72 | `autoLoad.enabled` | `AutoLoadModule.syncEnabledFromConfig()` |
| `list` | 74-80 | — | 读，Discord 输出全部玩家珍珠 |
| `list clear` | 81-91 | **清空 `players`** | 不可撤销 |
| `list <playerName>` | 92-104 | — | 读 |
| `add <name> <id> <x> <y> <z>` | 106-130 | 新增记录 | `recordPearl` |
| `del <name> <id>` | 132-153 | 删记录 | `removePearl` |
| `load <name> <id>` | 155-182 | — | `loadPearl(pearl, null, uuid)`，`requesterName` 传 null（因此**不会**丢珍珠、**不会**私语反馈） |
| `defaultpearlid <word\|none>` | 184-195 | `defaultPearlId` | `none` → 置 null，改用玩家名 |
| `loadcommand <word>` | 198-208 | `autoLoad.loadCommand` | 空串拒绝 |
| `strict <toggle>` | 210-217 | `autoLoad.allowNoiseAfterPearl = !strict` | **取反赋值** |
| `returnpos <toggle>` | 219-226 | `autoLoad.returnToStartPos` | |
| `autodetect <toggle>` | 228-242 | `autoDetect.enabled` | 开时额外 `markExistingPearls()` |
| `autodetect temp <toggle>` | 243-254 | `autoDetect.temporaryMode` | `onTemporaryModeToggle` |
| `distancecheck <toggle>` | 256-264 | `autoDetect.distanceCheck` | |
| `autodefault <toggle>` | 266-273 | `autoLoad.autoDefaultToPresent` | |
| `whitelist <toggle>` | 276-282 | `autoLoad.whitelistEnabled` | |
| `whitelist add/remove/list/clear` | 283-339 | `whitelist` map | |
| `droppearlafterload <toggle>` | 341-348 | `autoLoad.dropPearlAfterLoad` | |
| `set-restock-container clear` | 351-356 | `restock.enabled = false` | |
| `set-restock-container <x> <y> <z>` | 357-380 | `restock.x/y/z` + `enabled = true` | 若区块已加载则显示方块名，并用 `looksLikeStorage` 警告「可能不是储物方块」 |
| `debug <toggle>` | 382-389 | `debug` | |
| `defaultEmbed(...)` | 394-412 | — | `pp` 无参时打印全部开关状态 |

辅助：`restockContainerField()`（414）、`looksLikeStorage(name)`（421-432，匹配 chest/barrel/shulker_box/hopper/dispenser/dropper）、`resolveUuidByUsername(name)`（434-438 → `PlayerListsManager.getProfileFromUsername`，**这是插件唯一的外部 API 调用**）。

---

## 11. 事件订阅全表

| 事件 | 订阅者 | 行 | 动作 |
|---|---|---|---|
| `ClientBotTick` | `PearlPlusPlugin` | Plugin:40 | `tickPendingLookClick()` + `tickPendingClaimReleases()` |
| `ClientBotTick.Stopped` | `PearlPlusPlugin` | Plugin:44 | `cancelPendingLookClick()` + `cancelPendingClaimReleases()` |
| `ClientBotTick` | `AutoDetectModule` | Detect:40 | `scanForPearls()` |
| `ClientBotTick.Stopped` | `AutoDetectModule` | Detect:46 | `trackedPearls.clear()` + `pendingReconnectGrace = true` |
| `ClientBotTick` | `PearlRestockModule` | Restock:56 | `onTick()` 状态机 |
| `ClientBotTick.Stopped` | `PearlRestockModule` | Restock:57 | `abort("Bot stopped")` |
| `WhisperChatEvent` | `AutoLoadModule` | Load:25 | `onWhisper()` |

## 12. 关键常量汇总

| 常量 | 值 | 位置 | 作用 |
|---|---|---|---|
| `CLAIM_RELEASE_TIMEOUT_MS` | 5000 | PearlManager:32 | 装完后等珍珠消失的放权超时 |
| `STABLE_LOCATION_DURATION_MS` | 3000 | Detect:22 | 静止判定 |
| `STORED_PEARL_REMOVAL_GRACE_MS` | 60000 | Detect:23 | 断线/启用后注销宽限 |
| `POSITION_HISTORY_LIMIT` | 8 | Detect:24 | 弹跳检测历史长度（实际用 5） |
| `LOOK_TICKS` | 5 | GrimInteract:26 | 点击前瞄准 tick |
| `LOOK_LOCK_TIMEOUT_TICKS` | 20 | GrimInteract:27 | 射线重试上限 |
| `CONTAINER_OPEN_TIMEOUT_MS` | 5000 | Restock:28 | 容器打开等待上限 |
| `INVENTORY_PRIORITY` | 1000 | Restock:29 | 背包动作优先级 |
| 优先级 1000（内联） | — | PearlManager:743, 759 | 挪珍珠/丢珍珠 |
| 优先级 `Baritone+1`（内联） | — | GrimInteract:43 | 瞄准旋转压过寻路 |
| `defaultPearlId` | `"Base"` | Config:12 | 新珍珠 id 基数 |
| `temporaryRemovalRange` | 32 | Config:32 | 「在场」判定与 temp 注销的距离上限 |
| id 后缀上限 | 10000 | PearlManager:620 | `Base2`…`Base9999` |

## 13. 配置项全表

| JSON 路径 | 默认 | 管理命令 | 影响点 |
|---|---|---|---|
| `autoLoad.enabled` | `true` | `pp <on/off>` | `AutoLoadModule` 开关 + 全部私语处理 |
| `autoLoad.allowNoiseAfterPearl` | `true` | `pp strict <on/off>`(取反) | 允许 `load <id> <噪声>` 3 段式 |
| `autoLoad.returnToStartPos` | `true` | `pp returnpos <on/off>` | 装载/补货后 `BARITONE.pathTo(startPos)` |
| `autoLoad.autoDefaultToPresent` | `true` | `pp autodefault <on/off>` | `defaultPearlId()` 是否优先返回「在场」珍珠 |
| `autoLoad.whitelistEnabled` | `false` | `pp whitelist <on/off>` | 是否只放行白名单用户的 load |
| `autoLoad.dropPearlAfterLoad` | `true` | `pp droppearlafterload <on/off>` | 装完丢珍珠 / 没珍珠时讨要 |
| `autoLoad.loadCommand` | `"load"` | `pp loadcommand <word>` | 私语触发词（`startsWith` 匹配） |
| `autoDetect.enabled` | `true` | `pp autodetect <on/off>` | 关闭后不再扫描/登记 |
| `autoDetect.temporaryMode` | `false` | `pp autodetect temp <on/off>` | 珍珠消失时自动注销记录 |
| `autoDetect.distanceCheck` | `false` | `pp distancecheck <on/off>` | 归属识别降级到「最近玩家」 |
| `autoDetect.temporaryRemovalRange` | `32` | 无（手改 JSON） | 「在场」判定 + temp 注销范围 |
| `restock.enabled` | `false` | `pp set-restock-container <x y z>` / `clear` | 补货模块开关 |
| `restock.x/y/z` | `0` | 同上 | 容器坐标 |
| `defaultPearlId` | `"Base"` | `pp defaultpearlid <word\|none>` | 新珍珠 id 基数；`none` → 用玩家名 |
| `debug` | `false` | `pp debug <on/off>` | 额外日志 + Discord 调试卡片 |
| `players` | `{}` | `pp add/del/list/list clear` | 珍珠仓库 |
| `whitelist` | `{}` | `pp whitelist add/remove/list/clear` | 白名单 |

外部依赖：`CONFIG.discord.reportCoords`（Zenith 全局，非本插件）控制 `pearlsListWithCoords*` 是否输出坐标。

## 14. 构建期文件

| 文件 | 作用 |
|---|---|
| `build.gradle.kts` | `zenithproxy.plugin.dev` 插件模板；`repositories` 指向 `maven.2b2t.vc`；`graalvmNative` 用 `-H:Preserve=package=dev.zenith.pearlplus.*`（否则 native 镜像会裁掉插件类）；mainClass 为 `com.zenith.Proxy` |
| `settings.gradle.kts` | `pluginManagement` 仓库 + `rootProject.name = PearlPlus` |
| `gradle.properties` | `plugin_version=2.2.1`、`plugin_name=PearlPlus`、`plugin_id=pearlplus`、`mc=1.21.4`、`maven_group=dev.zenith.pearlplus` |
| `templates/.../BuildConstants.java` | 占位符模板，构建时生成到 `build/generated/sources/templates/` |
| `PearlPlusReflectionFeature.java` | GraalVM `Feature#beforeAnalysis`：枚举 `MAVEN_GROUP` 包下所有 `.class`，`registerAsUsed` + 注册构造/方法/字段/record accessor 反射元数据。仅构建期执行 |
| `native-image.properties` | `--features=graalvm.dev.zenith.pearlplus.PearlPlusReflectionFeature` |

---

## 15. 三条主时序（速记）

**A. 登记**
```
玩家抛珍珠 → 服务端下发展示实体
  → [每 tick] AutoDetectModule.scanForPearls
      → 建 TrackedPearl，解析 owner（ID → tab；可选最近玩家）
      → isStable：静止 3s  或  A-B-A-B-A 弹跳
      → 该列已有记录？
           是 → 同主：更新坐标；异主：私语拒绝
           否 → nextAvailablePearlId → recordPearl
                 → 私语 owner「/w bot load Base」
                 → Discord 卡片（坐标折叠）
```

**B. 装载**
```
owner 私语 "load Base"
  → AutoLoadModule.onWhisper（白名单/参数数/id 归属三重校验）
      → 私语「Loading pearl Base... You have N pearls left.」
      → PearlManager.loadPearl
          ├─ 闸门：在线 / 不在队列 / 无真人接管
          ├─ ensurePearlsAvailable（挪珍珠到热键 0）
          ├─ 记 startPos
          ├─ findNearestTrapdoorAround(3) → 目标方块
          └─ Baritone GoalNear(reach-1)²
                └─ executedListener → startLookThenClick
                      └─ [每 tick] tickPendingLookClick
                          ├─ lookAt ×5 tick（priority = Baritone+1）
                          ├─ hasInteractableTarget？
                          ├─ useItemOnIfLookingAt → UseItemOn + Swing
                          └─ onPearlLoaded
                              ├─ Discord「Pearl Loaded!」
                              ├─ releaseClaimAfterLoad（无珍珠→立即删；否则 5s 内轮询）
                              ├─ DropItem(36) 丢珍珠 ／ 没货则私语讨要
                              └─ restock 开且背包空 → PearlRestockModule
                                 否则 returnToStartPos 开 → 回家
```

**C. 补货**
```
requestAfterLoad(startPos) → PENDING
  → PATHING：GoalNear 走到容器
  → LOOKING：瞄准 5 tick → UseItemOn（带容器）
  → WAITING_OPEN：轮询 openContainerId()（5s）
  → WITHDRAWING：withdraw(1 叠 END_PEARL) + CloseContainer
  → finish → 若 returnToStartPos 则回家
```

---

## 16. 已知坑位 / 维护提示

| 位置 | 现象 |
|---|---|
| `AutoLoadModule:44,111` | `startsWith(loadCommand)` 过宽，`loader`、`loadxyz` 也会触发装载 |
| `AutoLoadModule:126` | 允许噪声时 token 上限为 3（`load <id> <噪声>`），比 `strict` 模式的 2 多一个，这是有意为之 |
| `PearlManager:826-827` | `isEnderPearl` 每次都 `info()` 打印物品名+id，日志噪音 |
| `AutoDetectModule:427-469` | `distanceCheck` 归属可被「站得近」冒名劫持 |
| `AutoDetectModule:406-425` | 完全信任 `ProjectileData.getOwnerId()`，可被恶意客户端伪造 |
| `PearlManager:560-603` | 坐标会进 Discord；`CONFIG.discord.reportCoords` 开启且频道可见时等于公开所有 stasis 位置 |
| `AutoDetectModule:493-554` | temp 模式在 60s 宽限期外遇到区块卸载会静默注销（数据丢失），README 已标为「may be buggy」 |
| `GrimInteract:56-59` | `isLookingAt` 是死代码，逻辑已内联进 `useItemOnIfLookingAt` |
| `PearlManager:754-764` | `DropItem(36, DROP_FROM_SELECTED)` 丢的是**整叠**，不是一颗；`onPearlLoaded:470` 的 `remaining = pearlCount - 1` 与实际丢叠数不一致（仅影响补货触发阈值判断） |
| `PearlPlusCommand:213` | `strict` 命令写入的是 `allowNoiseAfterPearl = !strict`，读配置时容易看反 |
