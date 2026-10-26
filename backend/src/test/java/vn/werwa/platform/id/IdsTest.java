package vn.werwa.platform.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void isVersion7WithRfcVariant() {
        UUID id = Ids.newId();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void carriesTheCreationTime() {
        Instant at = Instant.parse("2027-01-20T03:15:22.123Z");
        UUID id = Ids.newId(Clock.fixed(at, ZoneOffset.UTC));
        assertThat(Ids.timestampMillis(id)).isEqualTo(at.toEpochMilli());
    }

    @Test
    void laterMillisecondSortsAfter() {
        Instant at = Instant.parse("2027-01-20T03:15:22Z");
        UUID earlier = Ids.newId(Clock.fixed(at, ZoneOffset.UTC));
        UUID later = Ids.newId(Clock.fixed(at.plusMillis(1), ZoneOffset.UTC));
        assertThat(later.toString()).isGreaterThan(earlier.toString());
    }
}
