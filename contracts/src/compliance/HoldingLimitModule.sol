// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {WeRWAModuleBase} from "./WeRWAModuleBase.sol";

/// @title HoldingLimitModule
/// @notice Số dư của ví nhận sau giao dịch không vượt holdingLimit của series (0 là không giới hạn).
///         Loại ví nằm trong mặt nạ `exemptKinds` của series được miễn; hạn mức theo chủ sở hữu thật kiểm ngoài chuỗi.
///         Theo docs/design/04-hop-dong-thong-minh.md và đề xuất docs/decisions/0017-vi-mien-han-muc.md.
///         Áp cho mint và chuyển nhượng.
contract HoldingLimitModule is WeRWAModuleBase {
    /// @notice Loại ví. Thứ tự là một phần của ABI; chỉ thêm ở cuối.
    enum WalletKind {
        STANDARD, // ví nhà đầu tư tự giữ khóa, luôn bị kiểm
        CUSTODIAN_OMNIBUS, // (a) ví omnibus của ngân hàng lưu ký
        PLATFORM_OMNIBUS // (b) ví omnibus do WeRWA nắm giữ; chỉ bật khi nghiệp vụ đổi (ADR-0017)
    }

    /// @notice Mặt nạ mặc định: chỉ miễn ví omnibus của lưu ký.
    uint8 public constant DEFAULT_EXEMPT_KINDS = uint8(1) << uint8(WalletKind.CUSTODIAN_OMNIBUS);
    uint8 private constant _ALLOWED_EXEMPT_BITS =
        (uint8(1) << uint8(WalletKind.CUSTODIAN_OMNIBUS)) | (uint8(1) << uint8(WalletKind.PLATFORM_OMNIBUS));

    struct Config {
        bool configured;
        uint64 holdingLimit;
        uint8 exemptKinds;
    }

    mapping(address => Config) private _config; // compliance → cấu hình
    mapping(address => mapping(address => WalletKind)) private _walletKind; // compliance → ví → loại

    event HoldingLimitConfigured(address indexed compliance, uint64 holdingLimit, uint8 exemptKinds);
    event ExemptKindsSet(address indexed compliance, uint8 exemptKinds);
    event WalletKindSet(address indexed compliance, address indexed wallet, WalletKind kind);

    error InvalidExemptKinds(uint8 exemptKinds);
    error NotConfigured();
    error ZeroAddress();
    error LengthMismatch();

    /// @notice Đặt hạn mức và mặt nạ loại ví được miễn. Gọi qua compliance (khi triển khai, hoặc timelock về sau).
    function configure(uint64 holdingLimit, uint8 exemptKinds) external onlyComplianceCall {
        _checkMask(exemptKinds);
        _config[msg.sender] = Config({configured: true, holdingLimit: holdingLimit, exemptKinds: exemptKinds});
        emit HoldingLimitConfigured(msg.sender, holdingLimit, exemptKinds);
    }

    /// @notice Bật hoặc tắt loại ví được miễn. Gọi qua compliance (timelock).
    function setExemptKinds(uint8 exemptKinds) external onlyComplianceCall {
        Config storage c = _config[msg.sender];
        if (!c.configured) revert NotConfigured();
        _checkMask(exemptKinds);
        c.exemptKinds = exemptKinds;
        emit ExemptKindsSet(msg.sender, exemptKinds);
    }

    /// @notice Đặt loại cho một ví. STANDARD là bỏ khỏi danh sách omnibus. Gọi qua compliance.
    function setWalletKind(address wallet, WalletKind kind) external onlyComplianceCall {
        _setWalletKind(wallet, kind);
    }

    /// @notice Đặt loại cho nhiều ví một lần (danh sách omnibus ban đầu khi triển khai series).
    function setWalletKinds(address[] calldata wallets, WalletKind[] calldata kinds) external onlyComplianceCall {
        if (wallets.length != kinds.length) revert LengthMismatch();
        for (uint256 i = 0; i < wallets.length; i++) {
            _setWalletKind(wallets[i], kinds[i]);
        }
    }

    function configOf(address compliance) external view returns (Config memory) {
        return _config[compliance];
    }

    function walletKindOf(address compliance, address wallet) external view returns (WalletKind) {
        return _walletKind[compliance][wallet];
    }

    /// @notice Ví có được miễn hạn mức ở series này không.
    function isExempt(address compliance, address wallet) public view returns (bool) {
        uint8 bit = uint8(1) << uint8(_walletKind[compliance][wallet]);
        return _config[compliance].exemptKinds & bit != 0;
    }

    function moduleCheck(address from, address to, uint256 value, address compliance)
        external
        view
        override
        returns (bool)
    {
        Config memory c = _config[compliance];
        if (!c.configured) return false;
        if (c.holdingLimit == 0) return true;
        if (from == to) return true; // tự chuyển cho mình: số dư không đổi
        if (isExempt(compliance, to)) return true;
        return _token(compliance).balanceOf(to) + value <= c.holdingLimit;
    }

    function name() public pure override returns (string memory) {
        return "HoldingLimitModule";
    }

    function _setWalletKind(address wallet, WalletKind kind) private {
        if (wallet == address(0)) revert ZeroAddress();
        _walletKind[msg.sender][wallet] = kind;
        emit WalletKindSet(msg.sender, wallet, kind);
    }

    function _checkMask(uint8 exemptKinds) private pure {
        if (exemptKinds & ~_ALLOWED_EXEMPT_BITS != 0) revert InvalidExemptKinds(exemptKinds);
    }
}
