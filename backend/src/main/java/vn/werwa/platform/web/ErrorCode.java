package vn.werwa.platform.web;

import org.springframework.http.HttpStatus;

/**
 * Mã lỗi trả trong trường {@code code} của {@code application/problem+json}. Mỗi mô-đun khai báo enum mã lỗi riêng
 * cài interface này; {@link PlatformError} là mã kỹ thuật dùng chung.
 */
public interface ErrorCode {

    /** Mã máy đọc, chữ hoa và gạch dưới, ví dụ {@code ALLOCATION_EXCEEDS_MAX}. */
    String code();

    HttpStatus status();

    /** Tiêu đề ngắn bằng tiếng Việt, không đổi theo từng lần lỗi. */
    String title();

    /** Nhóm trong URI {@code type}, thường là tên mô-đun. */
    String group();

    /** {@code https://errors.werwa/<nhóm>/<mã-gạch-ngang>}, theo ví dụ của đặc tả API (05). */
    default String type() {
        return "https://errors.werwa/" + group() + "/" + code().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
