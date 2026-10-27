package org.openofdh.core.validation;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 显式选用的临床数据封套；不将同名字段的业务含义套用于第三方规范。 */
public final class ClinicalEnvelopeProfile {
    public static final String ID = "urn:openofdh:clinical-envelope:0.1.0";
    private static final List<String> REQUIRED_TEXT =
            Collections.unmodifiableList(
                    Arrays.asList(
                            "/head/schemaId",
                            "/head/version",
                            "/head/documentVersion",
                            "/head/createdAt",
                            "/head/modifiedAt",
                            "/identifier/documentId",
                            "/identifier/documentType/code",
                            "/identifier/documentType/scheme",
                            "/identifier/confidentiality/code",
                            "/identifier/confidentiality/scheme",
                            "/patient/id",
                            "/patient/authority",
                            "/meta/creator/id",
                            "/meta/creator/name",
                            "/meta/creator/authority",
                            "/meta/properties/sourceSystem",
                            "/meta/properties/sourceRecordId"));

    private ClinicalEnvelopeProfile() {}

    public static List<String> requiredTextFields() {
        return REQUIRED_TEXT;
    }
}
