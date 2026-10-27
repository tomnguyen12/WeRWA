package vn.werwa.onboarding;

import java.net.URI;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import vn.werwa.onboarding.kyc.KycService;
import vn.werwa.onboarding.organization.OrganizationService;
import vn.werwa.onboarding.organization.OrganizationService.Org;
import vn.werwa.platform.openapi.api.OnboardingApi;
import vn.werwa.platform.openapi.model.AuthorizedSigner;
import vn.werwa.platform.openapi.model.Decision;
import vn.werwa.platform.openapi.model.KycCase;
import vn.werwa.platform.openapi.model.KycCaseCreate;
import vn.werwa.platform.openapi.model.KycDecision;
import vn.werwa.platform.openapi.model.Organization;
import vn.werwa.platform.openapi.model.OrganizationCreate;
import vn.werwa.platform.openapi.model.SignerCreate;
import vn.werwa.platform.security.OrgScope;
import vn.werwa.platform.security.Role;

/** API Onboarding của B5a. Ví (wallet-challenges, wallets) làm ở B5b, tới lúc đó trả 501 (Q20, Q27). */
@RestController
class OnboardingController implements OnboardingApi {

    private final OrganizationService organizations;
    private final KycService kyc;

    OnboardingController(OrganizationService organizations, KycService kyc) {
        this.organizations = organizations;
        this.kyc = kyc;
    }

    @Override
    public ResponseEntity<Organization> createOrganization(String idempotencyKey, OrganizationCreate body) {
        Org org = organizations.create(body.getLegalName(), body.getRegistrationNo(), body.getCountry(),
                body.getRoles().stream().map(OrganizationCreate.RolesEnum::getValue).toList(),
                body.getKybDocumentIds() == null ? List.of() : body.getKybDocumentIds(), body.getNotificationEmail());
        return ResponseEntity.created(URI.create("/v1/organizations/" + org.id())).body(view(org));
    }

    @Override
    public ResponseEntity<Organization> getOrganization(UUID id) {
        // Tổ chức tự xem mình; Holco vận hành và Cơ quan giám sát xem được mọi tổ chức.
        OrgScope.requireOwnOrganizationOr(id, Set.of(Role.OPERATOR, Role.SUPERVISOR));
        return ResponseEntity.ok(view(organizations.get(id)));
    }

    @Override
    public ResponseEntity<Organization> decideKyb(UUID id, String idempotencyKey, Decision decision) {
        boolean approve = decision.getDecision() == Decision.DecisionEnum.APPROVE;
        return ResponseEntity.ok(view(organizations.decideKyb(id, approve, decision.getComment())));
    }

    @Override
    public ResponseEntity<AuthorizedSigner> addSigner(UUID id, String idempotencyKey, SignerCreate body) {
        OrganizationService.Signer s = organizations.addSigner(id, body.getUserId(), body.getSigningAddress(),
                body.getScopes().stream().map(SignerCreate.ScopesEnum::getValue).toList(), body.getValidFrom(),
                body.getValidTo(), body.getAuthorizationDocId());
        AuthorizedSigner out = new AuthorizedSigner();
        out.setId(s.id());
        out.setOrganizationId(s.organizationId());
        out.setUserId(s.userId());
        out.setSigningAddress(s.signingAddress());
        out.setScopes(s.scopes().stream().map(AuthorizedSigner.ScopesEnum::fromValue).toList());
        out.setValidFrom(s.validFrom());
        out.setValidTo(s.validTo());
        out.setAuthorizationDocId(s.authorizationDocId());
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    @Override
    public ResponseEntity<KycCase> openKycCase(String idempotencyKey, KycCaseCreate body) {
        KycService.Case c = kyc.open(body.getOrganizationId(),
                body.getDocumentIds() == null ? List.of() : body.getDocumentIds());
        return ResponseEntity.created(URI.create("/v1/kyc-cases/" + c.id())).body(view(c));
    }

    @Override
    public ResponseEntity<KycCase> decideKyc(UUID id, String idempotencyKey, KycDecision body) {
        boolean approve = body.getDecision() == KycDecision.DecisionEnum.APPROVE;
        return ResponseEntity.ok(view(kyc.decide(id, approve, body.getInvestorClass(), body.getReason())));
    }

    static Organization view(Org o) {
        Organization v = new Organization();
        v.setId(o.id());
        v.setLegalName(o.legalName());
        v.setRegistrationNo(o.registrationNo());
        v.setCountry(o.country());
        v.setRoles(o.roles().stream().map(Organization.RolesEnum::fromValue).toList());
        v.setKybStatus(Organization.KybStatusEnum.fromValue(o.kybStatus()));
        v.setNotificationEmail(o.notificationEmail());
        return v;
    }

    static KycCase view(KycService.Case c) {
        KycCase v = new KycCase();
        v.setId(c.id());
        v.setOrganizationId(c.organizationId());
        v.setStatus(KycCase.StatusEnum.fromValue(c.status()));
        v.setScreeningResult(c.screeningResult());
        v.setInvestorClass(c.investorClass());
        v.setExpiresAt(c.expiresAt() == null ? null : c.expiresAt().atOffset(ZoneOffset.UTC));
        return v;
    }
}
