package org.openofdh.core.sign;

import org.bouncycastle.asn1.gm.GMObjectIdentifiers;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.jcajce.spec.SM2ParameterSpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.openofdh.core.api.OfdhException;
import org.openofdh.core.api.ResourceLimits;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

/** 使用调用方提供的 PKCS#12 中的 SM2 密钥；OFD 封装复用底层签名适配流程。 */
public final class Pkcs12SignProvider implements SignProvider {
    private final Sm2SignProvider provider;

    private Pkcs12SignProvider(PrivateKey key, X509Certificate[] certificateChain)
            throws IOException {
        provider =
                Sm2SignProvider.forMessage(
                        certificateChain,
                        request -> {
                            try {
                                Signature signer =
                                        Signature.getInstance(
                                                "SM3withSM2", new BouncyCastleProvider());
                                signer.setParameter(new SM2ParameterSpec(request.getUserId()));
                                signer.initSign(key);
                                signer.update(request.getData());
                                return Sm2Signature.der(signer.sign());
                            } catch (GeneralSecurityException e) {
                                throw new OfdhException("SIGNING_FAILED", "SM2 签名计算失败", e);
                            }
                        });
    }

    public static Pkcs12SignProvider load(Path file, char[] password, String alias)
            throws IOException {
        if (password == null || alias == null || alias.trim().isEmpty()) {
            throw new OfdhException("SIGNING_CONFIGURATION", "必须提供密钥库口令和密钥别名");
        }
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) > (1L << 20)) {
            throw new OfdhException("SIGNING_CONFIGURATION", "密钥库必须是大小不超过 1 MiB 的普通文件");
        }
        BouncyCastleProvider provider = new BouncyCastleProvider();
        try (InputStream input = Files.newInputStream(file)) {
            KeyStore store = KeyStore.getInstance("PKCS12", provider);
            store.load(input, password);
            Key candidate = store.getKey(alias, password);
            Certificate cert = store.getCertificate(alias);
            if (!(candidate instanceof PrivateKey) || !(cert instanceof X509Certificate)) {
                throw new OfdhException("SIGNING_CONFIGURATION", "别名必须对应私钥及 X.509 证书");
            }
            X509Certificate x509 = (X509Certificate) cert;
            SubjectPublicKeyInfo publicInfo =
                    SubjectPublicKeyInfo.getInstance(x509.getPublicKey().getEncoded());
            if (!GMObjectIdentifiers.sm2p256v1.equals(publicInfo.getAlgorithm().getParameters())) {
                throw new OfdhException(
                        "SIGNING_ALGORITHM_UNSUPPORTED", "需要使用 sm2p256v1 曲线的 SM2 证书");
            }
            x509.checkValidity();
            boolean[] usage = x509.getKeyUsage();
            if (usage != null && !usage[0] && (usage.length < 2 || !usage[1])) {
                throw new OfdhException("SIGNING_CONFIGURATION", "证书用途不允许文档签名");
            }
            byte[] challenge = new byte[32];
            new java.security.SecureRandom().nextBytes(challenge);
            Signature signer = Signature.getInstance("SM3withSM2", provider);
            signer.initSign((PrivateKey) candidate);
            signer.update(challenge);
            byte[] signed = signer.sign();
            signer.initVerify(x509.getPublicKey());
            signer.update(challenge);
            if (!signer.verify(signed)) {
                throw new OfdhException("SIGNING_CONFIGURATION", "私钥与证书不匹配");
            }
            Certificate[] chain = store.getCertificateChain(alias);
            if (chain == null || chain.length == 0 || chain.length > 16) {
                throw new OfdhException("SIGNING_CONFIGURATION", "签署身份必须包含长度在限额内的证书链");
            }
            X509Certificate[] certificates = new X509Certificate[chain.length];
            for (int i = 0; i < chain.length; i++) {
                if (!(chain[i] instanceof X509Certificate)) {
                    throw new OfdhException("SIGNING_CONFIGURATION", "证书链中的证书必须采用 X.509 格式");
                }
                certificates[i] = (X509Certificate) chain[i];
                if (i > 0) {
                    if (!certificates[i - 1]
                            .getIssuerX500Principal()
                            .equals(certificates[i].getSubjectX500Principal())) {
                        throw new OfdhException("SIGNING_CONFIGURATION", "证书链未按签署者到颁发者的顺序排列");
                    }
                    certificates[i - 1].verify(certificates[i].getPublicKey(), provider);
                }
            }
            if (!x509.equals(certificates[0])) {
                throw new OfdhException("SIGNING_CONFIGURATION", "证书链未以签署证书开头");
            }
            return new Pkcs12SignProvider((PrivateKey) candidate, certificates);
        } catch (GeneralSecurityException e) {
            throw new OfdhException("SIGNING_CONFIGURATION", "无法加载有效的签署身份", e);
        }
    }

    @Override
    public void sign(Path preparedFile, Path signedFile, ResourceLimits limits) throws IOException {
        provider.sign(preparedFile, signedFile, limits);
    }
}
