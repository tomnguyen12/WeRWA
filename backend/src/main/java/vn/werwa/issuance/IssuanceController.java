package vn.werwa.issuance;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.werwa.issuance.document.DocumentService;
import vn.werwa.platform.openapi.api.IssuanceApi;
import vn.werwa.platform.openapi.model.Document;
import vn.werwa.platform.openapi.model.DocumentDownload;

/** API Phát hành của B6a (tài liệu). Đề án, series, quyết định UBND làm ở B6b; tới lúc đó trả 501 (Q20). */
@RestController
class IssuanceController implements IssuanceApi {

    private final DocumentService documents;

    IssuanceController(DocumentService documents) {
        this.documents = documents;
    }

    @Override
    public ResponseEntity<Document> uploadDocument(String idempotencyKey, MultipartFile file, String docType, String title,
            UUID supersedesId, String visibility) {
        DocumentService.Doc doc = documents.upload(docType, title, supersedesId, visibility,
                file.getContentType() == null ? "application/octet-stream" : file.getContentType(), file.getSize(),
                () -> open(file));
        return ResponseEntity.created(URI.create("/v1/documents/" + doc.id())).body(view(doc));
    }

    @Override
    public ResponseEntity<Document> getDocument(UUID id) {
        return ResponseEntity.ok(view(documents.get(id)));
    }

    @Override
    public ResponseEntity<DocumentDownload> getDocumentContent(UUID id) {
        DocumentService.Download d = documents.download(id);
        DocumentDownload out = new DocumentDownload();
        out.setUrl(URI.create(d.url()));
        out.setExpiresAt(d.expiresAt().atOffset(ZoneOffset.UTC));
        out.setSha256(d.sha256());
        return ResponseEntity.ok(out);
    }

    static Document view(DocumentService.Doc d) {
        Document v = new Document();
        v.setId(d.id());
        v.setDocType(d.docType());
        v.setTitle(d.title());
        v.setVersionNo(d.versionNo());
        v.setSha256(d.sha256());
        v.setSupersedesId(d.supersedesId());
        v.setVisibility(Document.VisibilityEnum.fromValue(d.visibility()));
        return v;
    }

    private static InputStream open(MultipartFile file) {
        try {
            return file.getInputStream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
