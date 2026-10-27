package org.openofdh.core.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.openofdh.core.prepare.PrepareService;
import org.openofdh.core.validation.ClinicalSchemaRegistry;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

class PageSourceAdapterRegistrationTest {
    @Test
    void rejectsUnsupportedFormatsAtBothRegistrationEntrypoints() {
        for (String format : Arrays.asList(null, "", " ", "OFD", "DOCX", "pdf", " PDF", "PDF ")) {
            PageSourceAdapter adapter = declaration(format);
            IllegalArgumentException facade =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> Ofdh.builder().sourceAdapter(adapter));
            IllegalArgumentException service =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    new PrepareService(
                                            Collections.singletonList(adapter),
                                            new ClinicalSchemaRegistry(),
                                            ResourceLimits.defaults()));
            IllegalArgumentException optimized =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    new PrepareService(
                                            Collections.singletonList(adapter),
                                            new ClinicalSchemaRegistry(),
                                            ResourceLimits.defaults(),
                                            true));
            assertEquals(facade.getMessage(), service.getMessage(), format);
            assertEquals(facade.getMessage(), optimized.getMessage(), format);
        }
    }

    @Test
    void acceptsOnePdfAdapterAndKeepsRegistrationAfterDuplicateRejection() throws Exception {
        PageSourceAdapter adapter = declaration("PDF");
        Ofdh.Builder builder = Ofdh.builder().sourceAdapter(adapter);
        IllegalArgumentException facade =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> builder.sourceAdapter(declaration("PDF")));
        IllegalArgumentException service =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new PrepareService(
                                        Arrays.asList(adapter, declaration("PDF")),
                                        new ClinicalSchemaRegistry(),
                                        ResourceLimits.defaults()));
        assertEquals(facade.getMessage(), service.getMessage());
        assertNotNull(builder.build());
        assertNotNull(
                new PrepareService(
                        Collections.singletonList(adapter),
                        new ClinicalSchemaRegistry(),
                        ResourceLimits.defaults()));
        assertNotNull(
                new PrepareService(
                        Collections.singletonList(adapter),
                        new ClinicalSchemaRegistry(),
                        ResourceLimits.defaults(),
                        true));
    }

    @Test
    void rejectsNullAdaptersBeforeAnyFileOperation() {
        NullPointerException facade =
                assertThrows(NullPointerException.class, () -> Ofdh.builder().sourceAdapter(null));
        NullPointerException service =
                assertThrows(
                        NullPointerException.class,
                        () ->
                                new PrepareService(
                                        Collections.singletonList(null),
                                        new ClinicalSchemaRegistry(),
                                        ResourceLimits.defaults()));
        assertEquals(facade.getMessage(), service.getMessage());
        assertEquals("来源适配器不能为空", facade.getMessage());
        assertEquals(
                "来源适配器列表不能为空",
                assertThrows(
                                NullPointerException.class,
                                () ->
                                        new PrepareService(
                                                null,
                                                new ClinicalSchemaRegistry(),
                                                ResourceLimits.defaults()))
                        .getMessage());
    }

    private static PageSourceAdapter declaration(String format) {
        return new PageSourceAdapter() {
            @Override
            public String format() {
                return format;
            }

            @Override
            public void convert(Path source, Path target, ResourceLimits limits) {
                throw new AssertionError("注册校验不得触发文件转换");
            }
        };
    }
}
