package vn.werwa.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import vn.werwa.platform.id.Ids;

class PagingAndETagsTest {

    @Test
    void limitDefaultsTo50AndAccepts1To200() {
        assertThat(Paging.limit(null)).isEqualTo(50);
        assertThat(Paging.limit(1)).isEqualTo(1);
        assertThat(Paging.limit(200)).isEqualTo(200);
        assertThatThrownBy(() -> Paging.limit(0)).hasMessageContaining("PAGE_LIMIT_INVALID");
        assertThatThrownBy(() -> Paging.limit(201)).hasMessageContaining("PAGE_LIMIT_INVALID");
    }

    @Test
    void cursorRoundTripsAndRejectsGarbage() {
        UUID id = Ids.newId();
        assertThat(Paging.decodeCursor(Paging.encodeCursor(id))).isEqualTo(id);
        assertThat(Paging.decodeCursor(null)).isNull();
        assertThatThrownBy(() -> Paging.decodeCursor("không-phải-con-trỏ")).hasMessageContaining("CURSOR_INVALID");
    }

    @Test
    void pageHasNextCursorOnlyWhenMoreRows() {
        List<UUID> ids = List.of(Ids.newId(), Ids.newId(), Ids.newId());
        Paging.Page<UUID> full = Paging.Page.of(ids, 2, Function.identity());
        assertThat(full.items()).hasSize(2);
        assertThat(Paging.decodeCursor(full.nextCursor())).isEqualTo(ids.get(1));

        Paging.Page<UUID> last = Paging.Page.of(ids, 3, Function.identity());
        assertThat(last.nextCursor()).isNull();
    }

    @Test
    void ifMatchAcceptsCurrentVersionOnly() {
        ETags.requireMatch("\"7\"", 7);
        ETags.requireMatch("W/\"7\"", 7);
        ETags.requireMatch("\"6\", \"7\"", 7);
        assertThatThrownBy(() -> ETags.requireMatch("\"6\"", 7)).hasMessageContaining("PRECONDITION_FAILED");
        assertThatThrownBy(() -> ETags.requireMatch(null, 7)).hasMessageContaining("PRECONDITION_REQUIRED");
    }

    @Test
    void errorTypeUriFollowsTheApiSpecExample() {
        assertThat(PlatformError.IDEMPOTENCY_KEY_REUSED.type())
                .isEqualTo("https://errors.werwa/platform/idempotency-key-reused");
    }
}
