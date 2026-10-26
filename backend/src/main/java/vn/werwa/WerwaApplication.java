package vn.werwa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Khối triển khai duy nhất của WeRWA (ADR-10). Mỗi gói con trực tiếp là một mô-đun Spring Modulith:
 * sáu mô-đun phân hệ và mô-đun dùng chung {@code platform} (D3).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class WerwaApplication {

    public static void main(String[] args) {
        SpringApplication.run(WerwaApplication.class, args);
    }
}
