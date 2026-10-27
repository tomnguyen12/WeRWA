package vn.werwa.issuance;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.issuance.document.DocumentService;
import vn.werwa.issuance.document.DocumentStore;
import vn.werwa.issuance.document.StorageProperties;

/** Bean của mô-đun Phát hành. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
class IssuanceConfig {

    @Bean
    DocumentStore documentStore(StorageProperties props) {
        DocumentStore store = new DocumentStore(props);
        store.verifyBucket();
        return store;
    }

    @Bean
    DocumentService documents(JdbcTemplate jdbc, DocumentStore store, AuditLog audit, StorageProperties props, Clock clock) {
        return new DocumentService(jdbc, store, audit, props, clock);
    }
}
