package vn.werwa.platform.security;

/** Vai của tổ chức và người dùng, trùng enum {@code roles} của {@code api/openapi.yaml} và claim {@code roles} (I3). */
public enum Role {
    ISSUER,
    OPERATING_AGENCY,
    SYNDICATE,
    REGISTRAR,
    PAYING_AGENT,
    TREASURY,
    CUSTODIAN,
    TRUSTEE,
    SUPERVISOR,
    OPERATOR,
    INVESTOR
}
