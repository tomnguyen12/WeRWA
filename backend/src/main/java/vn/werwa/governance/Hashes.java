package vn.werwa.governance;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** SHA-256 của nội dung ở dạng JSON chuẩn hóa: khóa xếp theo thứ tự chữ cái, không khoảng trắng. */
public final class Hashes {

    public static final String ZERO = "0x" + "0".repeat(64);

    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private Hashes() {}

    public static String canonicalJson(Object value) {
        return CANONICAL.writeValueAsString(value);
    }

    public static String payloadHash(Object payload) {
        return sha256(canonicalJson(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static String sha256(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) {
                digest.update(p);
            }
            return "0x" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] bytes(String hex0x) {
        return HexFormat.of().parseHex(hex0x.substring(2));
    }
}
