# SDK 接入与字段校验

本文对应工具版本 0.2.0。请求契约、元数据映射和内置临床 Schema 的版本仍为 0.1.0。已有服务类、请求构造器及错误码继续可用。

SDK 通过源码安装或组织内部 Maven 仓库分发；先在源码根目录执行 `mvn -B -ntp clean install`，再由宿主工程引用以下坐标。完整、可独立构建的消费者见 [examples/java-sdk](../examples/java-sdk/README.md)，错误码与恢复方式见 [错误码与诊断](errors.md)。

## 统一入口

仅处理原生 OFD 时，引用 `org.openofdh:ofdh-core:0.2.0`：

```java
import org.openofdh.core.api.Ofdh;
import org.openofdh.core.prepare.PreparedDocument;

Ofdh ofdh = Ofdh.create();
PreparedDocument prepared = ofdh.prepare(requestFile, inputRoot, outputFile);
```

`requestFile` 是已有请求 JSON 的 `Path`，`inputRoot` 是来源文件的根目录，`outputFile` 是尚不存在的输出文件。输入根目录和输出父目录应预先建立。

需要 PDF 来源时，引用 `org.openofdh:ofdh-pdf:0.2.0` 并显式注册转换器：

```java
import org.openofdh.core.api.Ofdh;
import org.openofdh.pdf.PdfSourceAdapter;

Ofdh ofdh = Ofdh.builder()
        .sourceAdapter(new PdfSourceAdapter())
        .build();
```

实例可重复使用，构建时编译的 Schema 在后续处理时复用。自定义转换器的并发安全由其实现负责。原生 OFD 路线不依赖 PDFBox，也不会通过反射自动加载转换器。

## 直接赋值

Java 接入不必先写请求 JSON。以下变量来自同一来源版本的文书导出流程；`sourceCreatedAt` 为 `OffsetDateTime`，`documentKeywords` 为字符串列表：

```java
import org.openofdh.core.api.DocumentRequest;
import org.openofdh.core.validation.ClinicalSchemas;

DocumentRequest request = DocumentRequest.builder()
        .documentId(documentId)
        .sourceRecord(sourceSystem, sourceRecordId, sourceVersion)
        .documentType(documentTypeCode, documentTypeScheme)
        .patient(patientId, patientAuthority)
        .encounter(encounterId, encounterAuthority)
        .responsibleParty(authorId, authorName, authorAuthority)
        .createdAt(sourceCreatedAt)
        .keywords(documentKeywords)
        .confidentiality(confidentialityCode, confidentialityScheme)
        .pdf(sourceRelativePath)
        .clinicalData(clinicalRelativePath, ClinicalSchemas.ADMISSION_RECORD)
        .build();
```

来源为 OFD 时使用 `.ofd(sourceRelativePath)`；两种来源方法都可以用第二个参数传入预期 SHA-256。不提供临床 JSON 时省略 `clinicalData`。`encounter` 是否提供取决于业务；内置入院记录 Schema 要求临床 JSON 有就诊标识。

构造器仅默认填写请求契约版本，不补造业务身份、代码、版本或时间。`build()` 返回不可变请求，允许暂时不完整，便于一次收集字段问题。`createdAt(String)` 保留原字符串供校验；`createdAt(OffsetDateTime)` 输出含秒及明确时区的时间。当前引擎拒绝不可表示的闰秒，不会静默归整。

## 提前校验

```java
import org.openofdh.core.validation.ValidationReport;

ValidationReport metadata = ofdh.validate(request);
ValidationReport clinical = ofdh.validateClinical(request, clinicalFile);
```

先判断 `metadata.isValid()`；不通过时向开发者返回问题列表。元数据通过且存在临床数据后，再调用 `validateClinical`，其中 `clinicalFile` 是实际 JSON 的 `Path`。两种校验均不打开源 PDF/OFD。`prepare` 仍会重新校验实际输入，不依赖调用方是否提前校验过。

字段错误以 `valid=false` 返回；JSON 解析、文件读写、Schema 配置错误仍抛异常。直接生成时，字段错误也会抛出 `OfdhException`，调用方可使用 `getCode()`、`getIssues()` 和 `isTruncated()` 按业务错误协议返回结果，不需要解析自然语言提示。

## 字段错误报告

`ValidationReport` 包含 `valid`、`issues` 和 `truncated`。每个问题包含：

| 字段 | 含义 |
|---|---|
| `path` | JSON Pointer，例如 `/patient/id` 或 `/content/items/0/result/value`；空字符串表示根节点 |
| `keyword` | 约束类别，例如 `required`、`type`、`format`、`sourceConsistency` |
| `message` | 中文错误说明 |
| `expected` | 中文约束或修正提示 |
| `actualType` | 当前 JSON 类型或 `missing`，不包含实际字段值 |

字段名中的 `/` 和 `~` 分别转义为 `~1` 和 `~0`。报告按路径稳定排序、去重，最多返回 100 条。`truncated=true` 表示仍有未列出的错误，修正后应再次校验。路径包含输入属性名称，调用方应避免把患者信息当作动态属性名。

缺失字段定位到该字段本身；父对象不存在时定位到父对象，不虚构其所有子字段的错误。通用互斥 Schema 无法匹配时报告组合约束所在字段，不把其他候选分支的字段都当作必填。内置检验结果按 `kind` 选择约束，能定位到当前结果的具体字段。

报告契约见 [validation-report.schema.json](../contracts/validation-report.schema.json)。工具不自动将数字字符串、无时区时间或未知代码转换为看似合法的数据，也不回显原始字段值。

## 复用 Schema

统一入口默认注册通用封套、入院记录和检验报告三个 Schema。它们是项目接入规范，不是国家标准发布的官方 Schema。临床 JSON 由来源系统提供；工具不从页面抽取或补造临床内容。

已有独立规范时，可在构建入口时注册：

```java
Ofdh ofdh = Ofdh.builder()
        .sourceAdapter(new PdfSourceAdapter())
        .clinicalSchema(schemaId, schemaVersion, localSchemaFile)
        .build();
```

本地 Schema 使用 Draft 2020-12，仅允许文件内 `#` 引用。同一 ID 和版本不能覆盖；修改内置字段约束后，应使用自己的 ID 和版本。通用封套只约束公共字段，不证明正文符合某类临床规范。完整字段说明见 [临床 Schema](clinical-schemas.md)。

## 命令行

需要按页渐进读取时，可在构建入口增加 `.optimizePageOrder(true)`。生成与签署都将按文档页序前置页面依赖，仍使用普通 OFD/ZIP，不增加私有索引；完整线性化符合性仍未证明。配置、阅读端要求及错误边界见 [页面布局与渐进读取](page-order.md)。

```sh
ofdh schema list
ofdh schema export --id urn:openofdh:clinical:admission-record --schema-version 0.1.0 --out admission.schema.json
ofdh validate-request --request request.json
ofdh validate-clinical --request request.json --input-root input
```

使用内置 Schema 时不必传 `--clinical-schema`；自定义规范仍需提供该参数。导出拒绝覆盖。`validate-clinical` 只读取请求及输入根目录内的临床 JSON，不要求源 PDF/OFD 已生成。

CLI 字段错误写入 stderr，保留 `code`、`message`，并新增 `issues`、`truncated`。请求错误返回 64，临床内容错误返回 2，读写失败返回 4；成功结果写入 stdout，`valid=true`、`issues=[]`、`truncated=false`。完整参数见各命令的 `--help`。

## 后续处理

`ofdh.check(path)` 默认按最终阶段检查，待签文件需显式指定 `CheckProfile.PREPARED`。`ofdh.sign(prepared, output, provider)` 复用已有签署流程及签后检查。生成成功表示待签文件已生成；签署授权与归档仍由宿主负责。

院方 CA 仅返回签名值时，可用 `Sm2SignProvider.forMessage(certificateChain, signer)` 或 `forDigest(...)` 生成提供者。回调实现 `Sm2Signer`，通过 `Sm2Signature.der(...)` 或 `raw(...)` 明确返回格式；OFD 签名封装及签后验证由中间件处理。两种模式的待签字节含义不同，接入前按 [CA 与底层签名接入](ca-integration.md) 核对，不能把预计算摘要再次交给原文模式。已有完整 OFD 签章服务继续实现 `SignProvider`。

当前版本尚未完成完整线性化、独立视觉验收或完整临床语义验证，检查报告继续记录未覆盖项。既有构造器和底层服务入口保留；回退时恢复已验证的依赖与调用方式，保留已生成和已签署文件。
