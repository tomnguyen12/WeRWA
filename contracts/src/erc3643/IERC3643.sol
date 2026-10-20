// SPDX-License-Identifier: CC0-1.0
pragma solidity 0.8.17;

/// @title Giao diện ERC-3643 mà WeRWA dùng
/// @notice Khai báo lại từ văn bản chuẩn EIP-3643 ("T-REX: Token for Regulated EXchanges") và ERC-734/735
///         (định danh, claim), các văn bản này phát hành theo CC0. Không chép mã của bản tham chiếu T-REX
///         hay ONCHAINID (ADR-0019, bước chung).
/// @dev Chỉ gồm các hàm WeRWA gọi. Tên hàm, kiểu tham số và giá trị trả về theo đúng chuẩn, nên selector
///      khớp với mọi bản triển khai ERC-3643 (phương án A: T-REX; phương án B: bản tự viết).

/// @notice ERC-735: đọc claim của một định danh.
interface IERC735Identity {
    function getClaim(bytes32 claimId)
        external
        view
        returns (
            uint256 topic,
            uint256 scheme,
            address issuer,
            bytes memory signature,
            bytes memory data,
            string memory uri
        );
}

/// @notice Bên cấp claim: kiểm chữ ký và trạng thái thu hồi của một claim.
interface IERC3643ClaimIssuer {
    function isClaimValid(address identity, uint256 claimTopic, bytes calldata signature, bytes calldata data)
        external
        view
        returns (bool);
}

/// @notice ERC-3643: danh sách bên cấp claim tin cậy.
interface IERC3643TrustedIssuersRegistry {
    function getTrustedIssuersForClaimTopic(uint256 claimTopic) external view returns (IERC3643ClaimIssuer[] memory);
}

/// @notice ERC-3643: sổ định danh (ví → định danh, xác minh theo claim).
interface IERC3643IdentityRegistry {
    function isVerified(address wallet) external view returns (bool);

    function identity(address wallet) external view returns (IERC735Identity);

    function issuersRegistry() external view returns (IERC3643TrustedIssuersRegistry);
}

/// @notice ERC-3643: compliance theo module.
interface IERC3643Compliance {
    function getTokenBound() external view returns (address);

    function addModule(address module) external;

    function callModuleFunction(bytes calldata callData, address module) external;
}

/// @notice ERC-3643: module của compliance theo module.
interface IERC3643Module {
    event ComplianceBound(address indexed _compliance);
    event ComplianceUnbound(address indexed _compliance);

    function bindCompliance(address compliance) external;

    function unbindCompliance(address compliance) external;

    function moduleTransferAction(address from, address to, uint256 value) external;

    function moduleMintAction(address to, uint256 value) external;

    function moduleBurnAction(address from, uint256 value) external;

    function moduleCheck(address from, address to, uint256 value, address compliance) external view returns (bool);

    function isComplianceBound(address compliance) external view returns (bool);

    function canComplianceBind(address compliance) external view returns (bool);

    function isPlugAndPlay() external pure returns (bool);

    function name() external pure returns (string memory);
}

/// @notice ERC-3643: token chứng khoán có cấp phép. Các hàm ghi chỉ agent gọi được.
interface IERC3643Token {
    function decimals() external view returns (uint8);

    function totalSupply() external view returns (uint256);

    function balanceOf(address account) external view returns (uint256);

    function identityRegistry() external view returns (IERC3643IdentityRegistry);

    function paused() external view returns (bool);

    function getFrozenTokens(address wallet) external view returns (uint256);

    function pause() external;

    function unpause() external;

    function mint(address to, uint256 amount) external;

    function burn(address from, uint256 amount) external;

    function freezePartialTokens(address wallet, uint256 amount) external;

    function unfreezePartialTokens(address wallet, uint256 amount) external;

    function forcedTransfer(address from, address to, uint256 amount) external returns (bool);
}

/// @notice Vai agent và chủ sở hữu của token và compliance (dùng khi triển khai series).
interface IERC3643Administrable {
    function addAgent(address agent) external;

    function transferOwnership(address newOwner) external;
}

/// @notice Bộ tạo token và compliance cho một series. Mỗi phương án của ADR-0019 có một bản cài:
///         phương án A dùng T-REX (src/adapters/trex/), phương án B dùng bản tự viết.
/// @dev Token và compliance trả về đã gắn với nhau, dùng `identityRegistry`, và đã chuyển quyền sở hữu
///      cho người gọi (factory).
interface IERC3643SuiteDeployer {
    function deployTokenSet(address identityRegistry, string calldata name, string calldata symbol)
        external
        returns (address token, address compliance);
}
