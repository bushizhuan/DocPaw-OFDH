package org.openofdh.core.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import org.openofdh.core.api.OfdhException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class JsonIO {
    private static final ObjectMapper MAPPER = createMapper(JsonInclude.Include.ALWAYS);
    private static final ObjectMapper VALUE_MAPPER = createMapper(JsonInclude.Include.NON_NULL);

    private JsonIO() {}

    private static ObjectMapper createMapper(JsonInclude.Include inclusion) {
        JsonFactory factory =
                JsonFactory.builder()
                        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .streamReadConstraints(
                                StreamReadConstraints.builder()
                                        .maxNestingDepth(64)
                                        .maxStringLength(1_000_000)
                                        .maxNumberLength(128)
                                        .build())
                        .build();
        return JsonMapper.builder(factory)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .serializationInclusion(inclusion)
                .build();
    }

    public static JsonNode read(Path path, long limit) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return read(in, limit);
        }
    }

    public static JsonNode read(InputStream input, long limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        long total = 0;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) {
                throw new OfdhException("LIMIT_EXCEEDED", "JSON 输入超过字节大小限额。");
            }
            bytes.write(buffer, 0, count);
        }
        return parse(bytes.toByteArray());
    }

    public static JsonNode parse(byte[] bytes) throws IOException {
        try {
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            JsonNode value = MAPPER.readTree(text);
            if (value == null) {
                throw new OfdhException("JSON_INVALID", "JSON 输入为空。");
            }
            return value;
        } catch (CharacterCodingException e) {
            throw new OfdhException("JSON_INVALID", "JSON 输入必须使用合法的 UTF-8 编码。", e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new OfdhException("JSON_INVALID", "JSON 语法不正确。", e);
        }
    }

    public static JsonNode tree(Object value) {
        return VALUE_MAPPER.valueToTree(value);
    }

    public static byte[] bytes(Object value) throws IOException {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
    }

    public static String string(Object value) throws IOException {
        return MAPPER.writeValueAsString(value);
    }
}
