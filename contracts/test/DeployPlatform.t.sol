// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {Test} from "forge-std/Test.sol";
import {DeployPlatform} from "../script/DeployPlatform.s.sol";
import {SeriesFactory} from "../src/factory/SeriesFactory.sol";
import {WeRWATimelock} from "../src/governance/WeRWATimelock.sol";
import {ClaimIssuer} from "@onchain-id/solidity/contracts/ClaimIssuer.sol";
import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";

/// @notice Script triển khai chạy trọn và giao mọi quyền sở hữu cho timelock; khóa triển khai không giữ quyền gì.
contract DeployPlatformTest is Test {
    function test_deploy_handsEverythingToTimelock() public {
        DeployPlatform script = new DeployPlatform();
        DeployPlatform.Config memory cfg;
        cfg.governance3of3 = makeAddr("3of3");
        cfg.cancellers = new address[](3);
        cfg.cancellers[0] = makeAddr("UBND");
        cfg.cancellers[1] = makeAddr("VSDC");
        cfg.cancellers[2] = makeAddr("Holco");
        cfg.holcoSafe = makeAddr("Holco Safe");
        cfg.holcoClaimSigner = makeAddr("Holco claim signer");
        cfg.holcoOperator = makeAddr("Holco operator");
        cfg.timelockDelay = 1 hours;
        cfg.kycTopic = 1;
        cfg.classTopic = 2;

        DeployPlatform.Deployed memory d = script.deploy(cfg);

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
        assertEq(SeriesFactory(d.seriesFactory).governance(), cfg.governance3of3);
        assertTrue(WeRWATimelock(payable(d.timelock)).hasRole(keccak256("PROPOSER_ROLE"), cfg.governance3of3));

        ClaimIssuer issuer = ClaimIssuer(d.holcoClaimIssuer);
        assertTrue(issuer.keyHasPurpose(keccak256(abi.encode(cfg.holcoSafe)), 1));
        assertTrue(issuer.keyHasPurpose(keccak256(abi.encode(cfg.holcoClaimSigner)), 3));
        assertFalse(issuer.keyHasPurpose(keccak256(abi.encode(address(script))), 1));
    }
}
