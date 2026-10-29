package vn.werwa.onchain.api;

import java.util.List;

/** Đọc Safe 1.4.1 qua O1 (Q107): owner, ngưỡng, nonce là nguồn sự thật cho thu chữ ký (B4b). */
public interface Safes {

    /** Địa chỉ owner, chữ thường. */
    List<String> owners(String safe);

    int threshold(String safe);

    long nonce(String safe);
}
