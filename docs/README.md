# 文档导航

先确定来源文件、业务版本和数据映射，再选择 SDK 或独立 CLI。

## 第一次使用

| 你要完成的事情 | 阅读入口 |
|---|---|
| 了解项目能解决什么问题 | [项目首页](../README.md) |
| 用自有 OFD/PDF 生成待签文件 | [快速开始](quickstart.md) |
| 编译运行独立 Java 工程 | [Java 接入工程](../examples/java-sdk/README.md) |
| 判断是否适合自己的场景 | [常见问题](faq.md)、[能力与兼容性](implementation-status.md) |

## 业务集成

| 主题 | 文档 |
|---|---|
| Java 请求构造、校验、转换与检查 | [SDK 接入](sdk.md) |
| 字段含义、版本关系、跨语言调用及归档恢复 | [完整接入说明](integration.md) |
| 来源信息写到 OFD 的什么位置 | [架构与数据映射](design.md) |
| 入院记录、检验报告及自定义临床 JSON | [临床 Schema](clinical-schemas.md) |
| 既有签章服务、底层 SM2 原文/摘要接口 | [CA 接入](ca-integration.md) |
| 字段错误、文件错误及失败后的产物状态 | [错误处理](errors.md) |
| 页面依赖前置及阅读端要求 | [页面布局](page-order.md) |

## 检查与验证

- [标准检查覆盖](standard-rule-matrix.md)：每组规则检查什么、仍缺什么证据。
- [标准适用边界](standard-open-questions.md)：压缩、线性化、外部附件的处理范围。
- [契约验证](contract-verification.md)：请求和报告 Schema 的正反例验证。
- [能力与兼容性](implementation-status.md)：运行环境、资源预算和已执行验证。
- [接入验收](acceptance-and-release.md)：试点应核对的输入、产物和外部证据。

## 维护与扩展

[技术选型](technology-decisions.md)解释当前模块及依赖取舍；[后续方向](roadmap.md)列出外部验证和扩展重点。开发、发行及安全流程分别见 [参与开发](../CONTRIBUTING.md)、[发布说明](publishing.md)和[安全反馈](../SECURITY.md)。

工具版本为 `0.2.0`，请求、报告、内置 Schema 及元数据映射的版本为 `0.1.0`，分别管理。
