# 内置临床 Schema

内置 Schema 提供可直接复用的字段结构和校验约束。它们是 DocPaw-OFDH 的项目接入规范，**不是国家标准发布的官方 Schema，也不代表所有临床文书规范已经实现**。采用前应核对医院的文书类型、代码体系及数据来源。工具不会从病历正文推断或补造结构化字段。

所有 Schema 均采用 JSON Schema Draft 2020-12、UTF-8 和本文件内的 `#` 引用。每个文件自包含，导出后可独立注册，不需要网络访问或下载其他文件。工具发行版本与 Schema 版本分别管理；以下 Schema 的版本均为 `0.1.0`。

| Java 常量 | Schema ID | 适用范围 |
|---|---|---|
| `ClinicalSchemas.ENVELOPE` | `urn:openofdh:clinical:envelope` | 通用来源和文书元数据；正文内容由接入方定义 |
| `ClinicalSchemas.ADMISSION_RECORD` | `urn:openofdh:clinical:admission-record` | 入院时间、主诉、现病史、诊断及可选叙述字段 |
| `ClinicalSchemas.LAB_REPORT` | `urn:openofdh:clinical:lab-report` | 检验报告、标本、检验项目及有明确类型的结果 |

源文件位于 [schemas/clinical](../schemas/clinical/)。每个文件中的 `$id` 在 Schema ID 后附带 `:0.1.0`，用于标识该份完整定义；请求中的 `clinicalData.schemaId` 不带这个版本后缀，版本由 `clinicalData.schemaVersion` 单独提供。

## 注册与读取

简化 SDK 会默认注册三个内置 Schema。使用底层注册表时可以只注册需要的一种：

```java
ClinicalSchemaRegistry schemas = new ClinicalSchemaRegistry();
ClinicalSchemas.ADMISSION_RECORD.register(schemas);
```

也可以一次注册全部内置定义：

```java
ClinicalSchemaRegistry schemas =
        ClinicalSchemas.registerDefaults(new ClinicalSchemaRegistry());
```

注册表不允许覆盖同一 ID 和版本。`ClinicalSchemas.find(id, version)` 返回 `Optional<ClinicalSchemas>`；未知 ID 或版本不会被自动替换为某个默认版本。`openSchema()` 返回完整资源流，由调用方关闭，例如：

```java
try (InputStream input = ClinicalSchemas.LAB_REPORT.openSchema()) {
    Files.copy(input, outputSchemaFile);
}
```

以上 `outputSchemaFile` 来自调用方选定的导出位置；`Files.copy` 默认拒绝覆盖。使用导出的文件注册时，继续传入对应的 Schema ID 和版本。自行修改了字段约束的文件应使用自己的 ID 和版本，不应冒用内置定义的身份。

## 所有类型共用的封套

三个 Schema 采用同一套头部、元数据、身份及代码结构，公共定义保存在各文件的 `$defs` 中，并由回归测试核对一致性。业务正文统一放在 `content` 下。

| 字段 | 约束或来源 |
|---|---|
| `head.profile` | 固定 `urn:openofdh:clinical-envelope:0.1.0`，明确选择项目字段映射 |
| `head.schemaId / version` | 必须与所选内置 Schema 的 ID、版本一致 |
| `head.documentVersion` | 与请求的 `sourceVersion` 一致 |
| `head.createdAt / modifiedAt` | 带时区的真实时间；修改时间不得早于创建时间 |
| `identifier.documentId` | 与请求中的业务文书标识一致 |
| `identifier.documentType / confidentiality` | 必须提供 `code` 和 `scheme`；可选 `display` |
| `patient.id / authority` | 与请求中的患者标识和分配机构一致 |
| `patient.name / birthDate / sex` | 可选；出生日期校验真实日历值，性别使用 `code + scheme` |
| `encounter.id / authority` | 入院记录必须提供，另两种可选；请求包含就诊标识时校验已提供值的一致性 |
| `meta.creator.id / name / authority` | 与请求的责任者一致，不据此推断签署授权 |
| `meta.keywords` | 非空、不重复的字符串数组，与请求按集合比较 |
| `meta.properties.sourceSystem / sourceRecordId` | 与请求的来源系统、来源记录一致 |
| `meta.title` | 可选文书标题 |
| `extensions` | 明确的扩展对象，允许接入方自行定义字段 |

时间示例格式为 `2026-10-26T09:30:00+08:00` 或 `2026-10-26T01:30:00Z`。无时区时间、非法日期和把数字传入时间字段都会被拒绝。这里的日期仅说明格式，不由工具填入业务内容。

非正文的普通字符串最长 512 个字符；正文叙述最长 1,000,000 个字符，均拒绝全空白。文件仍受 SDK 的总字节数和嵌套深度限制。`null` 不用于表示未提供的可选字段；不提供时省略该字段。

除通用封套的 `content` 与明确标出的 `extensions` 外，对象拒绝未知字段，避免拼错字段名后被静默忽略。代码体系由调用方声明；内置 Schema 不验证某个诊断编码是否确实属于该体系，也不自动转换医院本地代码。

## 入院记录正文

`content` 的必填字段为：

| 字段 | 类型与含义 |
|---|---|
| `admittedAt` | 带时区的实际入院时间 |
| `chiefComplaint` | 非空主诉文本 |
| `presentIllness` | 非空现病史文本 |
| `diagnoses` | 非空诊断数组，最多 1,000 项，按来源顺序保存 |

每项诊断必须有 `text`；有编码时填写 `coding.code / scheme`，可附 `coding.display`。来源没有编码时保留文字诊断，不补造编码。可选 `primary` 是来源系统提供的布尔标记；本 Schema 不判断主诊断是否唯一或选择是否正确。

可选叙述字段为 `pastHistory`、`allergyHistory`、`physicalExamination`、`auxiliaryExaminations` 和 `assessmentPlan`。其他结构放入 `content.extensions`，或在采用自己的 Schema 后定义。这些可选性只是本项目接入约束，不能用来判断院方病历书写要求是否已满足。

## 检验报告正文

`content.reportId`、`reportedAt`、`specimen` 和 `items` 必填。`specimen.id` 必填；可选类型 `type` 使用代码对象，采集和接收时间分别使用 `collectedAt`、`receivedAt`。`items` 至少一项、最多 10,000 项。

每个项目包含 `test` 和 `result`。`test` 必须提供 `code / scheme`，可选 `display`。`result.kind` 明确结果类型：

| `kind` | `value` 类型 | 其他允许字段 |
|---|---|---|
| `NUMBER` | JSON 数值，不能是数字字符串 | 可选 `unit`；可选 `comparator` 为 `<`、`<=`、`=`、`>=`、`>` |
| `TEXT` | 非空文本 | 无 |
| `CODED` | `code / scheme` 代码对象，可选 `display` | 无 |

数量结果不自动补单位、不隐式转换单位，也不从结果字符串中猜测比较符。原始结果属于文本时，应选择 `TEXT` 保留其含义。具体单位采用什么编码体系由接入双方确定；本版本的 `unit` 只是单位原文。

结果先校验 `kind`，再执行该类型对应的字段约束。例如 `kind=NUMBER` 而 `value` 为数字字符串时，错误定位到该项目的 `result/value`，不会要求填写文本或编码结果分支的字段。`TEXT` 和 `CODED` 不接受仅用于数量结果的 `unit`、`comparator`。

项目还可携带 `referenceText`（参考范围原文）、`abnormalFlag`（`NORMAL / LOW / HIGH / ABNORMAL / UNKNOWN`）、`critical`（布尔值）和 `extensions`。这些标记只记录来源系统的判定；工具不根据数值推导异常或危急值，也不执行临床决策。

## 通用封套与自定义规范

通用封套要求 `content` 为至少包含一个属性的对象，但不限制内部字段。它适合已有成熟临床 JSON 的接入方统一携带来源身份，**不应被当作正文已经校验通过的证明**。

需要更严格的正文类型、跨字段规则或其他文书类型时，注册自己的自包含 Schema。结构规则由 Schema 执行；数据来源一致性、真实病历完整性、代码字典、诊断合理性、标本时序及页面与临床正文的医学含义仍需来源系统和业务验收负责。现有检查报告会保留未覆盖的临床语义与其他标准范围。
