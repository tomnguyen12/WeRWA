package vn.werwa.platform.mail;

import java.util.List;

/** Gửi email (Q62). Mô-đun Tích hợp cài đặt qua SMTP; không có bean thì không gửi email, chỉ còn thông báo trong ứng dụng. */
public interface Mailer {

    void send(List<String> to, String subject, String text);
}
