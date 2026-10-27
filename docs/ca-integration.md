# CA 与底层 SM2 签名接入

本文对应 `ofdh-core` 的签署扩展。医院可以继续使用自己的 CA、签章平台或密码设备；中间件不要求导出其私钥。签署身份、业务授权和服务调用由宿主管理，OFD 签名结构由中间件生成。

## 选择接入层次

| 院方服务能力 | 使用入口 | 接入方负责 |
|---|---|---|
| 接收 OFD，返回完整的已签 OFD | 实现 `SignProvider` | 文件传输、授权及完整签署结果；返回格式须在当前检查范围内 |
| 接收待签消息，执行 SM3withSM2，返回签名值 | `Sm2SignProvider.forMessage(...)` | 将消息、用户标识和已授权身份传给 CA，返回 DER 或原始 `r‖s` |
| 接收已经计算好的 SM2 签名摘要，返回签名值 | `Sm2SignProvider.forDigest(...)` | 调用不再次计算摘要或 ZA 的 SM2 接口，返回 DER 或原始 `r‖s` |
| 授权 SM2 私钥可通过本地 PKCS#12 使用 | `Pkcs12SignProvider.load(...)` | 提供身份文件、口令及别名 |

这两层签署接口都通过 `Ofdh.sign(preparedFile, outputFile, provider)` 使用。现有整文件 `SignProvider` 保留；CLI 的 `sign` 命令目前仍只加载本地 PKCS#12，不自动发现或加载 CA 插件。

## 最小接入

先通过院方已有的证书获取方式取得 `X509Certificate[] certificateChain`，顺序为签署者证书、上级颁发者证书。签署者必须使用 SM2 曲线且证书用途允许签名。中间件固定本次证书材料；CA 必须使用与第一张证书对应的私钥完成本次签名。当前证书链限 1～16 张、DER 总量不超过 1 MiB；检查叶证书有效期及用途、证书重复、链次序和逐级签名，不自动补齐中间证书。

下面的 `caClient` 和 `signingIdentity` 来自宿主系统。`signMessage` 仅表示需要映射到现有 CA 客户端的操作，不是 DocPaw-OFDH 提供的 HTTP 接口或某家 CA 的方法名。接口地址、认证、身份选择、服务超时及返回值解码应在该客户端中处理。

```java
import org.openofdh.core.sign.SignedDocument;
import org.openofdh.core.sign.Sm2SignProvider;
import org.openofdh.core.sign.Sm2Signature;

Sm2SignProvider provider = Sm2SignProvider.forMessage(certificateChain, request -> {
    // 映射到院方客户端的 SM3withSM2 原文模式，显式传入本次签署身份和用户标识。
    byte[] signature = caClient.signMessage(
            signingIdentity, request.getData(), request.getUserId());
    return Sm2Signature.der(signature);
});

SignedDocument signed = ofdh.sign(preparedFile, outputFile, provider);
```

这里只把签名运算交给 CA。中间件生成签名引用、GB/T 35275 签名容器及证书链，校验返回签名，再执行原文内容保护和最终文件检查，通过后发布到新路径。错误密钥、错误数据模式或错误用户标识产生的签名不能作为成功文件发布。

接口本身是同步调用：

```java
Sm2Signature sign(Sm2SigningRequest request) throws IOException;
```

`Sm2Signer` 是函数接口，可以使用上面的 lambda，也可以由宿主实现为普通 Java 类。异步平台须由适配层等到最终结果可用后再返回；中间件不代管轮询、回调任务或授权会话。

## 消息与预计算摘要

当前签名封装使用下面的数据约定。`Signature.xml` 指本次 OFD 内签名描述文件的原始字节，不能重新序列化后再计算。

```text
M = SM3(Signature.xml 的原始字节)
ZA = 按 SM2 规则计算的用户标识、曲线参数和签署公钥摘要
e = SM3(ZA || M)
```

| 工厂方法 | `request.getInputType()` | `request.getData()` | CA 应执行的操作 |
|---|---|---|---|
| `forMessage` | `MESSAGE` | `M` | 按给定用户标识执行 SM3withSM2；算法内部计算 ZA 和最终摘要 |
| `forDigest` | `SM2_DIGEST` | `e` | 直接对已计算的最终摘要执行 SM2 签名运算 |

两种输入都恰好为 32 字节，**不能通过长度判断应选哪种模式**。CA 接口名中出现“摘要签名”也不足以判定：有的接口收到摘要后仍会再执行 SM3withSM2，必须核对其实际运算语义。

当前用户标识固定为 ASCII 字节 `1234567812345678`，通过 `request.getUserId()` 提供。它是 SM2 运算参数，不是患者标识、医生账号或证书序列号。CA 仅支持其他用户标识时，不应强行接入当前封装。

`forMessage` 的消息已经是 OFD 签名描述的 SM3 摘要。此模式应直接将 `getData()` 交给 SM3withSM2 原文接口，不要额外对它求摘要。`forDigest` 已包含 ZA 的影响，不得再追加 ZA、再调用 SM3，或交给会重新摘要的 SM3withSM2 原文接口。

纯摘要接口对应的适配形态如下，`signSm2Digest` 同样需要映射到院方已有客户端：

```java
Sm2SignProvider provider = Sm2SignProvider.forDigest(certificateChain, request -> {
    byte[] signature = caClient.signSm2Digest(signingIdentity, request.getData());
    return Sm2Signature.raw(signature);
});
```

## 返回签名值

| CA 返回的字节格式 | 构造结果 |
|---|---|
| DER 编码的 `SEQUENCE { INTEGER r, INTEGER s }` | `Sm2Signature.der(bytes)` |
| 64 字节的 `r‖s`，每项为 32 字节无符号大端整数 | `Sm2Signature.raw(bytes)` |

格式由适配方明确指定，不进行猜测。工厂方法检查编码及整数取值，并生成规范的 DER 结果。厂商返回 Base64 或十六进制字符串时，先在适配层解码；不能把字符串的 UTF-8 字节直接传入。CMS/PKCS#7、电子印章容器和完整 OFD 文件也不是这里要求的原始签名值。

`Sm2SigningRequest` 提供 `getInputType()`、`getData()`、`getUserId()` 和 `getCertificate()`；其中数组读取返回副本。`Sm2Signature.getDerEncoded()` 也返回副本。接入方应以请求携带的证书核对所选 CA 身份，不能在签名调用中静默切换到其他证书或密钥。

## 授权、失败与并发

选择签署医生、组织证书或签署授权的上下文应保留在适配对象或 lambda 闭包中。文书的责任者字段不自动授予签名权限，也不会自动选择 CA 账号。凭据、授权码和令牌不放入文书请求或机读附件；适配层日志不得直接输出这些内容。

一次底层回调对应一次实际签署请求，中间件不发送额外的签名探测请求，也不自动重试。远端拒绝、超时或结果不确定时，由适配层抛出 `IOException`；业务侧先按 CA 的任务查询能力确认状态，再决定是否重试。不同签署授权下的调用不能只按文件名去重。

宿主应为网络调用设置超时和取消策略。线程中断不能保证停止所有第三方 SDK 或网络操作；适配层应配合取消。同一个提供者可被宿主重复使用，客户端与身份上下文的并发安全由实现方保证；共享实例不应临时改写“当前医生”这类可变全局状态。

通过 `Ofdh.sign` 调用时，已有输出不会被覆盖，受控失败不发布本次新文件。返回 `SignedDocument` 后若宿主写报告或归档失败，已发布的签署文件仍然存在，应保留其摘要并按实际阶段恢复。具体错误码见 [错误处理](errors.md)。

## 信任及联调验收

密码验签成功说明签名值与本次数据、证书公钥一致，不等于医院已授权这次签署。证书链随文件保存，也不会自动成为可信证书。

使用本机构明确配置的根证书单独检查信任：

```java
CheckReport report = ofdh.check(signed.getPath(), CheckProfile.FINAL, trustedCertificates);
```

`trustedCertificates` 是 `Set<X509Certificate>`。当前检查是离线证书链验证，不查询 OCSP/CRL，也不取得可信时间戳。`INCOMPLETE` 仍应保留在报告中，不能因为 CA 返回成功就覆盖未完成的检查项。

接入自测至少应取得以下证据：

1. 使用授权的非生产身份，分别核对 CA 接口的数据模式、用户标识及签名编码，并生成实际已签 OFD。
2. 核对签后文件中的证书链、原文及机读附件保持不变，签名检查通过；再用机构实际使用的独立验签工具验证。
3. 错误身份、错误用户标识、二次摘要、异常返回、超时及取消均按失败处理，源文书保持不变，没有被当作成功发布的文件。
4. 核对医生授权、凭据更新、吊销策略、签署任务查询及归档恢复流程，避免失败后重复扣用授权或重复签署。

内置回归使用临时身份执行真实密码运算，不构成院方 CA 联调或独立产品互操作的证明。底层适配当前生成受支持的 SM2/SM3 数字签名，不包含电子印章外观、其他算法、追加签名或长期验证材料。
