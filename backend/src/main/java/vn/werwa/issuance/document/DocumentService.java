package vn.werwa.issuance.document;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.issuance.IssuanceError;
import vn.werwa.platform.documents.DocumentRegistry;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.security.CurrentUser;
import vn.werwa.platform.security.Role;
import vn.werwa.platform.web.ApiException;

/**
 * Hồ sơ phát hành có phiên bản (US-2.04; Q29). Mỗi lần tải là một bản ghi mới với SHA-256 của nội dung; tải bản mới của một
 * tài liệu thì trỏ về bản trước, không ghi đè. Tài liệu INTERNAL chỉ các bên tham gia phát hành xem được; người không
 * được xem nhận 404 như tài liệu không tồn tại (AC3).
 */
public class DocumentService implements DocumentRegistry {

    /** Vai không được xem tài liệu INTERNAL (Q29). */
    static final Set<Role> OUTSIDE_ISSUANCE = EnumSet.of(Role.INVESTOR, Role.CUSTODIAN);

    private final JdbcTemplate jdbc;
    private final DocumentStore store;
    private final AuditLog audit;
    private final StorageProperties props;
    private final Clock clock;

    public DocumentService(JdbcTemplate jdbc, DocumentStore store, AuditLog audit, StorageProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.store = store;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    public record Doc(
            UUID id, String docType, String title, int versionNo, String sha256, UUID supersedesId, String visibility,
            String storageKey) {}

    public record Download(String url, Instant expiresAt, String sha256) {}

    /** US-2.04-AC1: lưu nội dung, trả mã và SHA-256. */
    @Transactional
    public Doc upload(String docType, String title, UUID supersedesId, String visibility, String contentType,
            long size, Supplier<InputStream> content) {
        if (size <= 0) {
            throw new ApiException(IssuanceError.DOCUMENT_EMPTY);
        }
        int version = 1;
        if (supersedesId != null) {
            Doc previous = find(supersedesId);
            Integer newer = jdbc.queryForObject(
                    "SELECT count(*) FROM issuance.document WHERE supersedes_id = ?", Integer.class, supersedesId);
            if (newer > 0) {
                throw new ApiException(IssuanceError.DOCUMENT_ALREADY_SUPERSEDED);
            }
            version = previous.versionNo() + 1;
        }
        String sha256 = sha256(content);
        UUID id = Ids.newId();
        String key = "documents/" + id;
        try (InputStream in = content.get()) {
            store.put(key, in, size, contentType);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        CurrentUser user = CurrentUser.get();
        try {
            jdbc.update("""
                INSERT INTO issuance.document
                    (id, doc_type, title, version_no, storage_key, sha256, size_bytes, content_type, supersedes_id,
                     visibility, uploaded_by, owner_org_id, uploaded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, docType, title, version, key, sha256, size, contentType, supersedesId,
                visibility == null ? "INTERNAL" : visibility, user.subject(), user.organizationId(),
                Timestamp.from(clock.instant()));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // Hai người cùng tải bản mới cho một bản: ràng buộc UNIQUE(supersedes_id) giữ một bản thắng
            throw new ApiException(IssuanceError.DOCUMENT_ALREADY_SUPERSEDED);
        }
        audit.record("DOCUMENT_UPLOADED", "document/" + id, Map.of("docType", docType, "sha256", sha256,
                "versionNo", version, "supersedesId", String.valueOf(supersedesId)));
        return find(id);
    }

    /** US-2.04-AC3: người không được xem nhận 404. */
    public Doc get(UUID id) {
        Doc doc = find(id);
        if ("INTERNAL".equals(doc.visibility()) && !insideIssuance(CurrentUser.get())) {
            throw new ApiException(IssuanceError.DOCUMENT_NOT_FOUND);
        }
        return doc;
    }

    public Download download(UUID id) {
        Doc doc = get(id);
        return new Download(store.downloadUrl(doc.storageKey()).toString(), clock.instant().plus(props.downloadTtl()),
                doc.sha256());
    }

    @Override
    public Collection<UUID> missing(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<UUID> found = jdbc.queryForList("SELECT id FROM issuance.document WHERE id = ANY (?)", UUID.class,
                (Object) ids.toArray(new UUID[0]));
        return ids.stream().filter(i -> !found.contains(i)).toList();
    }

    /** Tài liệu theo mã, không kiểm quyền xem; mã chưa có thì 422 {@code DOCUMENT_NOT_FOUND} (dùng cho tham chiếu). */
    public java.util.Map<UUID, Doc> require(Collection<UUID> ids) {
        Collection<UUID> missing = missing(ids);
        if (!missing.isEmpty()) {
            throw new ApiException(IssuanceError.DOCUMENT_REFERENCE_MISSING, missing.toString());
        }
        java.util.Map<UUID, Doc> out = new java.util.LinkedHashMap<>();
        for (UUID id : ids) {
            out.put(id, find(id));
        }
        return out;
    }

    @Override
    public java.util.Optional<String> sha256(UUID documentId) {
        return jdbc.queryForList("SELECT sha256 FROM issuance.document WHERE id = ?", String.class, documentId).stream()
                .findFirst();
    }

    Doc find(UUID id) {
        List<Doc> rows = jdbc.query("""
                SELECT id, doc_type, title, version_no, sha256, supersedes_id, visibility, storage_key
                FROM issuance.document WHERE id = ?
                """, (rs, i) -> new Doc(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getString(5), rs.getObject(6, UUID.class), rs.getString(7), rs.getString(8)), id);
        if (rows.isEmpty()) {
            throw new ApiException(IssuanceError.DOCUMENT_NOT_FOUND);
        }
        return rows.getFirst();
    }

    static boolean insideIssuance(CurrentUser user) {
        return user.roles().stream().anyMatch(r -> !OUTSIDE_ISSUANCE.contains(r));
    }

    private static String sha256(Supplier<InputStream> content) {
        try (DigestInputStream in = new DigestInputStream(content.get(), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
