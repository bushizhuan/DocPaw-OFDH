package org.openofdh.core.sign;

import org.bouncycastle.asn1.gm.GMNamedCurves;
import org.bouncycastle.asn1.gm.GMObjectIdentifiers;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.crypto.digests.SM3Digest;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.util.PublicKeyFactory;
import org.bouncycastle.jcajce.spec.SM2ParameterSpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.math.ec.ECPoint;
import org.openofdh.core.api.OfdhException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Set;

/** 固定 SM2 剖面的证书、输入及签名检查；不建立机构信任或签署授权。 */
final class Sm2Support {
    private static final BouncyCastleProvider CRYPTO = new BouncyCastleProvider();
    private static final byte[] USER_ID = "1234567812345678".getBytes(StandardCharsets.US_ASCII);
    private static final X9ECParameters CURVE =
            GMNamedCurves.getByOID(GMObjectIdentifiers.sm2p256v1);
    private static final int MAX_CHAIN_BYTES = 1 << 20;

    private Sm2Support() {}

    static X509Certificate[] certificates(X509Certificate[] chain) throws IOException {
        if (chain == null || chain.length == 0 || chain.length > 16) {
            throw configuration("签署身份必须包含 1 至 16 张 X.509 证书");
        }
        X509Certificate[] supplied = chain.clone();
        X509Certificate[] frozen = new X509Certificate[supplied.length];
        int bytes = 0;
        Set<X509Certificate> unique = new HashSet<>();
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509", CRYPTO);
            for (int i = 0; i < supplied.length; i++) {
                if (supplied[i] == null) throw configuration("证书链不能包含空证书");
                byte[] encoded = supplied[i].getEncoded();
                if (encoded == null
                        || encoded.length == 0
                        || encoded.length > MAX_CHAIN_BYTES - bytes) {
                    throw configuration("证书链的 DER 数据不能超过 1 MiB");
                }
                bytes += encoded.length;
                ByteArrayInputStream input = new ByteArrayInputStream(encoded);
                frozen[i] = (X509Certificate) factory.generateCertificate(input);
                if (input.available() != 0 || !unique.add(frozen[i])) {
                    throw configuration("证书链包含尾随数据或重复证书");
                }
            }
            requireSm2(frozen[0]);
            frozen[0].checkValidity();
            boolean[] usage = frozen[0].getKeyUsage();
            if (usage != null
                    && (usage.length == 0 || !usage[0])
                    && (usage.length < 2 || !usage[1])) {
                throw configuration("签署证书用途不允许文档签名");
            }
            for (int i = 1; i < frozen.length; i++) {
                if (!frozen[i - 1]
                        .getIssuerX500Principal()
                        .equals(frozen[i].getSubjectX500Principal())) {
                    throw configuration("证书链须按签署者到颁发者的顺序排列");
                }
                frozen[i - 1].verify(frozen[i].getPublicKey(), CRYPTO);
            }
            return frozen;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new OfdhException("SIGNING_CONFIGURATION", "无法加载有效的签署证书链", e);
        }
    }

    static Sm2SigningRequest request(
            X509Certificate leaf, Sm2SigningRequest.InputType type, byte[] message)
            throws IOException {
        if (leaf == null || type == null || message == null || message.length != 32) {
            throw configuration("SM2 待签请求必须包含证书、输入模式和 32 字节文档签名描述摘要");
        }
        byte[] data = message.clone();
        if (type == Sm2SigningRequest.InputType.SM2_DIGEST) {
            data = digest(leaf, data);
        }
        return new Sm2SigningRequest(type, data, USER_ID, leaf);
    }

    static void verify(X509Certificate leaf, byte[] message, Sm2Signature value)
            throws IOException {
        if (value == null) {
            throw new OfdhException("SIGNATURE_VALUE_INVALID", "签署适配器未返回 SM2 签名值");
        }
        try {
            Signature verifier = Signature.getInstance("SM3withSM2", CRYPTO);
            verifier.setParameter(new SM2ParameterSpec(USER_ID));
            verifier.initVerify(leaf.getPublicKey());
            verifier.update(message);
            if (!verifier.verify(value.getDerEncoded())) {
                throw new OfdhException("SIGNATURE_VERIFICATION_FAILED", "签名值与本次待签数据、用户标识或证书不匹配");
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new OfdhException("SIGNATURE_VERIFICATION_FAILED", "无法验证签署适配器返回的 SM2 签名值", e);
        }
    }

    private static byte[] digest(X509Certificate certificate, byte[] message) throws IOException {
        ECPublicKeyParameters publicKey = requireSm2(certificate);
        ECPoint point = publicKey.getQ().normalize();
        SM3Digest digest = new SM3Digest();
        int bits = USER_ID.length * 8;
        digest.update((byte) (bits >>> 8));
        digest.update((byte) bits);
        update(digest, USER_ID);
        // 各曲线域元素由 BC 编为固定 32 字节，保留前导零；ENTL 使用大端位数。
        update(digest, CURVE.getCurve().getA().getEncoded());
        update(digest, CURVE.getCurve().getB().getEncoded());
        update(digest, CURVE.getG().normalize().getAffineXCoord().getEncoded());
        update(digest, CURVE.getG().normalize().getAffineYCoord().getEncoded());
        update(digest, point.getAffineXCoord().getEncoded());
        update(digest, point.getAffineYCoord().getEncoded());
        byte[] za = finish(digest);
        update(digest, za);
        update(digest, message);
        return finish(digest);
    }

    private static ECPublicKeyParameters requireSm2(X509Certificate certificate)
            throws IOException {
        try {
            SubjectPublicKeyInfo publicInfo =
                    SubjectPublicKeyInfo.getInstance(certificate.getPublicKey().getEncoded());
            if (!X9ObjectIdentifiers.id_ecPublicKey.equals(publicInfo.getAlgorithm().getAlgorithm())
                    || !GMObjectIdentifiers.sm2p256v1.equals(
                            publicInfo.getAlgorithm().getParameters())) {
                throw new OfdhException(
                        "SIGNING_ALGORITHM_UNSUPPORTED", "签署证书必须使用命名曲线 sm2p256v1 的 SM2 公钥");
            }
            return (ECPublicKeyParameters) PublicKeyFactory.createKey(publicInfo);
        } catch (OfdhException e) {
            throw e;
        } catch (IOException | IllegalArgumentException | ClassCastException e) {
            throw new OfdhException("SIGNING_CONFIGURATION", "无法解析签署证书的 SM2 公钥", e);
        }
    }

    private static void update(SM3Digest digest, byte[] bytes) {
        digest.update(bytes, 0, bytes.length);
    }

    private static byte[] finish(SM3Digest digest) {
        byte[] bytes = new byte[digest.getDigestSize()];
        digest.doFinal(bytes, 0);
        return bytes;
    }

    private static OfdhException configuration(String message) {
        return new OfdhException("SIGNING_CONFIGURATION", message);
    }
}
