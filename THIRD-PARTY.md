# 第三方组件与许可

发行包 `lib/` 保留 Maven Central 下载的独立 JAR，原包中的 LICENSE、NOTICE 与签名资源不剥离。未在 JAR 中附带许可的组件，其固定版本许可补充在 [licenses](licenses/README.md)。准确组件版本、依赖关系和摘要由构建生成的 `sbom/bom.json`、`sbom/bom.xml` 记录。SBOM 中的许可名称来自依赖元数据，不能替代各组件原文。

本项目的运行 JAR 同时包含 `META-INF/licenses/` 补充许可及 `META-INF/THIRD-PARTY.md`，使通过 Maven 引入 SDK 的接入方也能取得这些原文。独立接入工程在自己的 JAR 中保留 Apache-2.0 许可，运行依赖保持独立 JAR。

| 组件 | 固定版本 | 许可 |
|---|---|---|
| OFDRW reader/core/pkg/gv/gm/sign/graphics2d | 2.4.0 | Apache-2.0 |
| PDFBox/pdfbox-io/fontbox | 3.0.8 | Apache-2.0 |
| jbig2-imageio | 3.0.3 | Apache-2.0 |
| Jackson core/databind/dataformat-yaml | 2.22.4 | Apache-2.0 |
| Jackson annotations | 2.22 | Apache-2.0 |
| networknt json-schema-validator | 2.0.8 | Apache-2.0 |
| itu | 1.14.0 | Apache-2.0 |
| SnakeYAML | 2.5 | Apache-2.0 |
| picocli | 4.7.7 | Apache-2.0 |
| Bouncy Castle bcprov/bcpkix/bcutil jdk18on | 1.86 | Bouncy Castle Licence |
| zip4j | 2.11.3 | Apache-2.0 |
| commons-io | 2.22.0 | Apache-2.0 |
| commons-compress | 1.28.0 | Apache-2.0 |
| commons-codec | 1.19.0 | Apache-2.0 |
| commons-lang3 | 3.18.0 | Apache-2.0 |
| commons-logging | 1.4.0 | Apache-2.0 |
| dom4j | 2.1.4 | BSD 风格，见 licenses 中原文 |
| jaxen | 1.2.0 | BSD 风格，见 JAR 中 LICENSE |
| slf4j-api/slf4j-nop | 2.0.17 | MIT |

运行依赖未引入 iText、OFDRW converter/full。PDF 导入由本项目的 PDFBox 与 OFDRW 图形适配层实现。CLI 使用 slf4j-nop，SDK 不强制宿主日志实现。

测试字体 `ofdh-pdf/src/test/resources/fonts/validation-sans.ttf` 是 Noto Sans SC 的子集并已改名，按 SIL Open Font License 1.1 分发。来源摘要、裁剪方法和许可位于同目录；字体只进入测试资源，不进入应用 JAR 或二进制发行包。用户文书中嵌入的字体不属于本项目授权范围。

构建与测试工具包括 Maven 插件、JUnit 5.12.2 和 CycloneDX Maven Plugin 2.9.1。它们不进入 CLI 运行包。构建 JDK、运行 JDK、标准全文及医院提供的签署身份均不随包分发。

依赖版本、许可和来源在每次发布前重新核对。项目自有代码使用 Apache-2.0，不改变第三方组件、字体或接入方文书的授权条件。贡献材料的来源要求见 [贡献流程](CONTRIBUTING.md)，首次公开的权属与发布核对见 [发布说明](docs/publishing.md)。
