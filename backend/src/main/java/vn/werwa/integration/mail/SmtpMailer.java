package vn.werwa.integration.mail;

import java.util.List;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import vn.werwa.platform.mail.Mailer;

/** Email qua SMTP (Q62). Devnet dùng Mailpit; môi trường thật dùng máy chủ thư của Holco. */
public class SmtpMailer implements Mailer {

    private final JavaMailSender sender;
    private final String from;

    public SmtpMailer(JavaMailSender sender, String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public void send(List<String> to, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to.toArray(String[]::new));
        message.setSubject(subject);
        message.setText(text);
        sender.send(message);
    }
}
