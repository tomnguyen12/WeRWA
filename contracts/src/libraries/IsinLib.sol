// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

/// @title IsinLib
/// @notice Kiểm mã ISIN theo ISO 6166 (docs/open-questions.md, C7): 2 chữ cái mã nước, 9 ký tự A–Z hoặc 0–9,
///         1 chữ số kiểm tra theo thuật toán Luhn trên chuỗi số sau khi đổi chữ cái thành số (A = 10 … Z = 35).
library IsinLib {
    function isValid(bytes12 isin) internal pure returns (bool) {
        // Chuỗi số tối đa 22 chữ số (11 ký tự, mỗi chữ cái thành 2 chữ số).
        uint8[22] memory digits = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
        uint256 n = 0;
        for (uint256 i = 0; i < 11; i++) {
            uint8 c = uint8(isin[i]);
            if (c >= 0x41 && c <= 0x5A) {
                uint8 v = c - 0x41 + 10;
                digits[n++] = v / 10;
                digits[n++] = v % 10;
            } else if (c >= 0x30 && c <= 0x39) {
                if (i < 2) return false; // mã nước phải là chữ cái
                digits[n++] = c - 0x30;
            } else {
                return false;
            }
        }
        uint8 check = uint8(isin[11]);
        if (check < 0x30 || check > 0x39) return false;

        // Luhn: từ chữ số phải nhất của phần thân, nhân đôi xen kẽ bắt đầu từ chữ số đó.
        uint256 sum = 0;
        bool double = true;
        for (uint256 j = n; j > 0; j--) {
            uint256 d = digits[j - 1];
            if (double) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            double = !double;
        }
        return (10 - (sum % 10)) % 10 == check - 0x30;
    }
}
