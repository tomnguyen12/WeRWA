package vn.werwa.platform.web;

/**
 * Ghi đè có kiểm phiên bản (đặc tả API, 05): bản ghi có cột {@code version}; GET trả {@code ETag: "<version>"}; lệnh ghi
 * đè gửi {@code If-Match}. Thiếu header trả 428, sai phiên bản trả 412.
 */
public final class ETags {

    private ETags() {}

    public static String of(long version) {
        return "\"" + version + "\"";
    }

    /** Kiểm {@code If-Match} với phiên bản hiện tại của bản ghi. */
    public static void requireMatch(String ifMatch, long currentVersion) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(PlatformError.PRECONDITION_REQUIRED);
        }
        String expected = of(currentVersion);
        for (String candidate : ifMatch.split(",")) {
            String tag = candidate.strip();
            if (tag.startsWith("W/")) {
                tag = tag.substring(2);
            }
            if (tag.equals(expected) || tag.equals("*")) {
                return;
            }
        }
        throw new ApiException(PlatformError.PRECONDITION_FAILED, "phiên bản hiện tại " + expected);
    }
}
