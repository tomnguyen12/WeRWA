package vn.werwa.platform.events;

import java.sql.Timestamp;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;

/** Việc định kỳ của outbox và inbox: phát lại sự kiện chưa lên NATS; dọn bảng lưu trữ (Q11) và inbox. */
public class EventMaintenance {

    private static final Logger log = LoggerFactory.getLogger(EventMaintenance.class);

    private final IncompleteEventPublications incomplete;
    private final JdbcTemplate jdbc;
    private final EventsProperties props;
    private final Clock clock;

    public EventMaintenance(
            IncompleteEventPublications incomplete, JdbcTemplate jdbc, EventsProperties props, Clock clock) {
        this.incomplete = incomplete;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${werwa.events.resubmit-interval:PT30S}")
    public void resubmit() {
        incomplete.resubmitIncompletePublicationsOlderThan(props.resubmitOlderThan());
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M")
    public void purge() {
        int archived = jdbc.update(
                "DELETE FROM platform.event_publication_archive WHERE completion_date < ?",
                Timestamp.from(clock.instant().minus(props.archiveRetention())));
        int inbox = jdbc.update(
                "DELETE FROM platform.inbox WHERE processed_at < ?",
                Timestamp.from(clock.instant().minus(props.inboxRetention())));
        if (archived + inbox > 0) {
            log.info("Dọn {} sự kiện lưu trữ, {} dòng inbox quá hạn", archived, inbox);
        }
    }
}
