> 阶段记录：描述当时的实现与验证，当前行为以 [README](../README.md) 和 [整页识别说明](page-recognition.md) 为准。

# JustLedger 核心机制审查

审查日期：2026-10-23。对象：当前工作区，基于 AutoAccounting 二开后的 JustLedger。

结论：具备自动采集、规则识别、OCR、账单分组及退款类型等基础，但当前不能承诺稳定、准确的全自动记账。优先解决错误合并、退款语义、OCR 漏触发及采集恢复，再扩大银行支持范围。

本次为源码审查和调用链核对，未修改业务逻辑，未运行 Android 构建、设备测试或真实支付测试。当前环境缺少 Android SDK，前次 Gradle 下载遇到 DNS 失败。下文“确定”表示源码路径确定，示例为构造场景推演，不代表已经发生过用户账单损失。真实识别率、时延、机型和微信/支付宝版本兼容性仍需实测。

## 1. 获取渠道及有效性

数据大体经由 `采集 → 本机 js/analysis → 原始数据存储 → JS 规则/可选 AI → 账单入库 → 转账识别/去重 → 分类 → 待确认或自动保存`。本地 HTTP 地址为 `127.0.0.1:52045`。OCR 文本识别与 AI 财务分析是不同能力；此前去掉财务分析页面和路由，不等于去掉付款识别。

| 渠道 | 实现与覆盖 | 生效条件和边界 | 评价 |
| --- | --- | --- | --- |
| Android 通知监听 | 微信、支付宝、银行及其他白名单 App 的通知标题/正文 | 通知访问权限；App 实际发出带交易信息的通知；关键词通过；存在匹配规则或开启 AI | 可作为非 Root 主通道，但存在重绑和文本读取问题；通知未发出/金额被隐藏时无法凭空恢复 |
| 短信广播 | 银行扣款、入账等短信；拼接分段短信后识别 | RECEIVE_SMS 权限；确实收到短信；模板有规则覆盖 | 可补充银行卡数据，不等于所有消费都有短信；缺少可靠异步持久化交接 |
| 系统通知 Hook | Android 通知服务内部方法拦截 | Xposed 激活及对应作用域；系统内部方法兼容 | 是通知的另一采集实现，不是新的银行数据接口；也有按文本去重的局限 |
| 微信 Hook | 消息数据库 message/AppMessage、转账、红包、支付工具、WebView 等 | Xposed/LSPatch 对应环境、宿主类/方法兼容和消息规则 | 能取得比通知更丰富的数据；不能据此保证最新版微信可用 |
| 支付宝 Hook | SyncMessage.getData/toString、WebView 等 | 注入成功、宿主内部接口兼容和规则覆盖 | 有实现；当前支付宝版本需真机验证 |
| 短信 Hook | 短信 Intent 拦截后上报 DATA | Hook 环境和系统短信路径兼容 | 与广播/短信通知存在同一事件被多次采集的可能 |
| 本地 OCR | 无障碍截图，PP-OCRv5 Mobile / NCNN，CPU 推理，短边 720 | 截图路径要求 Android 11/API 30+、无障碍可用、页面可截图、规则可解析 | 适合作为补充；当前自动触发存在确定逻辑问题，不能当作付款全覆盖能力 |
| 可选 AI 图像识别 | 截图编码后发往配置的 AI 服务，跳过本地文字识别 | AI 配置和开关可用、网络和图像协议兼容 | 可选，不应成为基础记账的强依赖；与 AI 消费建议无关 |

默认白名单列有招商、工行、建行、农行、中行、交行、邮储、中信、民生、光大、兴业、浦发等银行相关 App。**白名单只是允许采集，不是已支持银行清单**，包名和实际安装包也需核对。源码中没有各银行个人流水统一直连接口。

规则引擎按 App + DATA/NOTICE/OCR + 启用状态加载用户/系统规则。仓库 assets 中没有完整的银行 `rules.json` 规则包，默认 `JS_COMMON` 为空；系统规则通过 `RuleAPI` 下载，再写数据库。由此不能只凭源码列出“哪些银行当前识别准确”。新安装无规则且 AI 默认关闭时，不具备开箱即用的自动识别保障。二开应提供自己可维护、可离线使用的规则包及覆盖清单。

证据：`NotificationService.kt:45`、`SmsReceiver.kt:45`、`XposedModule.kt:42`、`WechatHooker.kt:61`、`DatabaseHooker.kt:63`、`MessageBoxHooker.kt:26`、`OcrTools.kt:48`、`OcrProcessor.kt:58`、`RuleGenerator.kt:63`、`RuleUpdateHelper.kt:123`、`DefaultData.kt:644`。文末给出文件路径索引。

Android 截图接口从 API 30 提供，受保护窗口可以导致截图失败；这是产品需要明确展示的能力边界，不能承诺所有银行页面都可识别。参见 [Android AccessibilityService 文档](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)。

## 2. 去重与退款的实际行为

现有去重有两层：通知/Hook/服务端的短期内存内容缓存，以及数据库账单分组。分组保留子账单，主列表与收支统计仅使用 `groupId = -1` 的主账单，这个基础设计可以继续使用。

账单候选条件是：同金额、同类型、时间在 ±300 秒内（默认）、尚未作为子账单；按时间从早到晚寻找第一个匹配。随后依次判断：时间完全相同直接合并；双方 AI 生成不合并；非空渠道完全相同不合并；其他情况合并。**实际代码没有比较规则名、订单号、币种、账本、商户或银行卡身份。**

| 构造场景 | 当前行为/风险 | 应有行为 |
| --- | --- | --- |
| 微信支付 100 元，银行卡扣款 100 元，1 分钟内出现 | 若识别为同类型且渠道不同，通常能合并；双方 AI 且时间不同则不合并 | 同一交易保留一笔支出，记录两个来源及实际付款卡 |
| 微信买 A 商品 100 元，1 分钟后另一张卡买 B 商品 100 元 | 满足候选条件时可能直接合并，与商户和卡无关 | 两笔独立支出 |
| 同一渠道两笔同额交易，时间被规则解析成同一时间 | “时间相同”分支优先，可能误合并 | 时间相同不能单独证明重复 |
| 同一微信付款的通知和截图 | 取决于渠道/时间/AI标识；可能漏去重 | 用订单身份或多项强证据判断 |
| 银行扣款通知比微信晚 10 分钟 | 如果解析出的交易时间也相差超过窗口，会漏合并；如果都提取到相同原交易时间则可能成功 | 区分交易时间和到达时间，支持延迟补充 |
| 微信退款通知 + 银行退款到账，两者都为 IncomeRefund | 只有同额、窗口和渠道等条件满足时才可能合并 | 同一退款事件可跨渠道、跨到达时间归并 |
| 微信侧识别为 IncomeRefund，银行侧识别为 Income | 不进入同类型去重，可能形成一笔退款加一笔收入 | 统一退款事件语义，实际入账仅一次 |
| 100 元付款后退款 30 元，再退款 70 元 | 可以保存两条退款，但没有自动原单关联和退款累计约束 | 一笔付款关联两次退款，累计不超过可退金额 |
| 100 元付款很快退回 100 元，退款被识别成普通 Income | 在资产管理与转账识别都开启时，可与原支出合成 Transfer | 保留付款和退款两事件，关联冲减 |
| 自动转账开启后，短时间内不相关的收入/支出同额 | 可被当成账户间转账 | 确认本人账户对及转账证据后才自动归并 |

### 退款并非完全没有实现，但自动闭环不足

- 有 `IncomeRefund` 类型；本地月度支出采用 `Expend - IncomeRefund`，不把正确标记的退款计入普通收入。
- 编辑器可以手动选择原账单，把关系写入通用 `extendData`；当前选择流程依赖记账应用同步过来的账单列表，不是本地独立退款关系模型。
- 本地模型没有专门的支付订单号、退款单号、原支付 ID、退款状态和累计退款字段；规则解析也没有把订单身份写入统一字段。
- 默认 AI 提示词只允许 Transfer/Income/Expend，并把“退款”归为 Income，与本地退款统计语义冲突。
- “退款申请中”“商家退款成功”“银行卡实际到账”没有统一事件状态机；具体规则是否排除处理中提示，因规则包不在仓库内不能确认。
- 跨月退款按退款当月冲减支出；这是一种统计口径，不自动认定为缺陷，但 UI 应说明，原消费页面也应能看到已退金额。
- 钱迹非 Hook 模式把退款降级成普通收入。Hook 模式可执行专用退款，但只查询最近 10 天原单；未填原单或查询不到原单时函数正常返回，上层仍提示退款成功并标记已同步。这是明确的错误成功路径，需单独修复。

证据：`DuplicateDetector.kt:64`、`:89`，`BillInfoDao.kt:39`、`:141`，`TransferRecognizer.kt:46`、`:109`，`BillInfoModel.kt:25`，`DefaultData.kt:150`，`PaymentInfoComponent.kt:394`，`QianJiAdapter.kt:271`，`RefundPresenterImpl.kt:49`，`SearchPresenterImpl.kt:116`，钱迹 `AutoHooker.kt:400`。

## 3. 确定问题与优化优先级

P1：影响账单正确性或核心采集，应在全自动记账上线前解决。P2：可靠性、兼容性和可维护性改进。优先级表示本产品的修复顺序，不代表已经发生线上事故。

| 编号 | 优先级 | 问题、触发条件与影响 | 建议 |
| --- | --- | --- | --- |
| D1 | P1 | 去重证据过弱；同额同类型不同渠道就可合并，无币种/账本/账户检查；时间相同直接合并 | 订单 ID 优先；币种/账本/账户冲突禁止合并；弱证据只标记疑似重复，等待确认 |
| D2 | P1 | 自动转账仅找窗口内同额候选，未证明两端是本人账户；Transfer 查询甚至未限制候选类型 | 维持默认关闭；仅凭明确自有账户对和转账证据自动处理，排除退款/报销/债务 |
| R1 | P1 | 退款缺统一关联/状态模型，AI 默认把退款当收入；部分退款、分次退款、延迟到账不能形成可靠闭环 | 独立退款事件与原单关联；记录申请/成功/到账/关闭等来源状态；退款幂等键和累计校验 |
| O1 | P1 | OCR 内容指纹把所有数字替换为 #，只保留前 300 字符；同页两笔不同金额/订单可能相同；全局上次指纹在识别成功前即写入 | 页面结构识别与交易内容去重分开；内容保留金额/订单/时间，按 App/页面隔离；失败可重试 |
| O2 | P1 | `SelectToSpeakService.structFp` 无赋值路径；采集函数只定义局部同名变量；“记住页面”读到空结构指纹，后续退化成包名+Activity匹配 | 在截图时绑定准确包名、Activity、结构快照，避免同 Activity 的不同页面误触发 |
| O3 | P1 | 无障碍包名过滤拒绝所有 `com.android.*`；默认银行白名单里的农行 `com.android.bankabc` 被一起过滤 | 精确排除系统组件，不能根据此前缀推断系统 App；测试每家银行包名 |
| N1 | P1 | 通知断线重绑使用框架 `NotificationListenerService` 类，而非清单注册的本项目 `NotificationService` | 修正 ComponentName；展示已连接/断线/最近采集时间，并测试断线恢复 |
| N2 | P1 | 通知按 pkg+title+text 在 60 秒内抑制；两笔相同文案的真实支付可能被丢掉；未保留 sbn.key/id/postTime | 以原始通知身份区分新通知与同通知更新，保留身份和时间并交由交易层判断 |
| R2 | P1（钱迹同步） | 退款缺原单或原单超过最近 10 天查不到时正常返回，上层仍标记成功；非 Hook 退款直接映射为普通收入 | 未找到原单明确失败/待处理；按原单 ID 查询而非固定 10 天；对外适配器明确能力边界 |
| I1 | P1 | 入库、主子账单更新没有一个数据库事务；进程 Mutex 只能防并发，不能防中途失败留下半合并状态 | 在一个 Room 事务内保存账单与关联，网络/AI放在事务外；持久化处理状态并支持恢复 |
| I2 | P1 | “不要去重”与 unGroup 只解除子账单分组；不会恢复已改写的父账单商户/账户，更不会恢复被转账识别改写的父类型 | 合并保存可逆记录；撤销时恢复双方原始字段和类型 |
| N3 | P2 | 普通通知监听用 extras.getString 读取标题/正文，Hook 版却正确用 getCharSequence；富文本通知可能读不到 | 统一 getCharSequence()?.toString()；补充扩展文本格式适配 |
| I3 | P2 | Hook AnalysisUtils 查询缓存使用 md5(data)，存储却用 data；缓存类内部还会再哈希，查询与写入键不一致 | 统一传原始稳定键；服务端的 contains+put 也应改原子操作；键加入来源并移除采集时间噪声 |
| I4 | P2 | 通知/短信直接异步请求本地服务，失败主要记录日志；短信 onReceive 使用 App.launch，无 goAsync/持久任务交接；服务端内容缓存先记键再处理，失败后短时间重试会被阻止 | 先可靠保存事件再处理；使用有状态待处理队列、重试与失败入口；短信快速落盘后交持久任务 |
| A1 | P2 | BillService 无论规则是否已命中，都调用 analyzeWithAI（开关允许时），最终仍优先用规则结果 | 仅规则失败/低置信时使用可选 AI，避免无收益网络请求和延迟 |
| O4 | P2 | 自动页面触发却发送 manual=true，开启 AI 能力后可能绕过 aiBillRecognition=false；常驻默认 false，OCR 后会 disableSelf | 区分真正手动操作和后台自动触发；能力开关一致；明确自动识别所需常驻状态 |
| O5 | P2 | 图像实际 JPEG 编码，但返回 data:image/png；本地 OCR 也无谓编码一份未使用的 Base64；丢失置信度/文本框信息 | MIME 与字节一致；按需编码；保留文本框/置信度，低置信金额提示确认 |

补充说明：

- O1 例子：`支付成功 金额10.00 订单123` 和 `支付成功 金额20.00 订单456` 均归一化为 `支付成功 金额# 订单#`。这是内容算法本身的确定冲突，不需要等待某款手机才能判断。
- O2 的页面匹配实际为结构完全相等或空结构通配，并非注释提及的相似度匹配。修复数据传递后仍应使用可维护的页面模板，避免小布局变动令自动识别失效。
- O4 是代码中的实际默认值：自动触发开启、常驻关闭。若无 Root/Shizuku 等重新启用路径，识别结束后自动链路可能停止；需在无特殊权限设备验证完整使用流程。
- I3 不能直接推导“每次都会重复入账”：下游还有缓存和分组；但该层去重确定不起预期作用，叠加同渠道免合并/超过TTL可增加重复风险。
- OCR 模型注释称默认 GPU，实际执行 `Device.CPU`；实际 CPU 并不是缺陷，但文档应修正，耗时/功耗不能依据注释宣传。
- 原始数据在服务端识别之前落库，便于排查；但没有覆盖“服务端尚未收到数据”的可靠保存保障。
- 规则引擎有正金额筛选、单规则异常跳过、用户规则优先和禁用规则阻断等保护；不能因某个字段解析函数缺校验就断言所有零金额结果都能入账。

Android 将通知额外字段定义为 CharSequence；通知监听断开后需要针对自身组件请求重新绑定。见 [Notification 文档](https://developer.android.com/reference/android/app/Notification)、[NotificationListenerService 文档](https://developer.android.com/reference/android/service/notification/NotificationListenerService)。短信异步工作生命周期参见 [Android 广播说明](https://developer.android.com/develop/background-work/background-tasks/broadcasts)。

## 4. 建议的二开设计

不引入 AI 财务分析。基础能力以可解释的规则和本地数据为主。

1. **建立统一事件模型**：来源、源消息ID、支付订单号、退款单号、原支付ID、商户、币种、金额最小单位、付款账户标识、交易时间、接收时间、交易状态和原始证据。不同来源映射到同一交易，不直接覆盖证据。
2. **拆开三件事**：同一通知反复回调是采集幂等；微信/银行描述同一交易是跨渠道归并；退款关联原支付是业务关联。退款不能当成重复支出去掉，也不能仅凭同额收入/支出当成转账。
3. **保守归并**：订单号等强身份相符时自动合并；缺订单号时综合卡尾号、商户、时间、金额等证据，存在冲突不合并。不确定记录进待确认，不静默吞掉。
4. **退款独立记录**：支付100、退款30、退款70保留三事件；原单展示已退100、净支出0；退款申请不直接算到账；微信退款成功和银行入账作为同一退款不同阶段/证据。
5. **可靠处理流水线**：接收即持久化；规则版本与识别结果可追溯；事务归并；失败可重试；合并可撤销。外部记账 App 的同步状态与本地账单状态分开。
6. **采集健康页**：按通知/短信/微信Hook/支付宝Hook/OCR显示权限、连接、最后接收、最后识别、失败原因及规则版本。用真实成功记录展示“已验证”，不要只用开关表示“运行正常”。
7. **离线规则包**：先做好微信支付/退款、支付宝支付/退款、目标银行扣款/入账规则，建立脱敏样本集。银行名单由真实样本和测试结果维护，不能由默认白名单生成。

金额建议使用整数最小货币单位或精确十进制；现在 Double + 两位四舍五入适合常见人民币展示，但不应继续作为跨来源身份比较的唯一金额依据。

建议执行顺序：先 N1/N2/O1/O2/O3/I3 等确定缺陷和退款错误成功路径；随后 D1/D2/R1/I1/I2 的数据模型及事务改造；最后完善规则包、重试、OCR质量和真机覆盖。重构去重/退款模型前，保留待确认模式，暂不默认打开自动转账。

## 5. 验收场景（待执行，不是通过记录）

| 范围 | 必须覆盖的场景 | 验收重点 |
| --- | --- | --- |
| 同一事件 | 同一回调重复、同通知更新、进程重启重放 | 同一事件只产生一次业务记账 |
| 独立事件 | 同商户同额连续支付、同秒同额、不同卡/币种/账本同额 | 不错误合并、不漏记 |
| 跨渠道 | 微信/支付宝+短信+银行通知+OCR，不同到达顺序，延迟10分钟/1天 | 同一交易归并正确，来源证据完整 |
| 退款 | 全退、部分退、多次退、退款重发、超过原金额、找不到原单、跨月和超过10天原单 | 正确关联、累计校验、失败不伪成功 |
| 状态变化 | 退款申请中、退款失败、平台成功、银行到账 | 未完成事件不提前计入到账 |
| 转账 | 本人账户转账、信用卡还款、同额无关收支、支付后立即退款 | 只有有依据的转账才排除收支 |
| OCR | 同模板不同数字、同屏重复、首次识别失败后重试、同Activity不同页面、农行包名 | 正确触发、无误屏、无数字归一化漏记 |
| 采集恢复 | 通知断线重连、服务未启动、锁屏、权限撤销、系统回收、短信分段 | 故障可见；已接收事件可恢复处理 |
| 写入恢复 | 合并中途异常、并发多来源、撤销合并、外部同步失败 | 数据原子一致、可恢复且可追溯 |
| 无AI | 关闭所有AI识别开关，断网使用本地规则与OCR | 基础识别可用，不意外请求AI |

测试报告应按渠道/银行/App版本/Android版本/手机型号分别给出采集成功率、识别正确率、重复率、误合并率和处理延迟。当前未获得这些实测指标，不能估算一个“准确率”。

## 6. 关键代码路径索引

以上行号基于本次工作区，后续修改可能移动。

- 采集：`app/src/main/java/net/ankio/auto/service/NotificationService.kt`、`SmsReceiver.kt`、`AnalysisUtils.kt`。
- Hook：`app/src/main/java/net/ankio/auto/xposed/XposedModule.kt`；`xposed/core/utils/AnalysisUtils.kt`；`xposed/hooks/wechat/WechatHooker.kt`、`hooks/DatabaseHooker.kt`；`xposed/hooks/alipay/hooks/MessageBoxHooker.kt`；`xposed/hooks/android/hooks/NotificationHooker.kt`。
- OCR触发：`app/src/main/java/com/google/android/accessibility/selecttospeak/SelectToSpeakService.kt:193`；`app/src/main/java/net/ankio/auto/service/ocr/PageSignatureManager.kt:64`。
- OCR识别：`app/src/main/java/net/ankio/auto/service/OcrService.kt:138`、`:203`、`:340`；`service/ocr/OcrTools.kt:48`；`ocr/src/main/java/net/ankio/ocr/OcrProcessor.kt:58`。
- 规则：`app/src/main/java/net/ankio/auto/update/RuleUpdateHelper.kt:123`；`app/src/main/java/net/ankio/auto/http/license/RuleAPI.kt:34`；`server/src/main/java/org/ezbook/server/engine/RuleGenerator.kt:50`。
- 核心流水线：`server/src/main/java/org/ezbook/server/tools/BillService.kt:159`、`:199`、`:233`、`:400`。
- 去重/转账：同目录 `DuplicateDetector.kt:64`、`TransferRecognizer.kt:46`、`BillManager.kt:45`、`BillMerger.kt:184`、`MD5HashTable.kt:61`。
- 账单模型/统计：`server/src/main/java/org/ezbook/server/db/model/BillInfoModel.kt:25`；`db/dao/BillInfoDao.kt:39`、`:141`；`constant/DefaultData.kt:150`。
- 退款关联UI：`app/src/main/java/net/ankio/auto/ui/dialog/components/PaymentInfoComponent.kt:394`；`ui/dialog/BillSelectorDialog.kt:160`。
- 钱迹退款同步：`app/src/main/java/net/ankio/auto/adapter/QianJiAdapter.kt:271`；`xposed/hooks/qianji/impl/RefundPresenterImpl.kt:46`、`SearchPresenterImpl.kt:116`；`xposed/hooks/qianji/hooks/AutoHooker.kt:400`。
- 撤销：`app/src/main/java/net/ankio/auto/service/OverlayService.kt:108`；`server/src/main/java/org/ezbook/server/server/BillRoutes.kt:130`。
