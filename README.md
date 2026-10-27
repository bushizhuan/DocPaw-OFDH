# DocPaw-OFDH

[![构建与验证](https://github.com/bushizhuan/DocPaw-OFDH/actions/workflows/build.yml/badge.svg)](https://github.com/bushizhuan/DocPaw-OFDH/actions/workflows/build.yml)

**把已有 PDF/OFD 文书、业务元数据和临床 JSON 接入 OFD-H 生成链路。**

DocPaw-OFDH 面向 HIS、EMR、LIS 厂商和医院研发团队，处理文件转换、元数据映射、机读附件封装、字段校验和签名适配，帮助接入方复用已有文书导出能力，在原有归档流程中增加 OFD-H 产物。

提供 **Java SDK** 和 **本地 CLI** 两种入口，采用 Apache-2.0 许可证。当前版本为 **0.2.0 工程预览版**，适合开展集成验证；实际 CA、阅读器及医院归档环境需要另行联调。

[快速开始](docs/quickstart.md) · [Java SDK](docs/sdk.md) · [CA 接入](docs/ca-integration.md) · [完整文档](docs/README.md) · [常见问题](docs/faq.md)

## 能帮助你做什么

| 接入时的问题 | 提供的能力 |
|---|---|
| 文书只能导出 PDF | 将受支持的静态 PDF 转成带可检索文字、嵌入字体和页面坐标的 OFD |
| 已能导出 OFD，需要增加医疗文书信息 | 保留页面与资源，写入文档类型、患者、责任者和来源版本等元数据 |
| 诊断编码、检验结果没有展示在页面上 | 按 Schema 校验临床 JSON，保存附件原始字节并记录摘要 |
| 希望在开发阶段定位字段问题 | 检查必填、类型、日期和格式，返回 JSON Pointer、中文说明和修正要求 |
| 希望复用医院现有 CA | 接入完整 OFD 签章服务，或由 CA 返回 SM2 签名值、工具完成 OFD 签名封装 |
| 希望了解生成文件检查到了什么 | 输出绑定 SHA-256 的逐项报告，区分密码验签、证书信任和未覆盖范围 |

一次生成对应**一份业务文书的一个来源版本**。批量调度、业务身份、代码字典、签署授权和归档索引由宿主系统管理。

## 放在现有系统的什么位置

```text
HIS / EMR / LIS
  ├─ 导出一份 PDF 或 OFD
  ├─ 提供同一版本的文档元数据
  └─ 按需导出临床 JSON
             │
             ▼
      DocPaw-OFDH SDK / CLI
      字段校验 → 转换与封装 → 待签文件
             │
             ▼
      院方 CA / 签章平台
             │
             ▼
      签后检查 → 原系统归档与阅读
```

工具可以嵌入 Java 服务，也可以由 .NET 等系统启动独立进程。已有系统依赖较旧、难以隔离 Jackson 或密码库时，可优先选择 CLI。项目不要求接入方新增数据库或部署 Web 平台。

## 先跑通第一份文件

### 获取与构建

从 [Releases](https://github.com/bushizhuan/DocPaw-OFDH/releases) 获取预览包，或使用 JDK 21、Maven 3.9 从源码构建：

```sh
git clone https://github.com/bushizhuan/DocPaw-OFDH.git
cd DocPaw-OFDH
mvn -B -ntp clean install
```

CLI 包位于 `ofdh-cli/target/ofdh-0.2.0.zip`。运行时支持 Java 8、17、21；SDK 使用 Java 8 字节码。SDK 当前通过源码安装或组织内部 Maven 仓库分发。

### 使用 CLI

解压后，Windows 使用 `bin/ofdh.cmd`，Linux 使用 `bin/ofdh`。下文以 `ofdh` 表示启动脚本：

```sh
ofdh validate-request --request request.json
ofdh prepare --request request.json --input-root input --out output/record.ofd --report output/prepared-report.json
ofdh check --input output/record.ofd --profile PREPARED --report output/check-report.json
```

先创建输出目录，输出文件名必须尚不存在。完整请求模板、文件目录和 Windows/Linux 命令见 [快速开始](docs/quickstart.md)。

`prepare` 生成待签文件；`check` 返回 `3` 表示检查范围仍为 `INCOMPLETE`，应查看报告中的未检查项。它与输入损坏或已执行规则失败的退出码 `2` 有不同含义。

### 使用 Java SDK

完成根目录的 `mvn install` 后，在宿主工程引用：

```xml
<dependency>
  <groupId>org.openofdh</groupId>
  <artifactId>ofdh-pdf</artifactId>
  <version>0.2.0</version>
</dependency>
```

```java
import java.nio.file.Paths;
import org.openofdh.core.api.Ofdh;
import org.openofdh.core.prepare.PreparedDocument;
import org.openofdh.pdf.PdfSourceAdapter;

Ofdh ofdh = Ofdh.builder()
        .sourceAdapter(new PdfSourceAdapter())
        .build();

PreparedDocument prepared = ofdh.prepare(
        Paths.get("request.json"),
        Paths.get("input"),
        Paths.get("output/record.ofd"));
```

仅接收 OFD 时可改用 `ofdh-core` 和 `Ofdh.create()`。请求也可用 `DocumentRequest.builder()` 直接构建；`validate`、`validateClinical`、`prepare`、`sign` 和 `check` 共用同一 SDK 入口。

[独立 Java 接入工程](examples/java-sdk/README.md) 可以单独编译、打包并通过 `java -jar` 运行，包含实际文件处理、报告落盘和失败状态处理。

## 临床数据、字段校验与 CA

- **临床数据**：内置通用封套、入院记录、检验报告三类 JSON Schema，也支持注册本地 Draft 2020-12 Schema。页面上只有诊断名称时，可在机读数据中同时携带来源系统提供的名称和编码。见 [临床 Schema](docs/clinical-schemas.md)。
- **字段错误**：例如时间缺少时区，报告会定位到对应字段并说明约束；错误列表不回显字段原值。见 [SDK 与字段校验](docs/sdk.md)。
- **CA 接入**：`SignProvider` 接收和返回完整 OFD；`Sm2SignProvider.forMessage` / `forDigest` 连接底层签名服务，明确区分原文与预摘要，以及 DER 与原始 `r‖s`。见 [CA 接入](docs/ca-integration.md)。

## 采用前需要确认的范围

PDF 转换目前面向带嵌入 TrueType 字体的静态可检索文件；扫描件、加密或已签 PDF、ActualText、CFF/Type3、竖排文字及部分复杂图形会明确拒绝。原生 OFD 接收未签署、未加密的单文档 OFD 1.0。

页面顺序优化使用普通 OFD/ZIP，将页面及依赖资源前置；完整线性化协议、独立视觉等价、完整临床语义、吊销状态、可信时间和长期保存需要外部证据。生成成功和签名值正确均不能代替完整标准或医院业务验收。具体见 [能力与兼容性](docs/implementation-status.md)及[标准检查覆盖](docs/standard-rule-matrix.md)。

默认限额为单文件 100 MiB、展开 1 GiB、10,000 个成员、500 页及临床 JSON 10 MiB。输出在支持硬链接的本地文件系统发布；文档和报告分别落盘。宿主应按返回阶段处理重试和归档，详见 [错误码](docs/errors.md)。

## 文档与维护

- [文档导航](docs/README.md)：按首次接入、业务集成和维护查找说明。
- [问题反馈](https://github.com/bushizhuan/DocPaw-OFDH/issues/new/choose)、[参与开发](CONTRIBUTING.md)、[更新记录](CHANGELOG.md)。
- [安全反馈](SECURITY.md)、[第三方许可](THIRD-PARTY.md)、[Apache-2.0](LICENSE)。

Java 包与 Maven 组织坐标使用 `org.openofdh`，CLI 命令为 `ofdh`。现有元数据键、Schema URN 和规则集标识保持兼容。
