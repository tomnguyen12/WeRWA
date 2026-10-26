package vn.werwa.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Tệp seed của devnet (I6) đọc đủ 12 tổ chức với vai đúng. */
class SeedOrganizationDirectoryTest {

    final SeedOrganizationDirectory directory = new SeedOrganizationDirectory(new ClassPathResource("seed/orgs.json"));

    @Test
    void readsTheTwelveDevnetOrganizations() {
        assertThat(directory.size()).isEqualTo(12);
        assertThat(directory.rolesOf(UUID.fromString("01a15bc1-f409-7589-aef1-774ceddccf3f")))
                .contains(Set.of(Role.OPERATOR));
        assertThat(directory.rolesOf(UUID.fromString("01a15bc1-f400-7c28-a7a8-d5d4cc19328b")))
                .contains(Set.of(Role.ISSUER));
        assertThat(directory.rolesOf(UUID.randomUUID())).isEmpty();
    }
}
