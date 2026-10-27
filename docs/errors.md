# 错误处理与失败后的产物状态

本文对应 0.2.0 的 `ofdh-core`、`ofdh-pdf` 和正式 CLI 主代码。错误码用于程序分支，中文提示用于定位问题；不要解析提示文字，也不要把底层异常、堆栈或原始 JSON 直接返回给外部用户。新增但尚未识别的错误码应按失败处理，默认不自动重试。

## SDK 异常与检查报告

`OfdhException` 继承 `IOException`。接入代码应先捕获 `OfdhException`，读取 `getCode()`、`getIssues()`、`isTruncated()`，再处理普通 `IOException`。普通文件错误没有 SDK 错误码：文件不存在、权限不足、磁盘写入、第三方组件读取失败和临时文件清理失败都可能直接以 `IOException` 返回。

`IllegalArgumentException` 表示 Java 接入参数或配置错误，例如资源限额非正数、重复注册来源适配器、用转换器覆盖内置 OFD、格式标识不受支持、Schema 标识为空白、信任锚含空值。必需的 Java 对象传入 `null` 通常抛出 `NullPointerException`。这些异常不是字段校验结果，应修复调用代码后再执行，不应作为瞬时故障重试。运行依赖缺失、类库资源损坏或 JVM 错误也不保证转换成 `OfdhException`。

`Ofdh.validate(...)`、`validateClinical(...)` 在可完成字段校验时返回 `ValidationReport`；必须检查 `isValid()`。JSON 无法解析、文件不可读等情况仍可抛异常。`prepare(...)` 会重新验证请求和临床数据，预校验通过不替代生成时校验。

`check(...)` 通常把文档缺陷记录为报告中的 `FAIL`，而不是直接抛出对应的包结构异常。`CheckReport.assessment` 中的 `ISSUES_FOUND` 表示发现问题，`INCOMPLETE` 表示检查范围不完整。`OFDH-xxx`、`PKG-xxx`、`META-xxx`、`CLINICAL-xxx`、`SIGN-xxx` 是报告规则编号，与本页的异常码分属两套契约。

## 字段问题的适用范围

请求、临床正文 Schema、封套、来源一致性，以及部分 Schema 定义错误可提供 `issues`。每项包含 `path`、`keyword`、`message`、`expected`、`actualType`，不保存字段原值。`path` 是 JSON Pointer；空字符串表示整个输入，缺失字段可指向 `/patient`，数组项可指向 `/content/items/0/result/value`。

问题按字段路径等稳定排序、去重，最多返回 100 项。`truncated=true` 表示还有问题未列出，修正后应重新验证。组合条件可能在父字段返回约束，不保证每个分支都有独立问题。未知字段路径可以含输入中的字段名，因此报告仍应在受控环境保存。

请求文本最终需要写入 OFD 元数据。`keyword=xmlCharacter` 表示字段包含 XML 1.0 无法表示的字符或不成对的代理项；这是 JSON 语法及 Schema 之外的运行时检查。合法生僻字、换行和制表符仍允许，错误不回显原始内容。

文件读写、非法 JSON 语法、OFD 引用、PDF 渲染、签署和发布错误一般没有字段列表。`issues=[]` 不表示成功。临床校验一次可汇总多个字段，但顶层错误码只标识优先失败类别；不要仅凭顶层码丢弃其他 `issues`。

## 请求与 Schema 阶段

本组在校验或准备前置阶段失败时，不会发布新的文档。修改输入或配置后重新校验；校验本身不修改来源文件。

| 错误码 | 意义 | 恢复方式 |
| --- | --- | --- |
| `REQUEST_INVALID` | 请求缺少字段、类型或格式不合法，或命令需要的请求信息缺失 | 按 `issues` 修正；日期必须是真实且可表示的带时区时间 |
| `JSON_INVALID` | JSON 为空、UTF-8 非法、语法错误、重复键或尾随内容 | 修复序列化及编码；不要重试相同字节 |
| `SCHEMA_INVALID` | Schema 未声明受支持的 Draft 2020-12、元模式不通过或无法编译 | 修正本地 Schema，检查可用的字段问题 |
| `SCHEMA_EXTERNAL_REFERENCE` | Schema 使用文件外引用或其他禁止的引用方式 | 改为同文件内的片段引用；不会联网下载规则 |
| `SCHEMA_ALREADY_REGISTERED` | 同一 Schema 标识与版本已注册 | 去掉重复注册；自定义规则使用自己的标识与版本 |
| `SCHEMA_RESOURCE_MISSING` | 发行包缺少内置临床 Schema 资源 | 修复依赖打包或重新安装完整发行物 |
| `SCHEMA_UNKNOWN` | CLI 导出时未找到指定的内置 Schema | 用 `schema list` 核对标识和版本 |
| `CLINICAL_SCHEMA_UNKNOWN` | 请求中的临床 Schema 未注册 | 选用内置规范或显式注册对应本地规则 |
| `CLINICAL_DATA_INVALID` | 临床正文未通过已注册 Schema，或 Schema 校验执行失败 | 按问题列表修正正文；无问题列表时检查规则与运行环境 |
| `CLINICAL_ENVELOPE_INVALID` | 受支持的封套缺少必填结构或字段格式不正确 | 修正封套，不通过补入随意值规避校验 |
| `CLINICAL_PROFILE_UNSUPPORTED` | 声明了本项目尚不支持的临床封套版本 | 使用当前支持的封套，或先实现并验证版本适配 |
| `SOURCE_VERSION_MISMATCH` | 请求与临床封套或来源 OFD 元数据不一致 | 从同一文书版本重新取得页面、请求和机读数据；可能带来源一致性 `issues` |

## 输入、路径与资源预算

这些码可能出现在多个阶段。`prepare`、`sign` 的受控生成流程在发布前失败时不新增目标文档；`check` 的输入本来就存在。若调用方在生成成功后又执行检查或写报告，后续失败不会撤销已生成的文档。

| 错误码 | 意义 | 恢复或重试条件 |
| --- | --- | --- |
| `INPUT_INVALID` | 输入不是可读取的普通文件 | 改用实际普通文件，检查调用路径 |
| `INPUT_CHANGED` | 建立快照、验签或发布前后输入字节发生变化，或来源转换器改写/删除只读快照 | 停止并发写入并修复转换器，以稳定来源重新执行；不要复用旧摘要 |
| `SOURCE_HASH_MISMATCH` | 来源文件摘要与请求提供的预期摘要不一致 | 核对来源版本，重新从可信来源获取文件及摘要 |
| `PATH_INVALID` | 包成员或相对输入路径含非法、歧义或保留名称 | 修复上游路径，避免反斜杠、空组成部分等不允许形式 |
| `PATH_OUTSIDE_ROOT` | 路径越界、经过符号链接/目录联接，或目录发生重定向 | 使用明确的输入根目录和不经链接的实际目录 |
| `LIMIT_EXCEEDED` | 字节数、展开大小、成员数、页数、嵌套深度或绘制量超限 | 查明文件规模；确需扩大时显式调整限额并评估资源预算 |
| `OPERATION_CANCELLED` | 线程已中断 | 结束当前任务；需要重试时创建新的执行任务，并先确认输出状态 |
| `RESOURCE_MISSING` | 引用的包内文件不存在 | 修复来源包的依赖，不能用空文件替代 |

## OFD、ZIP、XML 与页面排序

在准备或签署发布前出现本组错误时，不发布新的目标文档。相同文件通常不会因重试恢复。检查接口可能把相同缺陷转成 `FAIL` 报告。

| 错误码 | 意义 | 恢复方式 |
| --- | --- | --- |
| `SOURCE_PROFILE_UNSUPPORTED` | 来源超出当前支持范围，如 OFD 版本、加密、复杂扩展、ZIP64、活动内容、未注册转换器或受限 PDF 特性 | 使用支持的导出方式或实现明确适配；不能只修改版本标签 |
| `SIGNED_INPUT_REQUIRES_DERIVATION` | 来源已签署或包含签名引用，不能原地改写或追加签名 | 回到未签来源；历史签署版本由宿主保留，派生关系另行设计 |
| `OFD_STRUCTURE_INVALID` | OFD 入口、页面、标识、文档元数据或附件登记不合法 | 修复来源结构，重新导出 |
| `REFERENCE_INVALID` | 内部引用路径为空、歧义或不支持 | 修正拥有该引用的 XML 文件及相对路径 |
| `REFERENCE_CYCLE` | OFD 文件引用存在循环 | 修复引用图，保持页面和资源依赖可终止 |
| `ZIP_INVALID` | ZIP 中央目录、本地头、数据描述符、长度、CRC 或成员范围不一致 | 重新传输或重新导出完整文件，不忽略校验 |
| `ZIP_DUPLICATE_ENTRY` | 包成员重名，或存在大小写/规范化名称歧义 | 统一包内名称并修复引用 |
| `ZIP_LINK_ENTRY` | ZIP 中存在链接或特殊类型成员 | 重新导出仅含普通成员的文档包 |
| `ZIP_LAYOUT_INVALID` | 指定的输出排列没有恰好覆盖全部包成员 | 修复布局调用，不删除未知成员来凑齐排列 |
| `XML_INVALID` | XML 语法或安全约束不通过 | 修复 XML，禁用 DTD、外部实体等不允许内容 |
| `XML_SECURITY_CONFIGURATION` | 当前 Java XML 实现无法启用所需安全设置 | 修复运行时或冲突依赖，不关闭安全设置绕过 |
| `XML_WRITE_FAILED` | XML 序列化失败 | 检查 XML 对象与运行依赖；保留来源，修复后重新生成 |
| `PAGE_LAYOUT_INVALID` | 页面、模板、资源、附件或标引依赖不完整、存在冲突或循环 | 修复来源依赖；不要把关闭排序当作数据修复 |
| `PAGE_LAYOUT_UNSUPPORTED` | 页面排序器尚不能安全分析该文档结构 | 评估是否可使用默认排列，或补充适配；默认排列仍须通过其他检查 |

## PDF 转换

本组在转换与准备发布前失败时不发布新的文档。PDF 转换还可能返回前述格式、预算、签署来源或普通 I/O 错误。

| 错误码 | 意义 | 恢复方式 |
| --- | --- | --- |
| `FONT_UNRESOLVED` | 所需字体未内嵌，或字体形式不在当前转换范围内 | 重新导出带受支持内嵌 TrueType 字体的来源 |
| `FONT_EMBEDDING_RESTRICTED` | 字体权限不允许本次嵌入 | 获取授权或使用允许嵌入的字体重新排版 |
| `PDF_TEXT_MAPPING_UNAVAILABLE` | 缺少可靠 Unicode/字形映射，或页面仅含图像而无法保留机读文字 | 改善来源导出；扫描页须另行完成 OCR 与内容复核 |
| `PDF_CONVERSION_FAILED` | PDF 渲染或新 OFD 初始化失败 | 用受控日志定位输入与依赖问题；不要无限重试同一文件 |

## 准备与签署

以下失败发生在 `Ofdh.prepare` 或 `Ofdh.sign` 的文档发布之前，不新增该次调用的目标文件。直接调用第三方 `SignProvider` 的输出行为不在此保证内；使用 `SigningService`/`Ofdh.sign` 完成受控快照、内容保持检查和最终验签。

| 错误码 | 意义 | 恢复方式 |
| --- | --- | --- |
| `PREPARED_CHECK_FAILED` | 准备阶段生成的暂存文档未通过检查 | 修复来源或集成映射；正式 CLI 不会发布该暂存文档 |
| `PREPARED_DOCUMENT_INVALID` | 待签文档未通过签前检查 | 先运行 `check --profile PREPARED` 定位，再重新准备 |
| `SIGNING_CONFIGURATION` | 密钥库、别名、口令配置、证书有效期、用途、链或密钥匹配不合法 | 修复已授权签署环境；不要输出口令、私钥或证书原文 |
| `SIGNING_ALGORITHM_UNSUPPORTED` | PKCS#12 或底层签名适配收到非受支持曲线的签署证书 | 使用 sm2p256v1 身份，或提供另行验证的整文件签署适配器 |
| `SIGNING_FAILED` | SM2 签名计算失败 | 检查签署组件和身份；重试前确认外部服务状态及授权 |
| `SIGN_PROVIDER_CHANGED_INPUT` | 签署适配器改写了传入的私有快照 | 修复适配器，使输入只读、输出写入独立目标 |
| `SIGN_PROVIDER_CHANGED_CONTENT` | 签署返回删除/修改原成员，或在允许范围外新增成员 | 排查适配器内容改写；不能通过重新生成摘要掩盖 |
| `SIGNATURE_VALUE_INVALID` | 底层回调未返回签名，或 DER／固定 64 字节 r‖s 的编码、长度及整数范围不合法 | 按厂商协议选择 `Sm2Signature.der` 或 `raw`，先解码传输文本，不传入完整签章容器 |
| `SIGNATURE_VERIFICATION_FAILED` | 回调签名与本次数据、用户标识或证书不匹配，或签后缺少有效签名、内容覆盖不全及最终检查失败 | 核对原文／预摘要模式、用户标识和签署身份，保留来源与受控诊断；不能自动重试 |

SDK 数字签名验证通过不等于证书已受信任或已有可信时间。证书信任、吊销、授权和适用算法证据应结合报告及实际签章服务评估。

底层回调的 `IOException` 会向调用方传播，不触发自动重试；错误发生后远端签署任务可能已完成。宿主应先查询任务和授权状态，再决定恢复方式。两种输入模式及签名编码约定见 [CA 接入](ca-integration.md)。

## 输出与正式 CLI

| 错误码 | 意义 | 恢复方式与产物状态 |
| --- | --- | --- |
| `OUTPUT_EXISTS` | 目标文档、报告或导出 Schema 已存在 | 不覆盖旧文件；核对是否为上次成功结果，改用新目标或复用已验证产物 |
| `OUTPUT_INVALID` | 输出父目录或文件名不合法，或文档与报告使用同一路径 | 修复输出路径；前置检查失败时不新增文档 |
| `ATOMIC_PUBLISH_UNSUPPORTED` | 文件系统不支持所需的不覆盖原子发布方式 | 改用支持的文件系统；该次发布未成功，不能退化为覆盖写入 |
| `ARGUMENT_INVALID` | CLI 参数缺失、未知或格式错误 | 查看 `--help`；命令未进入业务处理 |
| `IO_FAILURE` | CLI 捕获到普通 `IOException` | 检查路径、权限、空间及文件系统；**不能仅凭此码判断文档不存在** |
| `PROCESSING_FAILURE` | CLI 捕获到未映射的处理异常 | 检查集成和运行环境；不要自动无限重试，先核对输出状态 |
| `REPORT_WRITE_FAILED` | `prepare`/`sign` 已生成文档，后续报告写入失败 | 保留现有文档，按对应阶段运行 `check` 补报告；不能原样重跑生成命令 |
| `POST_PREPARE_CHECK_FAILED` | CLI 已发布待签文件，但后续检查失败或文件摘要已变化 | 标准输出保留 `PREPARED` 阶段、原发布摘要及 `reportStatus=NOT_WRITTEN`；核对现有文件后重新检查，不交付不匹配的报告 |
| `SERIALIZATION_FAILURE` | CLI 无法序列化错误摘要，输出兜底 JSON | 修复报告序列化依赖；此码不提供原始失败阶段，须核对已生成文件 |

CLI 的 `prepare`/`sign` 在报告写失败时，标准输出仍有制品摘要及 `reportStatus=FAILED`，标准错误有 `REPORT_WRITE_FAILED`，退出码为 `4`。`prepare` 发布后的复核失败返回 `POST_PREPARE_CHECK_FAILED` 和退出码 `4`，不会伪造完整检查报告。标准输出管道本身写入失败也会返回 `4`；这种情况下应依据实际文件、已落盘报告和已有任务记录核对状态，不能把非零退出码都解释为“未生成”。

正式 CLI 退出码如下；它与 [独立 Java 接入工程](../examples/java-sdk/README.md) 的轻量进程约定不同。

| 退出码 | 正式 CLI 含义 |
| --- | --- |
| `0` | 命令成功；`prepare`/`sign` 的检查覆盖仍可能不完整 |
| `2` | 检查发现 `FAIL`，或未归入下述类别的业务异常 |
| `3` | `check` 未发现 `FAIL`，但检查范围为 `INCOMPLETE` |
| `4` | 普通 I/O/处理异常，或签署计算失败、操作取消、原子发布不支持、发布后复核/报告写入失败 |
| `64` | 参数错误，或 `REQUEST_*`、`SCHEMA_*`、`OUTPUT_*`、`SIGNING_CONFIGURATION*`、`PATH_OUTSIDE_ROOT` 异常 |

错误码与退出码应一起处理。例如 `CLINICAL_SCHEMA_UNKNOWN` 的前缀是 `CLINICAL_`，正式 CLI 返回 `2`；`SCHEMA_UNKNOWN` 返回 `64`。字段格式检查返回 `REQUEST_INVALID`，正式 CLI 返回 `64`。不要用自行推测的类别覆盖实际映射。

## 重试与归档记录

同一输出路径禁止覆盖，不应在失败后自动删除再重试。先核对本次实际阶段、目标是否存在，以及已记录的 SHA-256；保留既有历史文档。只有明确的瞬时环境故障且输入已固定时才考虑新任务重试，签署还需遵循外部服务的幂等和授权约定。

建议宿主保存任务标识、来源版本、错误码、有限字段问题、已生成文档的摘要和报告状态。原始病历、解析器底层原因、患者字段值、密钥和口令不应写入通用错误日志。完整检查报告中含包内路径及制品文件名，仍应纳入文档访问控制。
