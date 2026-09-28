<div align="center">

# MoneyTracker

### 让每一笔收支，自动落入你的账本

[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)](#)
[![Version](https://img.shields.io/badge/version-2.0.0-179A9D)](../../releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-179A9D)](LICENSE)
[![CI](https://github.com/yuhaoyan04/MoneyTracker/actions/workflows/android.yml/badge.svg)](../../actions/workflows/android.yml)

**自动抓取 · 智能分析 · 数据可视化 · 深色模式**

</div>

---

> 你在微信、支付宝、淘宝、京东或银行卡产生的每一笔收支，都会被 **自动抓取** 并进入待确认收件箱——核对一下，就自动记账。**不遗漏、不重复、不用手敲。**

MoneyTracker 是一款专注个人收支的本地记账应用。它把你从繁琐的手动记账中解放出来：支付 App 的通知、银行的交易短信，都会被解析成结构化交易，等你确认后入账。配合月度可视化统计与 AI 洞察，让你的钱去哪儿了一目了然。

## 为什么是 MoneyTracker

| 痛点 | MoneyTracker 的解法 |
|---|---|
| 每笔都要手敲，懒得记账 | 自动抓取通知/短信，到收件箱一键确认 |
| 固定分类不够用、渠道太少 | 分类与渠道完全自定义，随输随建 |
| 深色模式下文字看不清 | 全量语义色板 + Material3，深浅模式都清晰 |
| 统计只看个总数 | 月度收支、分类占比饼图、近 6 月趋势柱状图 |
| 想接 AI 还得改代码重打包 | 应用内填 DeepSeek Key 即可用，免编译 |

## 核心能力

- **自动抓取引擎**：基于 `NotificationListenerService` 抓取微信/支付宝/淘宝/京东及银行 App 的支付通知，`BroadcastReceiver` 解析银行交易短信。解析失败的也会按原文兜底入库，**确保不遗漏**。
- **待确认收件箱**：所有抓取记录先进收件箱，核对金额/分类/渠道后再确认入账，避免误记。支持「全部确认」「忽略」「编辑」。
- **自由分类与渠道**：不再只能选预设标签——分类、渠道、支付方式全部支持自由输入，输入新值自动建档。
- **月度可视化统计**：本月收入/支出/结余概览、支出分类占比饼图、近 6 月收支柱状趋势、分类明细带占比进度条。
- **多模型 AI 洞察**：内置 DeepSeek 预设，并兼容 GLM、Kimi、自定义 OpenAI 兼容入口；API Key 在应用内输入、本地混淆存储，**无需重新编译**。一键生成本月趋势分析与优化建议。
- **完整深色模式**：重写双主题色板，告别「深色模式看不见按钮」。
- **隐私优先**：所有数据本地 Room 存储，支持回收站（15 天自动清理）与备份/恢复。

## 安装

**方式一（推荐，零编译）**：前往 [Releases](../../releases/latest) 下载最新 `MoneyTracker-vX.X.X-release.apk`，手机开启「允许未知来源」后安装。

**方式二（自行编译）**：见下方 [构建指南](#构建指南)。

### 首次使用三步走

1. 打开 App → 底部「设置」→「自动抓取」，授予**通知使用权**与**短信权限**。
2. 正常用微信/支付宝等支付，交易会自动进入「收件箱」。
3. 在「收件箱」核对后点「确认」，即可在「账本」与「统计」中查看。

> 提示：部分国产 ROM 会限制后台通知，建议把 MoneyTracker 加入电池优化白名单，以保证抓取不中断。

## 截图

> 截图随版本更新补充。当前请直接下载 [Release APK](../../releases/latest) 体验。

## 构建指南

环境要求：JDK 17+、Android SDK（compileSdk 36）。

```bash
git clone https://github.com/yuhaoyan04/MoneyTracker.git
cd MoneyTracker
# 可选：如需 Release 签名，复制并填写 keystore.properties（见 keystore.properties.example）
#   未配置时 Release 构建会自动回退 Debug 签名，仍可出包
./gradlew assembleRelease        # 或 assembleDebug
```

产物路径：`app/build/outputs/apk/release/app-release.apk`

> AI 功能需在应用内「设置 → AI 助手」填入你自己的 API Key（默认 DeepSeek）。无 Key 不影响记账与统计功能。

## 技术栈

- **语言**：Kotlin
- **UI**：Android XML + Material Design 3（双主题深色适配）
- **数据库**：Room（统一交易模型 + 迁移）
- **图表**：MPAndroidChart
- **网络**：Retrofit 2 + Gson（OpenAI 兼容 LLM 客户端）
- **后台**：NotificationListenerService、BroadcastReceiver（短信）、WorkManager、Coroutines

## 架构亮点

- **增量式数据层**：新增统一 `TransactionRecord`/`Category`/`PaymentChannel` 三表与 Room 迁移，与既有账本共存，升级不丢数据。
- **解析器可扩展**：`TransactionParser` 基于正则提取金额/方向/渠道/商户，新增支付 App 文案只需补规则。
- **多模型抽象**：`DeepSeekClient` 为 OpenAI 兼容实现，新增厂商仅改预设。

## 路线图

- [x] Android 自动抓取 + 待确认收件箱
- [x] 月度可视化统计
- [x] 应用内多模型 AI 配置
- [x] 完整深色模式
- [ ] 真机抓取规则调优（覆盖更多银行/支付文案）
- [ ] **iOS 端适配**（多端同步）
- [ ] **HarmonyOS 端适配**
- [ ] 云端同步与多端协作

## 贡献

欢迎提 Issue 反馈漏抓的支付/短信文案（附上通知原文），我会补 `TransactionParser` 规则。PR 也欢迎——尤其是新增支付渠道的解析规则与多端适配。

## 致谢

本项目在 [mudasirunar/SmartLedger](https://github.com/mudasirunar/SmartLedger) 的基础上二次开发与重构，在此致谢原作者打下的基础。

## 许可证

本项目基于 [MIT License](LICENSE) 开源。使用与二次开发请同时尊重上游项目的相关声明。
