// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {WeRWAModuleBase} from "./WeRWAModuleBase.sol";
import {IIdentityRegistry} from "@trex/registry/interface/IIdentityRegistry.sol";
import {ITrustedIssuersRegistry} from "@trex/registry/interface/ITrustedIssuersRegistry.sol";
import {IIdentity} from "@onchain-id/solidity/contracts/interface/IIdentity.sol";
import {IClaimIssuer} from "@onchain-id/solidity/contracts/interface/IClaimIssuer.sol";

/// @title InvestorClassModule
/// @notice Ví nhận phải có claim INVESTOR_CLASS hợp lệ, do bên cấp claim tin cậy ký, mã loại nằm trong danh sách
///         series cho phép, chưa hết hạn; và claim KYC_INSTITUTION còn hạn. Áp cho mint và chuyển nhượng.
///         docs/design/04-hop-dong-thong-minh.md, mục Module tuân thủ; định dạng claim theo docs/open-questions.md, C1.
/// @dev Sổ định danh T-REX đã kiểm chữ ký và thu hồi của claim nhưng không kiểm hạn; module này kiểm thêm hạn và mã loại.
///      Dữ liệu claim:
///        KYC_INSTITUTION: abi.encode(uint64 validUntil)
///        INVESTOR_CLASS:  abi.encode(uint16 classCode, uint64 validUntil)
///      Claim có dữ liệu sai dạng được coi là không hợp lệ (không revert).
///      Ví omnibus không được miễn (ADR-0017).
contract InvestorClassModule is WeRWAModuleBase {
    /// @notice Mã chủ đề claim, đặt khi triển khai (mã số chờ chốt, C1).
    uint256 public immutable kycTopic;
    uint256 public immutable investorClassTopic;

    struct Config {
        bool configured;
        uint16[] classes;
    }

    mapping(address => Config) private _config; // compliance → cấu hình
    mapping(address => mapping(uint16 => bool)) private _allowed; // compliance → mã loại → cho phép

    event InvestorClassesSet(address indexed compliance, uint16[] classes);

    error EmptyClassList();
    error DuplicateClass(uint16 classCode);
    error InvalidTopics();

    constructor(uint256 kycTopic_, uint256 investorClassTopic_) {
        if (kycTopic_ == investorClassTopic_) revert InvalidTopics();
        kycTopic = kycTopic_;
        investorClassTopic = investorClassTopic_;
    }

    /// @notice Đặt danh sách loại nhà đầu tư được phép của series (thay toàn bộ). Gọi qua compliance.
    function setInvestorClasses(uint16[] calldata classes) external onlyComplianceCall {
        if (classes.length == 0) revert EmptyClassList();
        Config storage c = _config[msg.sender];
        for (uint256 i = 0; i < c.classes.length; i++) {
            _allowed[msg.sender][c.classes[i]] = false;
        }
        for (uint256 i = 0; i < classes.length; i++) {
            if (_allowed[msg.sender][classes[i]]) revert DuplicateClass(classes[i]);
            _allowed[msg.sender][classes[i]] = true;
        }
        c.classes = classes;
        c.configured = true;
        emit InvestorClassesSet(msg.sender, classes);
    }

    function investorClassesOf(address compliance) external view returns (uint16[] memory) {
        return _config[compliance].classes;
    }

    function isClassAllowed(address compliance, uint16 classCode) external view returns (bool) {
        return _allowed[compliance][classCode];
    }

    function moduleCheck(address, address to, uint256, address compliance) external view override returns (bool) {
        if (!_config[compliance].configured) return false;
        return isEligible(compliance, to);
    }

    /// @notice Ví có đủ điều kiện nhận trái phiếu của series không (cùng luật với moduleCheck).
    function isEligible(address compliance, address wallet) public view returns (bool) {
        IIdentityRegistry registry = _token(compliance).identityRegistry();
        IIdentity identity = registry.identity(wallet);
        if (address(identity) == address(0)) return false;
        ITrustedIssuersRegistry issuers = registry.issuersRegistry();

        if (!_hasValidClaim(identity, issuers, kycTopic, compliance)) return false;
        return _hasValidClaim(identity, issuers, investorClassTopic, compliance);
    }

    function name() public pure override returns (string memory) {
        return "InvestorClassModule";
    }

    /// @dev Có ít nhất một claim của bên cấp tin cậy cho chủ đề, chữ ký hợp lệ, chưa thu hồi, còn hạn
    ///      (và với INVESTOR_CLASS: mã loại được phép).
    function _hasValidClaim(IIdentity identity, ITrustedIssuersRegistry issuers, uint256 topic, address compliance)
        private
        view
        returns (bool)
    {
        IClaimIssuer[] memory trusted = issuers.getTrustedIssuersForClaimTopic(topic);
        for (uint256 i = 0; i < trusted.length; i++) {
            bytes32 claimId = keccak256(abi.encode(address(trusted[i]), topic));
            // Vòng lặp theo danh sách bên cấp tin cậy (do multisig 3 bên quản lý, rất ngắn); scheme và uri không dùng.
            // slither-disable-next-line calls-loop,unused-return
            (uint256 foundTopic,, address issuer, bytes memory sig, bytes memory data,) = identity.getClaim(claimId);
            if (foundTopic != topic || issuer != address(trusted[i])) continue;
            if (!_dataValid(topic, data, compliance)) continue;
            if (_isClaimValid(trusted[i], identity, topic, sig, data)) return true;
        }
        return false;
    }

    function _dataValid(uint256 topic, bytes memory data, address compliance) private view returns (bool) {
        uint256 validUntil;
        if (topic == kycTopic) {
            if (data.length != 32) return false;
            validUntil = abi.decode(data, (uint256));
        } else {
            if (data.length != 64) return false;
            (uint256 classCode, uint256 until) = abi.decode(data, (uint256, uint256));
            if (classCode > type(uint16).max) return false;
            // casting to 'uint16' is safe because classCode was bounds-checked on the line above
            // forge-lint: disable-next-line(unsafe-typecast)
            if (!_allowed[compliance][uint16(classCode)]) return false;
            validUntil = until;
        }
        // Hạn claim tính theo ngày; sai lệch vài giây của thời gian khối không ảnh hưởng.
        // slither-disable-next-line timestamp
        return validUntil <= type(uint64).max && validUntil > block.timestamp;
    }

    /// @dev Gọi bên cấp claim; bên cấp lỗi hoặc revert thì coi như claim không hợp lệ.
    function _isClaimValid(IClaimIssuer issuer, IIdentity identity, uint256 topic, bytes memory sig, bytes memory data)
        private
        view
        returns (bool)
    {
        // slither-disable-next-line calls-loop
        try issuer.isClaimValid(identity, topic, sig, data) returns (bool valid) {
            return valid;
        } catch {
            return false;
        }
    }
}
