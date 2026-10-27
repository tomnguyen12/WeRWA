package vn.werwa.bidvsim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Giả lập BIDV Open API cho devnet (US-5.02; Q55): tạo báo có, ký thông điệp, gọi webhook của nền tảng. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BidvSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(BidvSimulatorApplication.class, args);
    }
}
