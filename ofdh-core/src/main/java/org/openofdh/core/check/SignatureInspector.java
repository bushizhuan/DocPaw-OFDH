package org.openofdh.core.check;

import static org.openofdh.core.check.Inspection.child;
import static org.openofdh.core.check.Inspection.descendants;
import static org.openofdh.core.check.Inspection.text;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ofdrw.core.signatures.SigType;
import org.ofdrw.gm.sm2strut.ContentInfo;
import org.ofdrw.gm.sm2strut.OIDs;
import org.ofdrw.gm.sm2strut.SignedData;
import org.ofdrw.gm.sm2strut.SignerInfo;
import org.ofdrw.sign.verify.container.GBT35275ValidateContainer;
import org.openofdh.core.io.FileSafety;
import org.openofdh.core.io.Xml;
import org.w3c.dom.Element;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertStore;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class SignatureInspector {
    private static final Provider CRYPTO = new BouncyCastleProvider();
    private static final String SM2_OID = "1.2.156.10197.1.501";
    private final Inspection state;

    SignatureInspector(Inspection state) {
        this.state = state;
    }

    void inspect(Element body) throws IOException {
        state.unknown(Rule.SIGNATURE_TRUST, "证书信任需要显式信任锚；本项不建立签署授权、吊销状态或可信时间证据。", "OFD.xml");
        String reference = text(body, "Signatures");
        if (reference.isEmpty()) {
            state.signatures =
                    new CheckReport.SignatureVerification(
                            "COMPLETE", 0, Collections.<CheckReport.Signature>emptyList());
            if (state.profile == CheckProfile.FINAL) {
                state.fail(Rule.SIGNATURE_REQUIRED, "最终使用阶段要求至少存在一个签名。", "OFD.xml");
            } else {
                state.unknown(Rule.SIGNATURE_REQUIRED, "当前待签署制品尚未完成签署。", "OFD.xml");
            }
            state.absent(Rule.SIGNATURE_REFERENCES, "没有可验证的签名引用。", "OFD.xml");
            state.absent(Rule.CONTENT_COVERAGE, "文档尚未签署。", "OFD.xml");
            state.absent(Rule.SIGNATURE_CRYPTOGRAPHY, "没有可验证的签名值。", "OFD.xml");
            return;
        }
        Set<String> requiredContent = new LinkedHashSet<>(state.pkg.entries());
        Set<String> graphContent = new LinkedHashSet<>(state.contentFiles);
        List<CheckReport.Signature> reports = new ArrayList<>();
        List<Element> signatures;
        String listPath;
        try {
            listPath = state.requireFile("OFD.xml", reference);
            Element list = state.pkg.readXml(listPath).getDocumentElement();
            if (!"Signatures".equals(list.getLocalName()) || !Xml.NS.equals(list.getNamespaceURI()))
                throw new IOException("签名列表不合法。");
            signatures = descendants(list, "Signature");
            Set<String> ids = new HashSet<>();
            Set<String> descriptors = new HashSet<>();
            for (Element signature : signatures) {
                String id = signature.getAttribute("ID");
                if (id.isEmpty() || id.length() > 1024 || !ids.add(id))
                    throw new IOException("签名标识不合法。");
                String descriptor = state.requireFile(listPath, signature.getAttribute("BaseLoc"));
                if (!descriptors.add(descriptor)) throw new IOException("同一签名描述文件不能重复登记。");
            }
            if (signatures.isEmpty()) throw new IOException("签名列表为空。");
        } catch (IOException e) {
            FileSafety.cancelled();
            state.fail(Rule.SIGNATURE_REQUIRED, "签名列表缺失、为空或格式不正确。", "OFD.xml");
            state.signatures = new CheckReport.SignatureVerification("FAILED", null, reports);
            return;
        }

        requiredContent.remove(listPath);
        for (Element registration : signatures) {
            try {
                String descriptor =
                        state.requireFile(listPath, registration.getAttribute("BaseLoc"));
                requiredContent.remove(descriptor);
                Element signature = state.pkg.readXml(descriptor).getDocumentElement();
                requiredContent.remove(
                        state.requireFile(descriptor, text(signature, "SignedValue")));
                Element seal = child(child(signature, "SignedInfo"), "Seal");
                if (seal != null)
                    requiredContent.remove(state.requireFile(descriptor, text(seal, "BaseLoc")));
            } catch (IOException e) {
                FileSafety.cancelled();
                // 后续逐签名检查会记录格式不正确的登记信息。
            }
        }
        requiredContent.addAll(graphContent);
        requiredContent.remove(listPath);

        boolean digestUnknown = false;
        boolean cryptoUnknown = false;
        boolean digestFailure = false;
        boolean cryptoFailure = false;
        boolean oneCompleteCover = false;
        for (Element registration : signatures) {
            FileSafety.cancelled();
            String id = registration.getAttribute("ID");
            String crypto = "NOT_CHECKED";
            String trust = "UNKNOWN";
            String reason = "不支持该签名值格式或算法；尚未确认信任与吊销状态。";
            String signaturePath = listPath;
            try {
                signaturePath = state.requireFile(listPath, registration.getAttribute("BaseLoc"));
                byte[] signatureXml = state.pkg.read(signaturePath);
                Inspection.utf8(signatureXml);
                Element signature = state.pkg.readXml(signaturePath).getDocumentElement();
                if (!"Signature".equals(signature.getLocalName())
                        || !Xml.NS.equals(signature.getNamespaceURI()))
                    throw new IOException("签名根节点不合法。");
                Element signedInfo = child(signature, "SignedInfo");
                Element references = child(signedInfo, "References");
                if (references == null || descendants(references, "Reference").isEmpty())
                    throw new IOException("签名未包含受保护文件的引用。");
                Set<String> protectedFiles = new HashSet<>();
                boolean intact = true;
                boolean unknown = false;
                for (Element item : descendants(references, "Reference")) {
                    String entry = state.requireFile(signaturePath, item.getAttribute("FileRef"));
                    if (!protectedFiles.add(entry)) throw new IOException("受保护文件的签名引用重复。");
                    String expected = text(item, "CheckValue");
                    byte[] expectedBytes =
                            Base64.getDecoder().decode(expected.replaceAll("\\s", ""));
                    try {
                        MessageDigest digest =
                                MessageDigest.getInstance(
                                        references.getAttribute("CheckMethod"), CRYPTO);
                        byte[] actual = digestFile(entry, digest);
                        if (!MessageDigest.isEqual(expectedBytes, actual)) intact = false;
                    } catch (NoSuchAlgorithmException e) {
                        unknown = true;
                    }
                }
                oneCompleteCover |= protectedFiles.containsAll(requiredContent);
                digestUnknown |= unknown;
                digestFailure |= !intact;
                String signedValuePath =
                        state.requireFile(signaturePath, text(signature, "SignedValue"));
                String algorithm = text(signedInfo, "SignatureMethod");
                if (!intact) {
                    crypto = "FAIL";
                    reason = "至少一个受保护文件的摘要与签名引用不一致。";
                } else if ("Sign".equals(registration.getAttribute("Type")) && isSm2(algorithm)) {
                    byte[] signedValue = state.pkg.read(signedValuePath);
                    SignedData signedData = requireSignedData(signedValue);
                    new GBT35275ValidateContainer()
                            .validate(SigType.Sign, algorithm, signatureXml, signedValue);
                    crypto = unknown ? "NOT_CHECKED" : "PASS";
                    trust = certificateTrust(signedData);
                    reason =
                            unknown
                                    ? "签名值验证通过，但存在不支持的引用摘要算法。"
                                    : "受保护文件摘要及 GB/T 35275 SM2 签名值验证通过；吊销状态和时间戳证据尚未检查。";
                }
            } catch (IOException | GeneralSecurityException | RuntimeException e) {
                FileSafety.cancelled();
                crypto = "FAIL";
                reason = "签名结构、受保护内容或签名值验证失败。";
                digestFailure = true;
            }
            if ("FAIL".equals(crypto)) cryptoFailure = true;
            if ("NOT_CHECKED".equals(crypto)) cryptoUnknown = true;
            if ("UNTRUSTED".equals(trust)) {
                state.fail(Rule.SIGNATURE_TRUST, "检查时，某签署者未能通过显式信任锚验证。", signaturePath);
            }
            reports.add(new CheckReport.Signature(id, crypto, trust, "NOT_CHECKED", reason));
        }
        state.signatures =
                new CheckReport.SignatureVerification("COMPLETE", reports.size(), reports);
        state.pass(Rule.SIGNATURE_REQUIRED, "已枚举非空签名列表；签名有效性单独报告。", listPath);
        if (digestFailure) state.fail(Rule.SIGNATURE_REFERENCES, "至少一个签名引用、摘要或签名结构无法验证。", listPath);
        else if (digestUnknown) state.unknown(Rule.SIGNATURE_REFERENCES, "不支持声明的文件摘要算法。", listPath);
        else state.pass(Rule.SIGNATURE_REFERENCES, "全部已枚举的受保护文件摘要均与引用一致。", listPath);
        if (oneCompleteCover)
            state.pass(Rule.CONTENT_COVERAGE, "至少一个签名覆盖全部包成员，仅排除明确枚举的签名控制对象。", listPath);
        else state.fail(Rule.CONTENT_COVERAGE, "没有单个签名覆盖本项目整包保护规则要求的全部内容。", listPath);
        if (cryptoFailure)
            state.fail(Rule.SIGNATURE_CRYPTOGRAPHY, "至少一个已枚举签名未通过密码或结构验证。", listPath);
        else if (cryptoUnknown)
            state.unknown(Rule.SIGNATURE_CRYPTOGRAPHY, "至少一个签名值使用不支持的签名类型、格式或算法。", listPath);
        else
            state.pass(
                    Rule.SIGNATURE_CRYPTOGRAPHY,
                    "全部已枚举的 GB/T 35275 SM2 签名及受保护文件摘要均验证通过。",
                    listPath);
    }

    private byte[] digestFile(String entry, MessageDigest digest) throws IOException {
        try (InputStream input = Files.newInputStream(state.pkg.path(entry))) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) {
                FileSafety.cancelled();
                digest.update(buffer, 0, count);
            }
        }
        return digest.digest();
    }

    private static boolean isSm2(String algorithm) {
        return SM2_OID.equals(algorithm) || "SM3withSM2".equalsIgnoreCase(algorithm);
    }

    private static SignedData requireSignedData(byte[] bytes) throws IOException {
        ContentInfo content = ContentInfo.getInstance(bytes);
        if (content == null || !OIDs.signedData.equals(content.getContentType()))
            throw new IOException("不支持该签名数据。");
        SignedData signed = SignedData.getInstance(content.getContent());
        // 上游验签器可能直接接受空签署者集合，
        // 因此必须先确认存在实际可验签的签署者。
        if (signed == null
                || signed.getSignerInfos() == null
                || signed.getSignerInfos().size() == 0
                || signed.getCertificates() == null
                || signed.getCertificates().size() == 0
                || signed.getContentInfo() == null) throw new IOException("签名数据不完整。");
        for (ASN1Encodable encoded : signed.getSignerInfos()) {
            SignerInfo signer = SignerInfo.getInstance(encoded);
            if (signer == null
                    || signer.getEncryptedDigest() == null
                    || signer.getEncryptedDigest().getOctets().length == 0
                    || signed.getSignCert(signer.getIssuerAngSerialNumber()) == null)
                throw new IOException("签署者信息不完整。");
        }
        return signed;
    }

    private String certificateTrust(SignedData signed)
            throws GeneralSecurityException, IOException {
        if (state.trustRoots.isEmpty()) return "UNKNOWN";
        Set<TrustAnchor> anchors = new HashSet<>();
        for (X509Certificate root : state.trustRoots) anchors.add(new TrustAnchor(root, null));
        CertificateFactory factory = CertificateFactory.getInstance("X.509", CRYPTO);
        List<X509Certificate> certificates = new ArrayList<>();
        for (ASN1Encodable entry : signed.getCertificates()) {
            certificates.add(decodeCertificate(factory, Certificate.getInstance(entry)));
        }
        try {
            for (ASN1Encodable entry : signed.getSignerInfos()) {
                SignerInfo signer = SignerInfo.getInstance(entry);
                X509Certificate leaf =
                        decodeCertificate(
                                factory, signed.getSignCert(signer.getIssuerAngSerialNumber()));
                leaf.checkValidity(new Date());
                boolean[] usage = leaf.getKeyUsage();
                if (usage != null && !usage[0] && (usage.length < 2 || !usage[1]))
                    return "UNTRUSTED";
                if (state.trustRoots.contains(leaf)) continue;
                X509CertSelector selector = new X509CertSelector();
                selector.setCertificate(leaf);
                PKIXBuilderParameters parameters = new PKIXBuilderParameters(anchors, selector);
                parameters.setRevocationEnabled(false);
                parameters.setDate(new Date());
                parameters.addCertStore(
                        CertStore.getInstance(
                                "Collection", new CollectionCertStoreParameters(certificates)));
                CertPathBuilder.getInstance("PKIX", CRYPTO).build(parameters);
            }
            return "TRUSTED";
        } catch (GeneralSecurityException e) {
            return "UNTRUSTED";
        }
    }

    private static X509Certificate decodeCertificate(
            CertificateFactory factory, Certificate certificate)
            throws IOException, GeneralSecurityException {
        return (X509Certificate)
                factory.generateCertificate(new ByteArrayInputStream(certificate.getEncoded()));
    }
}
