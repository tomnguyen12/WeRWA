/**
 * Lớp Java của hợp đồng, sinh từ {@code api/abi} khi build (ADR-11). Mô-đun khác dùng để mã hóa calldata qua
 * {@link vn.werwa.onchain.api.ContractCalls}; không gửi giao dịch trực tiếp bằng các lớp này.
 */
@NamedInterface("contracts")
package vn.werwa.onchain.api.contracts;

import org.springframework.modulith.NamedInterface;
