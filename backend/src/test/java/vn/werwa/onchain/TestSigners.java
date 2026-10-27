package vn.werwa.onchain;

import vn.werwa.onchain.signing.TransactionSigner;

/** Bộ ký giả cho kiểm thử ở gói khác (Q15). */
public final class TestSigners {

    private TestSigners() {}

    public static TransactionSigner operator() {
        return new LocalKeySigner(ChainIntegrationTest.OPERATOR);
    }
}
