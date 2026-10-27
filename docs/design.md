# 架构与数据映射

## 模块边界

| 模块 | 职责 |
|---|---|
| `ofdh-core` | 请求模型、字段与临床 Schema 校验、原生 OFD 封装、签署适配和检查报告 |
| `ofdh-pdf` | 受限静态 PDF 转换，作为显式注册的 `PageSourceAdapter` 使用 |
| `ofdh-cli` | 文件参数、结构化输出、退出码和独立进程入口 |
| `examples/java-sdk` | 独立 Maven 消费者，验证发布后的 SDK 接入方式 |

核心模块不依赖数据库或 Web 框架。文件、身份及业务状态由宿主提供；代码体系、医院表结构和归档策略留在业务接入层。

## 一次处理的生命周期

```text
DocumentRequest + source.pdf/source.ofd + 可选 clinical.json
        │
        ├─ 校验请求与本地 Schema
        ├─ 固定输入快照和摘要
        ├─ 保留/转换页面，映射元数据，登记临床附件
        └─ 检查并发布 PREPARED 文件
                     │
                     ├─ 院方整文件签章服务
                     └─ 底层 SM2 签名回调 + 项目签名封装
                                  │
                                  └─ 内容保持检查、密码验签、发布 SIGNED 文件
```

`Ofdh` 是 SDK 统一入口，提供 `validate`、`validateClinical`、`prepare`、`sign`、`check`。输入及输出在单次操作内固定快照，默认拒绝覆盖。输出与报告不是跨文件事务，宿主应保存实际完成阶段，详见 [错误处理](errors.md)。

## 文书身份与版本

- `documentId` 是业务文书标识，`sourceSystem + sourceRecordId + sourceVersion` 绑定来源记录。
- `patient` 和可选 `encounter` 负责患者及就诊关联，不能替代文件标识。
- `fileId` 是每次生成的 OFD 文件标识，写入 `DocInfo/DocID`。
- 来源摘要描述输入字节，最终摘要描述发布字节。相同业务请求重复生成可能产生不同文件标识、时间和摘要。

来源 PDF/OFD 与临床 JSON 应从同一业务版本导出。文件快照可以固定已提交的字节，但不能替代来源系统事务。

## 元数据写入位置

生成文件使用 `DocType=OFD-H`，文档入口为 `Doc_0/Document.xml`。优先使用 OFD 原生字段，其余来源身份采用公开的项目映射。

| 数据 | OFD 落点 |
|---|---|
| 文件标识 | `DocInfo/DocID` |
| 生成日期 | `DocInfo/CreationDate` |
| 责任者姓名 | `DocInfo/Author` |
| 关键词 | `DocInfo/Keywords/Keyword` |
| 软件及版本 | `DocInfo/Creator`、`CreatorVersion` |
| 映射版本 | `CustomDatas: openofdh.metadataProfile=0.1.0` |
| 精确生成时间 | `openofdh.generatedAt` |
| 业务来源 | `openofdh.documentId`、`sourceSystem`、`sourceRecordId`、`sourceVersion`、`sourceCreatedAt` |
| 来源摘要 | `openofdh.sourceSha256` |
| 患者与就诊 | `openofdh.patient.id/authority`、可选 `openofdh.encounter.id/authority` |
| 责任者身份 | `openofdh.responsibleParty.id/authority` |
| 文书类别与密级 | `openofdh.documentType.code/scheme`、`openofdh.confidentiality.code/scheme` |
| 临床附件绑定 | `openofdh.clinical.schemaId/schemaVersion/sha256` |

表中省略前缀的同组键仍带一次 `openofdh.`，例如 `openofdh.sourceSystem`。这些键属于项目协议，不是国家标准定义的统一医疗字段名。日期字段不能承载完整时区时间，因此精确时间另存扩展字段。

## 临床数据与页面

临床 JSON 按 OFD 附件机制登记，保留原始 UTF-8 字节并记录摘要。`schemaId` 和 `schemaVersion` 明确选择本地规则；统一 SDK 默认注册三种 [临床 Schema](clinical-schemas.md)。

页面只显示诊断名称而 JSON 携带来源诊断编码是允许的。Schema 检查类型、格式与已声明的一致性，不推断临床事实；页面、机读正文和医学语义的核对应由接入双方完成。

## 扩展点

| 入口 | 约定 |
|---|---|
| `PageSourceAdapter` | 声明并实现 PDF 转换；不能覆盖内置 OFD 或重复注册 |
| `clinicalSchema(...)` | 注册自包含的本地 Draft 2020-12 Schema；仅允许本文件片段引用 |
| `SignProvider` | 返回完整的已签 OFD，并保持原文书成员 |
| `Sm2Signer` | 返回实际 SM2 签名值；项目负责 OFD 签名结构、证书链和签后验证 |

工具实例和已编译 Schema 可以复用；自定义来源适配器、签名客户端及授权上下文的并发安全由实现方负责。详细调用见 [SDK](sdk.md)和 [CA 接入](ca-integration.md)。
