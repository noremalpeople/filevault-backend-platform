package com.filevault.storage;

import com.filevault.common.ApiException;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;

public final class FileRules {
    private static final Map<String, String> TYPES = Map.of("pdf", "application/pdf", "txt", "text/plain",
            "md", "text/markdown", "csv", "text/csv", "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg");

    private FileRules() { }

    public static String validateName(String name) {
        if (name == null || name.length() > 180 || name.isBlank() || !name.equals(name.strip())
                || name.endsWith(".") || name.startsWith(".")
                || name.codePoints().anyMatch(c -> Character.isISOControl(c) || "\\/:*?\"<>|".indexOf(c) >= 0)) {
            throw invalidName();
        }
        int firstDot = name.indexOf('.');
        if (firstDot <= 0 || name.substring(0, firstDot).strip().toUpperCase(Locale.ROOT)
                .matches("CON|PRN|AUX|NUL|COM[0-9¹²³]|LPT[0-9¹²³]")) {
            throw invalidName();
        }
        String type = TYPES.get(name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
        if (type == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FILE_TYPE", "This file extension is unsupported.");
        }
        return type;
    }

    public static void validateContent(Path path, String mediaType) throws IOException {
        if (mediaType.startsWith("text/")) {
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try (var reader = new java.io.InputStreamReader(Files.newInputStream(path), decoder)) {
                char[] buffer = new char[8192];
                for (int count; (count = reader.read(buffer)) != -1;) {
                    for (int index = 0; index < count; index++) {
                        char character = buffer[index];
                        if (Character.isISOControl(character) && "\t\n\r".indexOf(character) < 0) {
                            throw invalidContent();
                        }
                    }
                }
            } catch (CharacterCodingException failure) {
                throw invalidContent();
            }
            return;
        }

        byte[] marker = HexFormat.of().parseHex(switch (mediaType) {
            case "application/pdf" -> "255044462d";
            case "image/png" -> "89504e470d0a1a0a";
            case "image/jpeg" -> "ffd8ff";
            default -> throw invalidContent();
        });
        try (var input = Files.newInputStream(path)) {
            if (!java.util.Arrays.equals(marker, input.readNBytes(marker.length))) {
                throw invalidContent();
            }
        }
    }

    private static ApiException invalidName() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILENAME", "Use a plain supported filename.");
    }

    private static ApiException invalidContent() {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_FILE_CONTENT", "Content does not match its file type.");
    }
}
