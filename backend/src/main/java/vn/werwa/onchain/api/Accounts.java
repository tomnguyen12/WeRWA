package vn.werwa.onchain.api;

/** Địa chỉ tài khoản của ứng dụng trên chuỗi, ví dụ để ghi vai operator khi triển khai series (B6c; Q116). */
public interface Accounts {

    /** Địa chỉ khóa vận hành Holco (chữ thường). */
    String operatorAddress();
}
