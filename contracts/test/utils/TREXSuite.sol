// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {Test} from "forge-std/Test.sol";

import {Token} from "@trex/token/Token.sol";
import {IToken} from "@trex/token/IToken.sol";
import {ClaimTopicsRegistry} from "@trex/registry/implementation/ClaimTopicsRegistry.sol";
import {IdentityRegistry} from "@trex/registry/implementation/IdentityRegistry.sol";
import {IdentityRegistryStorage} from "@trex/registry/implementation/IdentityRegistryStorage.sol";
import {TrustedIssuersRegistry} from "@trex/registry/implementation/TrustedIssuersRegistry.sol";
import {ModularCompliance} from "@trex/compliance/modular/ModularCompliance.sol";
import {TREXImplementationAuthority} from "@trex/proxy/authority/TREXImplementationAuthority.sol";
import {ITREXImplementationAuthority} from "@trex/proxy/authority/ITREXImplementationAuthority.sol";
import {ClaimTopicsRegistryProxy} from "@trex/proxy/ClaimTopicsRegistryProxy.sol";
import {TrustedIssuersRegistryProxy} from "@trex/proxy/TrustedIssuersRegistryProxy.sol";
import {IdentityRegistryStorageProxy} from "@trex/proxy/IdentityRegistryStorageProxy.sol";
import {IdentityRegistryProxy} from "@trex/proxy/IdentityRegistryProxy.sol";
import {ModularComplianceProxy} from "@trex/proxy/ModularComplianceProxy.sol";
import {TokenProxy} from "@trex/proxy/TokenProxy.sol";

import {Identity} from "@onchain-id/solidity/contracts/Identity.sol";
import {ClaimIssuer} from "@onchain-id/solidity/contracts/ClaimIssuer.sol";
import {IClaimIssuer} from "@onchain-id/solidity/contracts/interface/IClaimIssuer.sol";
import {IIdentity} from "@onchain-id/solidity/contracts/interface/IIdentity.sol";

/// @notice Bộ dựng T-REX 4.1.3 cho kiểm thử: Implementation Authority, sổ định danh dùng chung,
///         chủ đề claim, bên cấp claim tin cậy (Holco) và ONCHAINID của nhà đầu tư.
/// @dev Chỉ dùng trong test. Mã chủ đề claim là giả định đang dùng (docs/open-questions.md, C1).
abstract contract TREXSuite is Test {
    // Giả định C1: mã chủ đề claim, chờ SA chốt.
    uint256 internal constant TOPIC_KYC_INSTITUTION = uint256(keccak256("WERWA.CLAIM.KYC_INSTITUTION"));
    uint256 internal constant TOPIC_INVESTOR_CLASS = uint256(keccak256("WERWA.CLAIM.INVESTOR_CLASS"));
    uint256 internal constant CLAIM_SCHEME_ECDSA = 1;
    uint16 internal constant COUNTRY_VN = 704;

    // Vai và khóa dùng trong test (không phải khóa thật).
    address internal trexOwner = makeAddr("trexOwner");
    address internal identityAgent = makeAddr("identityAgent");
    uint256 internal claimSignerKey = 0xC1A1;
    address internal claimSigner;

    TREXImplementationAuthority internal ia;
    ClaimTopicsRegistry internal claimTopics;
    TrustedIssuersRegistry internal trustedIssuers;
    IdentityRegistryStorage internal identityStorage;
    IdentityRegistry internal identityRegistry;
    ClaimIssuer internal holcoClaimIssuer;

    function _deployTREXSuite() internal {
        claimSigner = vm.addr(claimSignerKey);

        vm.startPrank(trexOwner);
        ia = new TREXImplementationAuthority(true, address(0), address(0));
        ia.addAndUseTREXVersion(
            ITREXImplementationAuthority.Version({major: 4, minor: 1, patch: 3}),
            ITREXImplementationAuthority.TREXContracts({
                tokenImplementation: address(new Token()),
                ctrImplementation: address(new ClaimTopicsRegistry()),
                irImplementation: address(new IdentityRegistry()),
                irsImplementation: address(new IdentityRegistryStorage()),
                tirImplementation: address(new TrustedIssuersRegistry()),
                mcImplementation: address(new ModularCompliance())
            })
        );

        claimTopics = ClaimTopicsRegistry(address(new ClaimTopicsRegistryProxy(address(ia))));
        trustedIssuers = TrustedIssuersRegistry(address(new TrustedIssuersRegistryProxy(address(ia))));
        identityStorage = IdentityRegistryStorage(address(new IdentityRegistryStorageProxy(address(ia))));
        identityRegistry = IdentityRegistry(
            address(
                new IdentityRegistryProxy(
                    address(ia), address(trustedIssuers), address(claimTopics), address(identityStorage)
                )
            )
        );
        identityStorage.bindIdentityRegistry(address(identityRegistry));
        identityRegistry.addAgent(identityAgent);

        claimTopics.addClaimTopic(TOPIC_KYC_INSTITUTION);
        claimTopics.addClaimTopic(TOPIC_INVESTOR_CLASS);

        holcoClaimIssuer = new ClaimIssuer(trexOwner);
        holcoClaimIssuer.addKey(keccak256(abi.encode(claimSigner)), 3, 1);
        uint256[] memory topics = new uint256[](2);
        topics[0] = TOPIC_KYC_INSTITUTION;
        topics[1] = TOPIC_INVESTOR_CLASS;
        trustedIssuers.addTrustedIssuer(IClaimIssuer(address(holcoClaimIssuer)), topics);
        vm.stopPrank();
    }

    /// @notice Triển khai một token T-REX và modular compliance qua proxy, dùng sổ định danh dùng chung.
    function _deployToken(string memory name, string memory symbol, address agent)
        internal
        returns (Token token, ModularCompliance compliance)
    {
        vm.startPrank(trexOwner);
        compliance = ModularCompliance(address(new ModularComplianceProxy(address(ia))));
        token = Token(
            address(
                new TokenProxy(address(ia), address(identityRegistry), address(compliance), name, symbol, 0, address(0))
            )
        );
        token.addAgent(agent);
        vm.stopPrank();
    }

    /// @notice Tạo ONCHAINID cho nhà đầu tư, gắn claim KYC và loại nhà đầu tư, đăng ký ví vào sổ định danh.
    function _onboardInvestor(address wallet, bytes memory kycData, bytes memory classData)
        internal
        returns (Identity identity)
    {
        identity = new Identity(wallet, false);
        _addClaim(identity, wallet, TOPIC_KYC_INSTITUTION, kycData);
        _addClaim(identity, wallet, TOPIC_INVESTOR_CLASS, classData);
        vm.prank(identityAgent);
        identityRegistry.registerIdentity(wallet, IIdentity(address(identity)), COUNTRY_VN);
    }

    function _addClaim(Identity identity, address managementKey, uint256 topic, bytes memory data) internal {
        bytes memory sig = _signClaim(address(identity), topic, data);
        vm.prank(managementKey);
        identity.addClaim(topic, CLAIM_SCHEME_ECDSA, address(holcoClaimIssuer), sig, data, "");
    }

    function _signClaim(address identity, uint256 topic, bytes memory data) internal view returns (bytes memory) {
        bytes32 dataHash = keccak256(abi.encode(identity, topic, data));
        bytes32 prefixed = keccak256(abi.encodePacked("\x19Ethereum Signed Message:\n32", dataHash));
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(claimSignerKey, prefixed);
        return abi.encodePacked(r, s, v);
    }
}
