package org.openofdh.core.sign;

import org.openofdh.core.api.OfdhException;
import org.openofdh.core.api.ResourceLimits;
import org.openofdh.core.check.CheckProfile;
import org.openofdh.core.check.CheckReport;
import org.openofdh.core.check.CheckService;
import org.openofdh.core.io.FileSafety;
import org.openofdh.core.io.SafePackage;
import org.openofdh.core.io.Xml;
import org.openofdh.core.layout.PageOrderOptimizer;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/** 签署私有快照，并在发布前验证返回文件的实际字节。 */
public final class SigningService {
    private final ResourceLimits limits;
    private final boolean optimizePageOrder;

    public SigningService(ResourceLimits limits) {
        this(limits, false);
    }

    public SigningService(ResourceLimits limits, boolean optimizePageOrder) {
        this.limits = Objects.requireNonNull(limits, "资源限额不能为空");
        this.optimizePageOrder = optimizePageOrder;
    }

    public SignedDocument sign(Path preparedFile, Path output, SignProvider provider)
            throws IOException {
        Objects.requireNonNull(provider, "签署适配器不能为空");
        Path target = output.toAbsolutePath().normalize();
        Path parent = FileSafety.unlinkedDirectory(target.getParent());
        target = parent.resolve(target.getFileName());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new OfdhException("OUTPUT_EXISTS", "输出文件已存在");
        }
        Path work = FileSafety.privateDirectory("ofdh-sign-");
        Path publication = null;
        try {
            Path snapshot = work.resolve("prepared.ofd");
            String before = FileSafety.snapshot(preparedFile, snapshot, limits.getMaxInputBytes());
            try (SafePackage archive = SafePackage.open(snapshot, limits)) {
                Element entry = archive.readXml("OFD.xml").getDocumentElement();
                for (Element body : Xml.children(entry, "DocBody")) {
                    if (Xml.child(body, "Signatures") != null) {
                        throw new OfdhException(
                                "SIGNED_INPUT_REQUIRES_DERIVATION", "不支持对已签署文档追加签名");
                    }
                }
            }
            CheckService checker = new CheckService(limits);
            CheckReport prepared = checker.check(snapshot, CheckProfile.PREPARED);
            Integer signatureCount = prepared.getSignatureVerification().getSignatureCount();
            if (signatureCount != null && signatureCount > 0) {
                throw new OfdhException("SIGNED_INPUT_REQUIRES_DERIVATION", "不支持对已签署文档追加签名");
            }
            if ("ISSUES_FOUND".equals(prepared.getAssessment())) {
                throw new OfdhException("PREPARED_DOCUMENT_INVALID", "待签署文档未通过签前检查");
            }
            Path signed = Files.createDirectory(work.resolve("provider")).resolve("result.ofd");
            provider.sign(snapshot, signed, limits);
            if (!before.equals(FileSafety.sha256(snapshot))) {
                throw new OfdhException("SIGN_PROVIDER_CHANGED_INPUT", "签署适配器修改了输入文件");
            }
            Path received =
                    Files.createDirectory(work.resolve("received")).resolve(target.getFileName());
            String receivedHash = FileSafety.snapshot(signed, received, limits.getMaxInputBytes());
            // 先核对签署返回，重排不能掩盖签署适配器对原文档的修改。
            requireSameContent(snapshot, received);
            Path frozen = received;
            String frozenHash = receivedHash;
            if (optimizePageOrder) {
                frozen =
                        Files.createDirectory(work.resolve("verified"))
                                .resolve(target.getFileName());
                try (SafePackage archive = SafePackage.open(received, limits)) {
                    PageOrderOptimizer.publish(archive, frozen, limits);
                }
                frozenHash = FileSafety.sha256(frozen);
                requireSameContent(snapshot, frozen);
            }
            CheckReport report = checker.check(frozen, CheckProfile.FINAL);
            if ("ISSUES_FOUND".equals(report.getAssessment())
                    || report.getSignatureVerification().getSignatureCount() == null
                    || report.getSignatureVerification().getSignatureCount() < 1
                    || report.getSignatureVerification().getSignatures().stream()
                            .anyMatch(
                                    signature ->
                                            !"PASS"
                                                    .equals(
                                                            signature
                                                                    .getCryptographicVerification()))) {
                throw new OfdhException("SIGNATURE_VERIFICATION_FAILED", "签署文档未通过最终完整性检查");
            }
            if (!frozenHash.equals(report.getArtifact().getSha256())
                    || !before.equals(FileSafety.sha256(snapshot))) {
                throw new OfdhException("INPUT_CHANGED", "签署输入在验证期间发生变化");
            }
            publication = parent.resolve(".ofdh-sign-" + UUID.randomUUID() + ".tmp");
            String hash = FileSafety.snapshot(frozen, publication, limits.getMaxInputBytes());
            if (!hash.equals(report.getArtifact().getSha256())) {
                throw new OfdhException("INPUT_CHANGED", "待发布文件与已验证文档的字节不一致");
            }
            FileSafety.deleteTree(work);
            work = null;
            FileSafety.publish(publication, target);
            publication = null;
            return new SignedDocument(target, hash, report);
        } finally {
            try {
                if (publication != null) Files.deleteIfExists(publication);
            } finally {
                FileSafety.deleteTree(work);
            }
        }
    }

    private void requireSameContent(Path prepared, Path signed) throws IOException {
        try (SafePackage original = SafePackage.open(prepared, limits);
                SafePackage result = SafePackage.open(signed, limits)) {
            for (String entry : original.entries()) {
                if (!result.contains(entry)) {
                    throw new OfdhException("SIGN_PROVIDER_CHANGED_CONTENT", "签署过程删除了原文档成员");
                }
                if ("OFD.xml".equals(entry)) {
                    org.w3c.dom.Document left = original.readXml(entry);
                    org.w3c.dom.Document right = result.readXml(entry);
                    normalizeEntry(left.getDocumentElement());
                    normalizeEntry(right.getDocumentElement());
                    if (!left.getDocumentElement().isEqualNode(right.getDocumentElement())) {
                        throw new OfdhException("SIGN_PROVIDER_CHANGED_CONTENT", "签署过程修改了文档元数据");
                    }
                } else if (!FileSafety.sha256(original.path(entry))
                        .equals(FileSafety.sha256(result.path(entry)))) {
                    throw new OfdhException("SIGN_PROVIDER_CHANGED_CONTENT", "签署过程修改了原文档内容");
                }
            }
            for (String entry : result.entries()) {
                if (!original.contains(entry) && !entry.startsWith("Doc_0/Signs/")) {
                    throw new OfdhException("SIGN_PROVIDER_CHANGED_CONTENT", "签署过程在签名目录之外新增了成员");
                }
            }
        }
    }

    private static void normalizeEntry(org.w3c.dom.Node parent) {
        for (org.w3c.dom.Node node = parent.getFirstChild(); node != null; ) {
            org.w3c.dom.Node next = node.getNextSibling();
            boolean signature =
                    node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE
                            && "Signatures".equals(node.getLocalName())
                            && org.openofdh.core.io.Xml.NS.equals(node.getNamespaceURI())
                            && "DocBody".equals(parent.getLocalName());
            if (signature
                    || node.getNodeType() == org.w3c.dom.Node.COMMENT_NODE
                    || (node.getNodeType() == org.w3c.dom.Node.TEXT_NODE
                            && node.getNodeValue().trim().isEmpty())) {
                parent.removeChild(node);
            } else {
                normalizeEntry(node);
            }
            node = next;
        }
    }
}
