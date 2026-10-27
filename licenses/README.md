# 随包分发的第三方许可

本目录补充运行依赖 JAR 中缺少的许可文件。许可及声明均从下列上游版本对应的提交下载，保留原始字节、版权文字和文件名；没有翻译、改写或使用本项目的 Apache 许可代替。下载核对日期为 2026-10-23，文件摘要见 [SHA256SUMS](SHA256SUMS)。

发行包仍保留各依赖原始 JAR 及其中已有的许可资源。组件清单见 [THIRD-PARTY.md](../THIRD-PARTY.md)，完整依赖版本以随包 SBOM 为准。

## 版本与适用范围

| 组件 | 上游版本标记 | 对应提交 | 本目录 |
|---|---|---|---|
| OFDRW 2.4.0 | [2.4.0](https://github.com/ofdrw/ofdrw/tree/2.4.0) | `7459e35082170061efa6b399a6518dbc219f08ac` | [ofdrw-2.4.0](ofdrw-2.4.0/) |
| dom4j 2.1.4 | [version-2.1.4](https://github.com/dom4j/dom4j/tree/version-2.1.4) | `8db3742e13860c6767971867458073e8dd0fa1d1` | [dom4j-2.1.4](dom4j-2.1.4/) |
| networknt json-schema-validator 2.0.8 | [2.0.8](https://github.com/networknt/json-schema-validator/tree/2.0.8) | `0913d1285d136b31d596d9294590d8ed464d0f8b` | [json-schema-validator-2.0.8](json-schema-validator-2.0.8/) |
| picocli 4.7.7 | [v4.7.7](https://github.com/remkop/picocli/tree/v4.7.7) | `5fcd4415a2cf834a12b4cb1e262a007beaa6b4af` | [picocli-4.7.7](picocli-4.7.7/) |
| itu 1.14.0 | [v1.14.0](https://github.com/ethlo/itu/tree/v1.14.0) | `f5fbc9ec887dfbb46ec59407bc437e296ba381e4` | [itu-1.14.0](itu-1.14.0/) |
| SnakeYAML 2.5 | [snakeyaml-2.5](https://bitbucket.org/snakeyaml/snakeyaml/src/snakeyaml-2.5/) | `225cf7b0166c7d8b25ed0000d03f2a02105347ee` | [snakeyaml-2.5](snakeyaml-2.5/) |
| zip4j 2.11.3 | [v2.11.3](https://github.com/srikanth-lingala/zip4j/tree/v2.11.3) | `7b9a50050087fcba8e71ded5d7390cdcd4f284cd` | [zip4j-2.11.3](zip4j-2.11.3/) |

OFDRW 的仓库根许可覆盖发行包中的 `ofdrw-reader`、`ofdrw-core`、`ofdrw-pkg`、`ofdrw-gv`、`ofdrw-gm`、`ofdrw-sign` 和 `ofdrw-graphics2d`，均为 2.4.0；该版本源码树没有另设模块许可或 NOTICE。

dom4j 使用上游自己的 BSD 风格许可，Maven 元数据将其标为 Plexus。这里完整保留 MetaStuff, Ltd. 与 DOM4J contributors 的版权声明及全部条件。

networknt 的 NOTICE 同时保留其版权声明和 Apache Commons Validator 的归属说明。其 `src/test/suite/LICENSE` 对应上游测试套件；本项目没有分发该测试套件，因此没有将这份测试专用许可纳入运行包补充文件。

picocli 的 `NOTICE-DOCS` 明确限定为上游源码发行包中的 OpenJDK 文档，使用 GPL v2 with Classpath Exception，并明确说明这些文档不进入其二进制发行包。这里保留该原始文件供来源核对；本项目分发的 picocli 运行 JAR 使用 Apache-2.0，没有引入这些文档。

已检查上述固定版本的源码文件列表。除 networknt 的 NOTICE 和 picocli 的 NOTICE-DOCS 外，未发现这些组件的独立 NOTICE 文件，因此没有创建占位或自行编写的第三方 NOTICE。所有文件均来自表中固定版本，没有使用默认分支替代。

## 原文件来源

以下链接固定到具体提交；本地文件与下载响应逐字节一致。

| 本地文件 | 上游原文 |
|---|---|
| [ofdrw-2.4.0/LICENSE](ofdrw-2.4.0/LICENSE) | [LICENSE](https://raw.githubusercontent.com/ofdrw/ofdrw/7459e35082170061efa6b399a6518dbc219f08ac/LICENSE) |
| [dom4j-2.1.4/LICENSE](dom4j-2.1.4/LICENSE) | [LICENSE](https://raw.githubusercontent.com/dom4j/dom4j/8db3742e13860c6767971867458073e8dd0fa1d1/LICENSE) |
| [json-schema-validator-2.0.8/LICENSE](json-schema-validator-2.0.8/LICENSE) | [LICENSE](https://raw.githubusercontent.com/networknt/json-schema-validator/0913d1285d136b31d596d9294590d8ed464d0f8b/LICENSE) |
| [json-schema-validator-2.0.8/NOTICE](json-schema-validator-2.0.8/NOTICE) | [NOTICE](https://raw.githubusercontent.com/networknt/json-schema-validator/0913d1285d136b31d596d9294590d8ed464d0f8b/NOTICE) |
| [picocli-4.7.7/LICENSE](picocli-4.7.7/LICENSE) | [LICENSE](https://raw.githubusercontent.com/remkop/picocli/5fcd4415a2cf834a12b4cb1e262a007beaa6b4af/LICENSE) |
| [picocli-4.7.7/NOTICE-DOCS](picocli-4.7.7/NOTICE-DOCS) | [NOTICE-DOCS](https://raw.githubusercontent.com/remkop/picocli/5fcd4415a2cf834a12b4cb1e262a007beaa6b4af/NOTICE-DOCS) |
| [itu-1.14.0/LICENSE](itu-1.14.0/LICENSE) | [LICENSE](https://raw.githubusercontent.com/ethlo/itu/f5fbc9ec887dfbb46ec59407bc437e296ba381e4/LICENSE) |
| [snakeyaml-2.5/LICENSE.txt](snakeyaml-2.5/LICENSE.txt) | [LICENSE.txt](https://api.bitbucket.org/2.0/repositories/snakeyaml/snakeyaml/src/225cf7b0166c7d8b25ed0000d03f2a02105347ee/LICENSE.txt) |
| [zip4j-2.11.3/LICENSE](zip4j-2.11.3/LICENSE) | [LICENSE](https://raw.githubusercontent.com/srikanth-lingala/zip4j/7b9a50050087fcba8e71ded5d7390cdcd4f284cd/LICENSE) |
