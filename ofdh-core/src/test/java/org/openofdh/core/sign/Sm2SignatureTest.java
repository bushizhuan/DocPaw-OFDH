package org.openofdh.core.sign;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.gm.GMNamedCurves;
import org.bouncycastle.asn1.gm.GMObjectIdentifiers;
import org.bouncycastle.util.BigIntegers;
import org.junit.jupiter.api.Test;
import org.openofdh.core.api.OfdhException;

import java.math.BigInteger;
import java.util.Arrays;

class Sm2SignatureTest {
    private static final BigInteger ORDER =
            GMNamedCurves.getByOID(GMObjectIdentifiers.sm2p256v1).getN();

    @Test
    void preservesUnsignedBoundaryValuesAndDoesNotExposeInternalBytes() throws Exception {
        byte[] raw = raw(BigInteger.ONE, ORDER.subtract(BigInteger.ONE));
        byte[] expected = der(BigInteger.ONE, ORDER.subtract(BigInteger.ONE));
        Sm2Signature value = Sm2Signature.raw(raw);
        Arrays.fill(raw, (byte) 0);
        assertArrayEquals(expected, value.getDerEncoded());
        byte[] returned = value.getDerEncoded();
        Arrays.fill(returned, (byte) 0);
        assertArrayEquals(expected, value.getDerEncoded());

        byte[] input = expected.clone();
        Sm2Signature decoded = Sm2Signature.der(input);
        Arrays.fill(input, (byte) 0);
        assertArrayEquals(expected, decoded.getDerEncoded());
    }

    @Test
    void rejectsInvalidScalarRangesInBothEncodings() throws Exception {
        for (BigInteger[] pair :
                new BigInteger[][] {
                    {BigInteger.ZERO, BigInteger.ONE},
                    {BigInteger.ONE, BigInteger.ZERO},
                    {ORDER, BigInteger.ONE},
                    {BigInteger.ONE, ORDER}
                }) {
            assertInvalid(() -> Sm2Signature.raw(raw(pair[0], pair[1])));
            assertInvalid(() -> Sm2Signature.der(der(pair[0], pair[1])));
        }
        assertInvalid(() -> Sm2Signature.der(der(BigInteger.valueOf(-1), BigInteger.ONE)));
    }

    @Test
    void rejectsWrongLengthsTrailingBytesAndNoncanonicalDer() throws Exception {
        assertInvalid(() -> Sm2Signature.raw(null));
        assertInvalid(() -> Sm2Signature.der(null));
        for (int length : new int[] {0, 63, 65, 73}) {
            assertInvalid(() -> Sm2Signature.raw(new byte[length]));
        }
        assertInvalid(() -> Sm2Signature.der(new byte[73]));
        byte[] minimal = der(BigInteger.ONE, BigInteger.ONE);
        assertInvalid(() -> Sm2Signature.der(Arrays.copyOf(minimal, minimal.length + 1)));
        // 数值为 1，但通过冗余前导零、长长度或 BER 不定长表示，均不能当成规范 DER。
        assertInvalid(() -> Sm2Signature.der(new byte[] {48, 7, 2, 2, 0, 1, 2, 1, 1}));
        assertInvalid(() -> Sm2Signature.der(new byte[] {48, (byte) 129, 6, 2, 1, 1, 2, 1, 1}));
        assertInvalid(() -> Sm2Signature.der(new byte[] {48, (byte) 128, 2, 1, 1, 2, 1, 1, 0, 0}));
    }

    @Test
    void rejectsOtherAsn1StructuresInsteadOfGuessingTheEncoding() throws Exception {
        byte[] extra =
                new DERSequence(
                                new ASN1Encodable[] {
                                    new ASN1Integer(1), new ASN1Integer(1), new ASN1Integer(1)
                                })
                        .getEncoded();
        assertInvalid(() -> Sm2Signature.der(extra));
        byte[] wrongType =
                new DERSequence(
                                new ASN1Encodable[] {
                                    new DEROctetString(new byte[] {1}), new ASN1Integer(1)
                                })
                        .getEncoded();
        assertInvalid(() -> Sm2Signature.der(wrongType));
        assertInvalid(() -> Sm2Signature.der(raw(BigInteger.ONE, BigInteger.ONE)));
        assertInvalid(() -> Sm2Signature.raw(der(BigInteger.ONE, BigInteger.ONE)));
    }

    private static void assertInvalid(org.junit.jupiter.api.function.Executable action) {
        assertEquals(
                "SIGNATURE_VALUE_INVALID", assertThrows(OfdhException.class, action).getCode());
    }

    private static byte[] der(BigInteger r, BigInteger s) throws java.io.IOException {
        return new DERSequence(new ASN1Encodable[] {new ASN1Integer(r), new ASN1Integer(s)})
                .getEncoded("DER");
    }

    private static byte[] raw(BigInteger r, BigInteger s) {
        byte[] value = new byte[64];
        System.arraycopy(BigIntegers.asUnsignedByteArray(32, r), 0, value, 0, 32);
        System.arraycopy(BigIntegers.asUnsignedByteArray(32, s), 0, value, 32, 32);
        return value;
    }
}
