package vn.werwa.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import vn.werwa.platform.config.WerwaProperties.SigningMode;

/** Tên biến môi trường của infra (I11, I13) phải bind đủ vào {@code werwa.*}. */
class WerwaPropertiesBindingTest {

    private static final jakarta.validation.ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    @Test
    void bindsEveryVariableOfTheInfraEnvFile() throws IOException {
        WerwaProperties p = bind(sampleEnv());

        assertThat(p.nats().url()).isEqualTo(URI.create("nats://127.0.0.1:4222"));
        assertThat(p.nats().stream()).isEqualTo("WERWA");
        assertThat(p.oidc().clientId()).isEqualTo("werwa-web");
        assertThat(p.chain().id()).isEqualTo(1337);
        assertThat(p.chain().readUrls())
                .containsExactly(URI.create("http://127.0.0.1:8555"), URI.create("http://127.0.0.1:8565"));
        assertThat(p.chain().submitUrls())
                .containsExactly(URI.create("http://127.0.0.1:8545"), URI.create("http://127.0.0.1:8546"));
        assertThat(p.chain().deployedAtBlock()).isEqualTo(12);
        assertThat(p.chain().signingMode()).isEqualTo(SigningMode.PKCS11);
        assertThat(p.signer().url()).isEqualTo(URI.create("http://127.0.0.1:8745"));
        assertThat(p.monitoring().alertmanagerUrl()).isEqualTo(URI.create("http://127.0.0.1:9093"));
        assertThat(violations(p)).isEmpty();
    }

    @Test
    void looksUpContractsAndSafesByTheirNamesInDevnetJson() throws IOException {
        WerwaProperties p = bind(sampleEnv());

        assertThat(p.contract("seriesFactory")).isEqualTo("0x1000000000000000000000000000000000000002");
        assertThat(p.contract("series-factory")).isEqualTo("0x1000000000000000000000000000000000000002");
        assertThat(p.safe("governance3of3")).isEqualTo("0x2000000000000000000000000000000000000001");
        assertThatThrownBy(() -> p.contract("proxyAdmin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("werwa.contracts.proxyAdmin");
    }

    @Test
    void rejectsReadingFromAValidator() throws IOException {
        Map<String, Object> env = sampleEnv();
        env.put("WERWA_CHAIN_READURLS", "http://127.0.0.1:8545");

        assertThat(messages(bind(env))).anyMatch(m -> m.contains("chỉ đọc qua O1"));
    }

    @Test
    void requiresSignerInPkcs11Mode() throws IOException {
        Map<String, Object> env = sampleEnv();
        env.remove("WERWA_SIGNER_URL");
        env.remove("WERWA_SIGNER_TOKEN");

        assertThat(messages(bind(env))).anyMatch(m -> m.contains("werwa.signer.url"));

        env.put("WERWA_CHAIN_SIGNINGMODE", "devnet");
        assertThat(violations(bind(env))).isEmpty();
    }

    @Test
    void doesNotPrintSecrets() throws IOException {
        WerwaProperties p = bind(sampleEnv());

        assertThat(p.nats().toString()).doesNotContain("gia-tri-gia");
        assertThat(p.signer().toString()).doesNotContain("gia-tri-gia");
    }

    private static WerwaProperties bind(Map<String, Object> env) {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(new SystemEnvironmentPropertySource("env", env));
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind("werwa", WerwaProperties.class)
                .get();
    }

    private static Set<ConstraintViolation<WerwaProperties>> violations(WerwaProperties p) {
        return VALIDATOR.validate(p);
    }

    private static List<String> messages(WerwaProperties p) {
        return violations(p).stream().map(ConstraintViolation::getMessage).toList();
    }

    /** Đọc tệp mẫu theo định dạng {@code KEY=VALUE} của {@code infra/werwa.sh env}. */
    private static Map<String, Object> sampleEnv() throws IOException {
        Map<String, Object> env = new LinkedHashMap<>();
        try (InputStream in = WerwaPropertiesBindingTest.class.getResourceAsStream("/werwa.env.sample")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                env.put(trimmed.substring(0, eq), trimmed.substring(eq + 1));
            }
        }
        return new HashMap<>(env);
    }
}
