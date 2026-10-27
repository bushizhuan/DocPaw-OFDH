package org.openofdh.core.sign;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.gm.GMObjectIdentifiers;
import org.bouncycastle.jcajce.provider.digest.SM3;
import org.ofdrw.core.signatures.SigType;
import org.ofdrw.gm.sm2strut.ContentInfo;
import org.ofdrw.gm.sm2strut.OIDs;
import org.ofdrw.gm.sm2strut.SignedData;
import org.ofdrw.gm.sm2strut.builder.SignedDataBuilder;
import org.ofdrw.reader.OFDReader;
import org.ofdrw.sign.ExtendSignatureContainer;
import org.ofdrw.sign.OFDSigner;
import org.openofdh.core.api.OfdhException;
import org.openofdh.core.api.ResourceLimits;
import org.openofdh.core.io.FileSafety;
import org.openofdh.core.io.SafePackage;
import org.openofdh.core.io.Xml;
import org.w3c.dom.Element;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 将底层 SM2 签名能力接入 OFD 数字签名封装，不要求取得签署私钥。
 *
 * <p>证书链在创建时固定，签署时重新检查有效期。提供者负责 OFD 引用、摘要、签名数据及证书链的封装；
 * 回调负责医生身份选择、授权、传输、超时及取消，不会被自动重试。共享实例时，回调须满足并发安全要求。
 *
 * <p>生成 GB/T 35275 数字签名，不生成可见印章、时间戳或多次追加签名。通过 {@link org.openofdh.core.api.Ofdh#sign(Path, Path,
 * SignProvider)} 使用，可取得最终内容检查报告。
 */
public final class Sm2SignProvider implements SignProvider {
    private final X509Certificate[] certificates;
    private final Sm2Signer signer;
    private final Sm2SigningRequest.InputType inputType;

    private Sm2SignProvider(
            X509Certificate[] certificates, Sm2Signer signer, Sm2SigningRequest.InputType inputType)
            throws IOException {
        this.signer = Objects.requireNonNull(signer, "底层签名实现不能为空");
        this.certificates = Sm2Support.certificates(certificates);
        this.inputType = inputType;
    }

    /**
     * 对接会执行 SM3withSM2 运算的原文签名接口。
     *
     * @param certificateChain 签署者在前、颁发者在后的 X.509 证书链，不自动信任链内证书
     * @param signer 对请求数据执行 SM3withSM2，使用请求中给定的用户标识
     * @return 不含私钥的签署提供者
     * @throws IOException 证书身份、用途、链或算法不合法
     */
    public static Sm2SignProvider forMessage(X509Certificate[] certificateChain, Sm2Signer signer)
            throws IOException {
        return new Sm2SignProvider(certificateChain, signer, Sm2SigningRequest.InputType.MESSAGE);
    }

    /**
     * 对接接受最终 SM2 摘要的接口；设备不得再次计算 SM3 或 ZA。
     *
     * @param certificateChain 签署者在前、颁发者在后的 X.509 证书链
     * @param signer 对请求中的 e = SM3(ZA || M) 执行 SM2 签名运算
     * @return 不含私钥的签署提供者
     * @throws IOException 证书身份、用途、链或算法不合法
     */
    public static Sm2SignProvider forDigest(X509Certificate[] certificateChain, Sm2Signer signer)
            throws IOException {
        return new Sm2SignProvider(
                certificateChain, signer, Sm2SigningRequest.InputType.SM2_DIGEST);
    }

    @Override
    public void sign(Path preparedFile, Path signedFile, ResourceLimits limits) throws IOException {
        Objects.requireNonNull(preparedFile, "待签文件不能为空");
        Objects.requireNonNull(signedFile, "签署输出不能为空");
        Objects.requireNonNull(limits, "资源限额不能为空");
        FileSafety.cancelled();
        X509Certificate[] identity = Sm2Support.certificates(certificates);
        Path target = signedFile.toAbsolutePath().normalize();
        if (target.getParent() == null || target.getFileName() == null) {
            throw new OfdhException("OUTPUT_INVALID", "签署输出必须包含文件名和已存在的父目录");
        }
        Path parent = FileSafety.unlinkedDirectory(target.getParent());
        target = parent.resolve(target.getFileName());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new OfdhException("OUTPUT_EXISTS", "签署输出文件已存在");
        }
        Path work = FileSafety.privateDirectory("ofdh-sm2-");
        Path publication = null;
        try {
            Path snapshot = work.resolve("prepared.ofd");
            FileSafety.snapshot(preparedFile, snapshot, limits.getMaxInputBytes());
            Path result = work.resolve("signed.ofd");
            try (SafePackage archive = SafePackage.open(snapshot, limits)) {
                Element entry = archive.readXml("OFD.xml").getDocumentElement();
                for (Element body : Xml.children(entry, "DocBody")) {
                    if (Xml.child(body, "Signatures") != null) {
                        throw new OfdhException(
                                "SIGNED_INPUT_REQUIRES_DERIVATION", "不支持对已签署文档追加签名");
                    }
                }
                for (String name : archive.entries()) {
                    if (name.toLowerCase(Locale.ROOT).endsWith(".xml")) archive.readXml(name);
                }
                Path root = archive.path("OFD.xml").getParent();
                try (OFDReader reader = new OFDReader(root.toString(), false);
                        OFDSigner documentSigner = new OFDSigner(reader, result)) {
                    documentSigner.setSignContainer(new Container(identity, limits));
                    documentSigner.exeSign();
                }
            } catch (GeneralSecurityException e) {
                throw new OfdhException("SIGNING_FAILED", "无法封装 SM2 数字签名", e);
            }
            FileSafety.cancelled();
            // 上游写入完成后重新应用文件包预算，不能只限制待签输入。
            try (SafePackage checked = SafePackage.open(result, limits)) {
                checked.readXml("OFD.xml");
            }
            publication = parent.resolve(".ofdh-sm2-" + UUID.randomUUID() + ".tmp");
            FileSafety.snapshot(result, publication, limits.getMaxInputBytes());
            FileSafety.deleteTree(work);
            work = null;
            FileSafety.cancelled();
            FileSafety.publish(publication, target);
            publication = null;
        } finally {
            try {
                if (publication != null) Files.deleteIfExists(publication);
            } finally {
                FileSafety.deleteTree(work);
            }
        }
    }

    private final class Container implements ExtendSignatureContainer {
        private final X509Certificate[] identity;
        private final ResourceLimits limits;

        private Container(X509Certificate[] identity, ResourceLimits limits) {
            this.identity = identity;
            this.limits = limits;
        }

        @Override
        public MessageDigest getDigestFnc() {
            return new SM3.Digest();
        }

        @Override
        public ASN1ObjectIdentifier getSignAlgOID() {
            return GMObjectIdentifiers.sm2sign_with_sm3;
        }

        @Override
        public SigType getSignType() {
            return SigType.Sign;
        }

        @Override
        public byte[] getSeal() {
            return null;
        }

        @Override
        public byte[] sign(InputStream data, String propertyInfo)
                throws IOException, GeneralSecurityException {
            MessageDigest digest = getDigestFnc();
            byte[] buffer = new byte[8192];
            long count = 0;
            int read;
            while ((read = data.read(buffer)) != -1) {
                FileSafety.cancelled();
                count += read;
                if (count > Math.min(Xml.MAX_XML_BYTES, limits.getMaxExpandedBytes())) {
                    throw new OfdhException("LIMIT_EXCEEDED", "签名描述超过字节大小限额");
                }
                digest.update(buffer, 0, read);
            }
            // GB/T 35275 容器保存 M；SM2 算法内部的 ZA 和摘要运算属于另一层。
            byte[] message = digest.digest();
            Sm2SigningRequest request = Sm2Support.request(identity[0], inputType, message);
            FileSafety.cancelled();
            Sm2Signature value = signer.sign(request);
            FileSafety.cancelled();
            Sm2Support.verify(identity[0], message, value);
            SignedData signed =
                    SignedDataBuilder.signedData(message, value.getDerEncoded(), identity[0]);
            ASN1EncodableVector chain = new ASN1EncodableVector();
            for (X509Certificate certificate : identity) {
                chain.add(
                        org.bouncycastle.asn1.x509.Certificate.getInstance(
                                certificate.getEncoded()));
            }
            signed.setCertificates(new DERSet(chain));
            return new ContentInfo(OIDs.signedData, signed).getEncoded("DER");
        }
    }
}
