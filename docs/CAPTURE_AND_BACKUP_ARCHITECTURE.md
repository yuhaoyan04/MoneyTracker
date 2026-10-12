# SmartLedger 自动采集与备份架构

更新日期：2026-10-12

## 1. 能力边界

Android 没有向普通第三方应用开放“读取个人全部支付订单”的统一接口。SmartLedger 应把自动记账定义为**多信号采集和待确认**，而不是保证 100% 静默抓取。

| 信号 | 可覆盖 | 无法保证 | 产品定位 |
|---|---|---|---|
| 通知监听 | 支付应用实际发布、且用户允许展示的通知 | 被关闭/折叠/隐藏金额的通知；应用根本不发通知；工作资料通知 | 主链路 |
| 银行短信 | 银行发送的结构化消费、到账短信 | 无短信、号码/模板变化、双卡/OEM 限制 | 补充链路；上架需申请 SMS 权限例外 |
| 账单文件导入 | 用户从支付平台导出的历史账单 | 非实时，需要用户操作 | 兜底与对账主链路（待实现） |
| 商户 API | 自己商户号下产生的订单 | 普通消费者在任意商户的个人订单 | 不能用于个人全量账单 |
| MediaProjection | 用户明确同意的一次屏幕采集会话 | 无法永久静默；Android 14+ 每个会话需重新同意 | 不采用 |
| AccessibilityService | 部分页面可访问节点中的文字 | WebView/Canvas/安全页面、页面改版、OEM 保活 | 不作为核心；存在上架与隐私风险 |

依据：

- Android `NotificationListenerService` 只接收系统已发布的通知，并存在低内存设备、工作资料等限制：<https://developer.android.com/reference/android/service/notification/NotificationListenerService>
- Android 14+ 每次 `MediaProjection` 采集会话都必须取得用户同意：<https://developer.android.com/about/versions/14/behavior-changes-14#media-projection>
- Accessibility API 应优先让位于范围更窄的 API，非无障碍工具还需显著披露、用户同意和 Play 声明：<https://support.google.com/googleplay/android-developer/answer/16558241>
- 微信支付查询接口要求商户号和商户订单，面向商户自己的交易：<https://pay.wechatpay.cn/doc/v3/merchant/4012791858>
- Google Play 把“SMS-based money management”列为可能的例外用途，但仍需权限声明和审核：<https://support.google.com/googleplay/android-developer/answer/10208820>

## 2. 推荐采集管线

1. 通知和短信适配器只生成 `CaptureCandidate`，保留原文、包名、发生时间和解析器版本。
2. 先做同源持久化重放去重，再做跨源概率关联。仅凭“同金额 + 同分钟”不能永久合并。
3. 所有低置信度结果进入收件箱；金额为 0 的解析失败项单独展示，不能算入统计。
4. 用户确认后才训练个人分类器。用户纠正是强正样本，超时未修改只能是弱正样本。
5. 设置页展示最近 7/30 天按来源的捕获数、解析失败数和用户补录数，用实际覆盖率管理预期。

华为设备需要引导用户在“应用启动管理”中允许自动启动、关联启动和后台活动，并关闭该应用的电池优化。厂商设置只能提升存活率，不能补回支付应用从未发布的通知。参考：<https://consumer.huawei.com/en/support/content/en-us15850065/>

## 3. 备份格式

`smartledger-ledger` 是数据库之外的稳定交换协议：

- 顶层包含 `format`、`schemaVersion`、`exportedAt`、`appVersion`、`payload`、`journal`、`checksumSha256`。
- `payload` 使用独立 DTO，不直接序列化 Room 表；数据库重构不等于备份格式破坏。
- 每次备份保留当前 checkpoint，并把相对上一 checkpoint 的变化追加到 journal。
- 每笔交易使用可迁移 UUID；旧备份会得到确定性身份。恢复按 UUID 合并，绝不拿备份中的自增数据库 ID 覆盖本地行。
- 分类和渠道按业务唯一键恢复；整个导入在单一数据库事务中完成。
- 文件有 SHA-256 完整性校验，版本过新、字段非法或内容损坏时拒绝导入并给出原因。

Android 10+ 的 scoped storage 会记录文件归属；卸载重装后，应用可能无法直接访问旧安装创建的 MediaStore 文件。因此：

- 日常自动备份：私有原子 latest + 最近 5 代 + 每 6 小时最多一份公共不可变快照。
- 跨重装/跨设备：用户通过系统文件选择器导出、导入 JSON，这是可靠主路径。
- 不再删除旧公共备份后覆盖同名文件，避免写入中断导致唯一副本丢失。

Android 官方建议使用 Storage Access Framework 的 `ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT` 处理用户文档：<https://developer.android.com/training/data-storage/shared/documents-files>。应用专属目录会在卸载时删除，不能作为用户期望长期保留的数据副本：<https://developer.android.com/training/data-storage/app-specific>。

## 4. 后续优先级

1. 实现微信、支付宝和主流银行官方账单 CSV/XLSX 导入适配器及对账 UI。
2. 增加解析器版本和原始来源 ID，并把跨源关联从启发式升级为可解释评分。
3. 增加真实通知/短信脱敏语料的回归测试；每个支付 App 版本变更后运行。
4. 将备份目录授权升级为 `ACTION_OPEN_DOCUMENT_TREE`，允许用户选择长期同步目录，并提供保留周期设置。
5. 建立华为、小米、OPPO、vivo、三星和原生 Android 的真机覆盖率看板。
