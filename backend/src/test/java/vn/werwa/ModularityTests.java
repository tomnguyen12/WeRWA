package vn.werwa;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/** Cổng CI của backend (CLAUDE.md): ranh giới mô-đun Spring Modulith phải qua. */
class ModularityTests {

    private static final ApplicationModules MODULES = ApplicationModules.of(WerwaApplication.class);

    @Test
    void verifiesModuleBoundaries() {
        MODULES.verify();
    }

    @Test
    void hasSixDomainModulesAndPlatform() {
        assertThat(MODULES.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
                .containsExactlyInAnyOrder(
                        "platform", "onboarding", "issuance", "distribution", "governance", "integration", "onchain");
    }

    @Test
    void writesModuleDocumentation() {
        new Documenter(MODULES).writeDocumentation();
    }
}
