// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {Test} from "forge-std/Test.sol";
import {DeployPlatform} from "../script/DeployPlatform.s.sol";
import {SeriesFactory} from "../src/factory/SeriesFactory.sol";
import {WeRWATimelock} from "../src/governance/WeRWATimelock.sol";
import {ClaimIssuer} from "@onchain-id/solidity/contracts/ClaimIssuer.sol";
import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";
import {AuditAnchor} from "../src/AuditAnchor.sol";
import {TREXSuiteDeployer} from "../src/adapters/trex/TREXSuiteDeployer.sol";

/// @notice Script triển khai chạy trọn qua run() dưới broadcast (đúng đường của `forge script --broadcast`),
///         giao mọi quyền sở hữu cho timelock; khóa triển khai không giữ quyền gì (I1).
contract DeployPlatformTest is Test {
    address internal governance3of3 = makeAddr("3of3");
    address internal holcoSafe = makeAddr("Holco Safe");
    address internal holcoClaimSigner = makeAddr("Holco claim signer");
    address internal holcoOperator = makeAddr("Holco operator");

    DeployPlatform internal script;

    function setUp() public {
        script = new DeployPlatform();
        vm.setEnv("WERWA_GOVERNANCE_3OF3", vm.toString(governance3of3));
        vm.setEnv(
            "WERWA_CANCELLERS",
            string.concat(
                vm.toString(makeAddr("UBND")), ",", vm.toString(makeAddr("VSDC")), ",", vm.toString(makeAddr("Holco"))
            )
        );
        vm.setEnv("WERWA_HOLCO_SAFE", vm.toString(holcoSafe));
        vm.setEnv("WERWA_HOLCO_CLAIM_SIGNER", vm.toString(holcoClaimSigner));
        vm.setEnv("WERWA_HOLCO_OPERATOR", vm.toString(holcoOperator));
        vm.setEnv("WERWA_TIMELOCK_DELAY", "3600");
        vm.setEnv("WERWA_TOPIC_KYC", "1");
        vm.setEnv("WERWA_TOPIC_CLASS", "2");
    }

    /// @dev I1: run() phát giao dịch bằng msg.sender; người gọi run() ở đây là hợp đồng kiểm thử, đóng vai khóa triển khai.
    function test_I1_run_underBroadcast_handsEverythingToTimelock() public {
        address deployer = address(this);
        DeployPlatform.Deployed memory d = script.run();

        address[6] memory owned = [
            d.implementationAuthority,
            d.identityRegistry,
            d.identityRegistryStorage,
            d.claimTopicsRegistry,
            d.trustedIssuersRegistry,
            d.controllerBeacon
        ];
        for (uint256 i = 0; i < owned.length; i++) {
            assertEq(Ownable(owned[i]).owner(), d.timelock);
        }
        assertEq(Ownable(d.proxyAdmin).owner(), d.timelock);
        assertEq(SeriesFactory(d.seriesFactory).governance(), governance3of3);
        // ADR-0019: factory tạo token qua tokenDeployer (phương án A: TREXSuiteDeployer trên IA dùng chung).
        assertEq(address(SeriesFactory(d.seriesFactory).tokenDeployer()), d.tokenDeployer);
        assertEq(TREXSuiteDeployer(d.tokenDeployer).implementationAuthority(), d.implementationAuthority);
        assertEq(SeriesFactory(d.seriesFactory).identityRegistry(), d.identityRegistry);
        assertTrue(WeRWATimelock(payable(d.timelock)).hasRole(keccak256("PROPOSER_ROLE"), governance3of3));
        assertEq(WeRWATimelock(payable(d.timelock)).getMinDelay(), 3600);

        assertEq(AuditAnchor(d.auditAnchor).operator(), holcoOperator);
        assertEq(AuditAnchor(d.auditAnchor).governance(), governance3of3);

        ClaimIssuer issuer = ClaimIssuer(d.holcoClaimIssuer);
        assertTrue(issuer.keyHasPurpose(keccak256(abi.encode(holcoSafe)), 1));
        assertTrue(issuer.keyHasPurpose(keccak256(abi.encode(holcoClaimSigner)), 3));
        // Khóa triển khai đã tự gỡ quyền quản lý tạm; hợp đồng script không bao giờ là khóa quản lý.
        assertFalse(issuer.keyHasPurpose(keccak256(abi.encode(deployer)), 1));
        assertFalse(issuer.keyHasPurpose(keccak256(abi.encode(address(script))), 1));
    }

    /// @dev I1: mọi hợp đồng do khóa triển khai tạo (broadcast), không phải do hợp đồng script tạm.
    function test_I1_run_contractsCreatedByDeployerNotScript() public {
        address deployer = address(this);
        uint256 nonceBefore = vm.getNonce(deployer);
        DeployPlatform.Deployed memory d = script.run();
        assertGt(vm.getNonce(deployer), nonceBefore);
        assertEq(vm.getNonce(address(script)), 1); // script không tạo hợp đồng nào
        assertTrue(d.timelock.code.length > 0);
    }

    /// @dev I1: gọi deploy() trực tiếp, không broadcast, thì khóa quản lý tạm (người gọi) khác hợp đồng script
    ///      đang gọi addKey, nên revert. Kiểm thử cũ dựa vào đường này nên không bắt được lỗi.
    function test_I1_deployWithoutBroadcast_reverts() public {
        DeployPlatform.Config memory cfg;
        cfg.governance3of3 = governance3of3;
        cfg.cancellers = new address[](0);
        cfg.holcoSafe = holcoSafe;
        cfg.holcoClaimSigner = holcoClaimSigner;
        cfg.holcoOperator = holcoOperator;
        cfg.timelockDelay = 1;
        cfg.kycTopic = 1;
        cfg.classTopic = 2;
        vm.expectRevert("Permissions: Sender does not have management key");
        script.deploy(cfg);
    }
}
