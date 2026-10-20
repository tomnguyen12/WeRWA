// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {SeriesFixture} from "./utils/SeriesFixture.sol";
import {Token} from "@trex/token/Token.sol";
import {ModularCompliance} from "@trex/compliance/modular/ModularCompliance.sol";
import {MerkleHelper} from "./utils/MerkleHelper.sol";
import {MerkleProof} from "@openzeppelin/contracts/utils/cryptography/MerkleProof.sol";
import {SeriesController} from "../src/SeriesController.sol";
import {ISeriesController} from "../src/ISeriesController.sol";
import {SeriesState} from "../src/interfaces/ISeriesState.sol";
import {SeriesLeaves} from "../src/libraries/SeriesLeaves.sol";
import {BeaconProxy} from "@openzeppelin/contracts/proxy/beacon/BeaconProxy.sol";
import {IsinLib} from "../src/libraries/IsinLib.sol";
import {Test} from "forge-std/Test.sol";

contract SeriesControllerIssuanceTest is SeriesFixture {
    event StateChanged(SeriesState indexed from, SeriesState indexed to);
    event SeriesDeployed(
        bytes32 indexed seriesId, address indexed token, bytes32 termsHash, uint64 maxQuantity, uint16 periodCount
    );
    event IsinAssigned(bytes12 isin);
    event Minted(
        bytes32 indexed allocationId, address indexed wallet, uint64 quantity, uint256 amountMinor, bytes32 paymentRoot
    );
    event SeriesCancelled(bytes32 decisionHash);
    event PaymentAttestationToggled(bool required);

    address internal alice;
    address internal bob;
    address internal carol;
    address internal stranger = makeAddr("stranger");
    Alloc[] internal allocs;

    function setUp() public {
        _deploySeries();
        alice = _investor("alice", CLASS_FOREIGN_INSTITUTION);
        bob = _investor("bob", CLASS_VIFC_MEMBER);
        carol = _investor("carol", CLASS_FOREIGN_INSTITUTION);
        allocs.push(Alloc(keccak256("alloc-1"), alice, 1000, keccak256("BIDV-REF-0001")));
        allocs.push(Alloc(keccak256("alloc-2"), bob, 2500, keccak256("BIDV-REF-0002")));
        allocs.push(Alloc(keccak256("alloc-3"), carol, 500, keccak256("BIDV-REF-0003")));
    }

    function _a() internal view returns (Alloc[] memory a) {
        a = allocs;
    }

    // =====================================================================
    // initialize
    // =====================================================================

    // US-2.07-AC1 (phần trên chuỗi): triển khai xong thì series ở DEPLOYED, lưu điều khoản và vai.
    function test_US_2_07_AC1_initializeStoresTermsAndRoles() public view {
        assertEq(uint8(_state()), uint8(SeriesState.DEPLOYED));
        assertEq(address(controller.token()), address(token));
        assertEq(address(controller.identityRegistry()), address(identityRegistry));
        assertEq(controller.seriesId(), keccak256("series-2027A"));
        assertEq(controller.termsHash(), sha256("term sheet 2027A"));
        assertEq(controller.currency(), bytes3("USD"));
        assertEq(controller.faceValueMinor(), FACE_VALUE_MINOR);
        assertEq(controller.issuePricePpm(), ISSUE_PRICE_PPM);
        assertEq(controller.maxQuantity(), MAX_QUANTITY);
        assertEq(controller.rateFloorPpm(), RATE_FLOOR);
        assertEq(controller.rateCapPpm(), RATE_CAP);
        assertEq(controller.periodCount(), PERIODS);
        assertEq(controller.issuer(), ubnd);
        assertEq(controller.registrar(), vsdc);
        assertEq(controller.payingAgent(), bidv);
        assertEq(controller.operator(), holcoOperator);
        assertEq(controller.enforcement(), enforcement2of2);
        assertEq(controller.governance(), governance3of3);
        assertTrue(controller.paymentAttestationRequired());
        assertFalse(controller.paused());
        assertFalse(controller.isRegisterAttested());
    }

    // C16: token T-REX được mở tạm dừng khi khởi tạo; khóa chuyển nhượng do TransferGateModule giữ.
    function test_C16_initializeUnpausesToken() public view {
        assertFalse(token.paused());
    }

    function test_initialize_emitsSeriesDeployed() public {
        SeriesController c = _freshProxy();
        (address t,) = _tokenFor(address(c));
        ISeriesController.SeriesTerms memory terms = _defaultTerms();
        vm.expectEmit(true, true, false, true, address(c));
        emit SeriesDeployed(terms.seriesId, t, terms.termsHash, terms.maxQuantity, terms.periodCount);
        vm.expectEmit(true, true, false, false, address(c));
        emit StateChanged(SeriesState.DEPLOYED, SeriesState.DEPLOYED);
        c.initialize(terms, t);
    }

    function test_initialize_onlyOnce() public {
        ISeriesController.SeriesTerms memory t = _defaultTerms();
        vm.expectRevert(ISeriesController.AlreadyInitialized.selector);
        controller.initialize(t, address(token));
    }

    function test_initialize_implementationLocked() public {
        SeriesController impl = SeriesController(beacon.implementation());
        ISeriesController.SeriesTerms memory t = _defaultTerms();
        vm.expectRevert(ISeriesController.AlreadyInitialized.selector);
        impl.initialize(t, address(token));
    }

    function test_initialize_rejectsZeroAddresses() public {
        ISeriesController.SeriesTerms memory t = _defaultTerms();
        SeriesController c = _freshProxy();
        vm.expectRevert(ISeriesController.ZeroAddress.selector);
        c.initialize(t, address(0));

        address[6] memory saved = [t.issuer, t.registrar, t.payingAgent, t.operator, t.enforcement, t.governance];
        for (uint256 i = 0; i < 6; i++) {
            t = _defaultTerms();
            if (i == 0) t.issuer = address(0);
            if (i == 1) t.registrar = address(0);
            if (i == 2) t.payingAgent = address(0);
            if (i == 3) t.operator = address(0);
            if (i == 4) t.enforcement = address(0);
            if (i == 5) t.governance = address(0);
            c = _freshProxy();
            vm.expectRevert(ISeriesController.ZeroAddress.selector);
            c.initialize(t, address(token));
        }
        saved; // giữ để dễ đọc
    }

    function test_initialize_rejectsInvalidTerms() public {
        for (uint256 i = 0; i < 6; i++) {
            ISeriesController.SeriesTerms memory t = _defaultTerms();
            if (i == 0) t.maxQuantity = 0;
            if (i == 1) t.faceValueMinor = 0;
            if (i == 2) t.issuePricePpm = 0;
            if (i == 3) t.periodCount = 0;
            if (i == 4) t.rateFloorPpm = RATE_CAP + 1;
            if (i == 5) t.currency = bytes3(0);
            SeriesController c = _freshProxy();
            vm.expectRevert(ISeriesController.InvalidTerms.selector);
            c.initialize(t, address(token));
        }
    }

    function test_initialize_rejectsTokenWithDecimals() public {
        SeriesController c = _freshProxy();
        vm.mockCall(address(token), abi.encodeWithSignature("decimals()"), abi.encode(uint8(18)));
        ISeriesController.SeriesTerms memory t = _defaultTerms();
        vm.expectRevert(ISeriesController.InvalidTerms.selector);
        c.initialize(t, address(token));
    }

    // =====================================================================
    // setIsin
    // =====================================================================

    // US-2.07-AC2: VSDC nhập ISIN 12 ký tự đúng ISO 6166 thì sang READY; nhập lần hai bị từ chối.
    function test_US_2_07_AC2_setIsinOnceMovesToReady() public {
        vm.expectEmit(false, false, false, true, address(controller));
        emit IsinAssigned(ISIN);
        vm.expectEmit(true, true, false, false, address(controller));
        emit StateChanged(SeriesState.DEPLOYED, SeriesState.READY);
        vm.prank(vsdc);
        controller.setIsin(ISIN);
        assertEq(controller.isin(), ISIN);
        assertEq(uint8(_state()), uint8(SeriesState.READY));

        vm.prank(vsdc);
        vm.expectRevert(ISeriesController.IsinAlreadySet.selector);
        controller.setIsin("VN0000000001");
    }

    // US-2.07-AC2 / C7: chữ số kiểm tra sai thì từ chối.
    function test_US_2_07_AC2_setIsinRejectsBadCheckDigit() public {
        vm.prank(vsdc);
        vm.expectRevert(ISeriesController.InvalidIsin.selector);
        controller.setIsin("US0378331006");
    }

    function test_setIsin_rejectsDigitInCountryCodeAndBadChars() public {
        vm.startPrank(vsdc);
        vm.expectRevert(ISeriesController.InvalidIsin.selector);
        controller.setIsin("1S0378331005");
        vm.expectRevert(ISeriesController.InvalidIsin.selector);
        controller.setIsin("US03783310-5");
        vm.expectRevert(ISeriesController.InvalidIsin.selector);
        controller.setIsin("US037833100A");
        vm.stopPrank();
    }

    function test_isin_knownValidCodes() public {
        // Mã thật đúng ISO 6166.
        vm.prank(vsdc);
        controller.setIsin("DE000BAY0017");
        assertEq(controller.isin(), bytes12("DE000BAY0017"));
    }

    function test_setIsin_onlyRegistrar() public {
        vm.prank(ubnd);
        vm.expectRevert(ISeriesController.Unauthorized.selector);
        controller.setIsin(ISIN);
    }

    function test_setIsin_badStateAfterCancel() public {
        vm.prank(governance3of3);
        controller.cancel(keccak256("decision"));
        vm.prank(vsdc);
        vm.expectRevert(ISeriesController.BadState.selector);
        controller.setIsin(ISIN);
    }

    // =====================================================================
    // setAllocation
    // =====================================================================

    // US-3.03-AC3 (phần trên chuỗi): UBND ký gốc danh sách hợp lệ thì series sang ALLOCATED.
    function test_US_3_03_AC3_setAllocationMovesToAllocated() public {
        _toAllocated(_a());
        assertEq(uint8(_state()), uint8(SeriesState.ALLOCATED));
        assertEq(controller.allocationRoot(), MerkleHelper.root(_allocLeaves(_a())));
        assertEq(controller.allocationListHash(), keccak256("allocation list v3"));
        assertEq(controller.totalAllocated(), 4000);
        uint32[] memory r = controller.periodRates();
        assertEq(r.length, PERIODS);
        assertEq(r[0], 51_250);
    }

    // US-3.03-AC2 (phần trên chuỗi): tổng vượt khối lượng series bị hợp đồng từ chối.
    function test_US_3_03_AC2_allocationExceedsMaxRejected() public {
        _toReady();
        vm.prank(ubnd);
        vm.expectRevert(ISeriesController.ExceedsMaxQuantity.selector);
        controller.setAllocation(bytes32(uint256(1)), bytes32(0), MAX_QUANTITY + 1, _rates(50_000));
    }

    // US-3.03-AC2 (phần trên chuỗi): lãi suất ngoài khung bị hợp đồng từ chối.
    function test_US_3_03_AC2_rateOutOfRangeRejected() public {
        _toReady();
        uint32[] memory r = _rates(50_000);
        r[3] = RATE_CAP + 1;
        vm.prank(ubnd);
        vm.expectRevert(ISeriesController.RateOutOfRange.selector);
        controller.setAllocation(bytes32(uint256(1)), bytes32(0), 1, r);

        r[3] = RATE_FLOOR - 1;
        vm.prank(ubnd);
        vm.expectRevert(ISeriesController.RateOutOfRange.selector);
        controller.setAllocation(bytes32(uint256(1)), bytes32(0), 1, r);
    }

    function test_setAllocation_periodCountMismatch() public {
        _toReady();
        vm.prank(ubnd);
        vm.expectRevert(ISeriesController.PeriodCountMismatch.selector);
        controller.setAllocation(bytes32(uint256(1)), bytes32(0), 1, new uint32[](PERIODS - 1));
    }

    function test_setAllocation_onlyIssuerAndReady() public {
        vm.prank(ubnd);
        vm.expectRevert(ISeriesController.BadState.selector);
        controller.setAllocation(bytes32(uint256(1)), bytes32(0), 1, _rates(50_000));
        _toReady();
        vm.prank(stranger);
        vm.expectRevert(ISeriesController.Unauthorized.selector);
        controller.setAllocation(bytes32(uint256(1)), bytes32(0), 1, _rates(50_000));
    }

    // US-3.03-AC4: nhà đầu tư tự kiểm được phân bổ khớp gốc danh sách trên chuỗi.
    function test_US_3_03_AC4_investorVerifiesProofAgainstOnchainRoot() public {
        Alloc[] memory a = _a();
        _toAllocated(a);
        bytes32 leaf = SeriesLeaves.allocationLeaf(address(controller), a[1].allocationId, a[1].wallet, a[1].quantity);
        assertEq(leaf, controller.allocationLeaf(a[1].allocationId, a[1].wallet, a[1].quantity));
        assertTrue(MerkleProof.verify(MerkleHelper.proof(_allocLeaves(a), 1), controller.allocationRoot(), leaf));
    }

    // =====================================================================
    // addPaymentBatch
    // =====================================================================

    function test_addPaymentBatch_storesRootOnce() public {
        _toAllocated(_a());
        bytes32 root = _addBatch(_a());
        assertEq(controller.paymentBatchHash(root), keccak256(abi.encode("batch", root)));
        vm.prank(bidv);
        vm.expectRevert(ISeriesController.PaymentRootExists.selector);
        controller.addPaymentBatch(root, keccak256("again"));
    }

    function test_addPaymentBatch_rejectsZeroAndRoleAndState() public {
        vm.prank(bidv);
        vm.expectRevert(ISeriesController.BadState.selector);
        controller.addPaymentBatch(bytes32(uint256(1)), bytes32(uint256(2)));

        _toAllocated(_a());
        vm.prank(bidv);
        vm.expectRevert(ISeriesController.InvalidPaymentProof.selector);
        controller.addPaymentBatch(bytes32(0), bytes32(uint256(2)));
        vm.prank(bidv);
        vm.expectRevert(ISeriesController.InvalidPaymentProof.selector);
        controller.addPaymentBatch(bytes32(uint256(1)), bytes32(0));
        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.Unauthorized.selector);
        controller.addPaymentBatch(bytes32(uint256(1)), bytes32(uint256(2)));
    }

    function test_addPaymentBatch_allowedWhileIssuing() public {
        Alloc[] memory a = _a();
        _toAllocated(a);
        _mintAt(a, 0, _addBatch(a));
        assertEq(uint8(_state()), uint8(SeriesState.ISSUING));
        vm.prank(bidv);
        controller.addPaymentBatch(keccak256("second batch"), keccak256("h"));
        assertEq(controller.paymentBatchHash(keccak256("second batch")), keccak256("h"));
    }

    // =====================================================================
    // mint
    // =====================================================================

    // US-3.05-AC3: BIDV đã ký lô thì mỗi phân bổ mint đúng một lần với hai đường chứng minh; trái phiếu bị khóa.
    function test_US_3_05_AC3_mintOncePerAllocationWithTwoProofs() public {
        Alloc[] memory a = _a();
        _toAllocated(a);
        bytes32 root = _addBatch(a);

        vm.expectEmit(true, true, false, false, address(controller));
        emit StateChanged(SeriesState.ALLOCATED, SeriesState.ISSUING);
        vm.expectEmit(true, true, false, true, address(controller));
        emit Minted(a[0].allocationId, alice, 1000, _amount(1000), root);
        _mintAt(a, 0, root);

        assertEq(token.balanceOf(alice), 1000);
        assertEq(controller.totalMinted(), 1000);
        assertTrue(controller.isMinted(a[0].allocationId));
        ISeriesController.MintRecord memory r = controller.mintRecord(a[0].allocationId);
        assertEq(r.wallet, alice);
        assertEq(r.quantity, 1000);
        assertEq(uint8(_state()), uint8(SeriesState.ISSUING));

        vm.expectRevert(ISeriesController.AlreadyMinted.selector);
        _mintAt(a, 0, root);

        vm.prank(alice);
        vm.expectRevert("Transfer not possible");
        token.transfer(bob, 1);
    }

    // US-3.05-AC4 (cảnh demo ⑥): Holco mint mục không có trong danh sách thì hợp đồng từ chối.
    function test_US_3_05_AC4_mintOutsideAllocationListRejected() public {
        Alloc[] memory a = _a();
        _toAllocated(a);
        bytes32 root = _addBatch(a);
        ISeriesController.MintItem memory item = ISeriesController.MintItem({
            allocationId: a[0].allocationId,
            wallet: stranger,
            quantity: a[0].quantity,
            amountMinor: _amount(a[0].quantity),
            bankRefHash: a[0].bankRefHash
        });
        bytes32[] memory ap = MerkleHelper.proof(_allocLeaves(a), 0);
        bytes32[] memory pp = MerkleHelper.proof(_paymentLeaves(a), 0);
        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.InvalidAllocationProof.selector);
        controller.mint(item, ap, root, pp);

        item.wallet = alice;
        item.quantity = a[0].quantity + 1; // vượt số lượng đã phân bổ
        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.InvalidAllocationProof.selector);
        controller.mint(item, ap, root, pp);
    }

    // US-3.05-AC4 (cảnh demo ⑥): mục chưa có trong lô BIDV thì hợp đồng từ chối.
    function test_US_3_05_AC4_mintWithoutBidvBatchRejected() public {
        Alloc[] memory a = _a();
        _toAllocated(a);
        Alloc[] memory onlyFirst = new Alloc[](1);
        onlyFirst[0] = a[0];
        bytes32 root = _addBatch(onlyFirst); // BIDV mới xác nhận tiền của alloc-1

        // alloc-2 chưa có trong lô nào
        ISeriesController.MintItem memory item = ISeriesController.MintItem({
            allocationId: a[1].allocationId,
            wallet: a[1].wallet,
            quantity: a[1].quantity,
            amountMinor: _amount(a[1].quantity),
            bankRefHash: a[1].bankRefHash
        });
        bytes32[] memory ap = MerkleHelper.proof(_allocLeaves(a), 1);
        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.InvalidPaymentProof.selector);
        controller.mint(item, ap, root, new bytes32[](0));

        // gốc lô chưa được BIDV ký
        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.InvalidPaymentProof.selector);
        controller.mint(item, ap, keccak256("unsigned root"), new bytes32[](0));
    }

    function test_mint_amountMismatchRejected() public {
        Alloc[] memory a = _a();
        _toAllocated(a);
        // Lô BIDV ghi thiếu 1 cent cho alloc-1.
        bytes32[] memory pleaves = new bytes32[](1);
        pleaves[0] = controller.paymentLeaf(a[0].allocationId, _amount(a[0].quantity) - 1, a[0].bankRefHash);
        bytes32 root = MerkleHelper.root(pleaves);
        vm.prank(bidv);
        controller.addPaymentBatch(root, keccak256("short batch"));

        ISeriesController.MintItem memory item = ISeriesController.MintItem({
            allocationId: a[0].allocationId,
            wallet: alice,
            quantity: a[0].quantity,
            amountMinor: _amount(a[0].quantity) - 1,
            bankRefHash: a[0].bankRefHash
        });
        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.AmountMismatch.selector);
        controller.mint(item, MerkleHelper.proof(_allocLeaves(a), 0), root, new bytes32[](0));
    }

    // C5: tiền phải chia hết; số lượng làm tiền lẻ phần cent thì AmountMismatch.
    function test_C5_expectedAmountExactness() public view {
        (uint256 amt, bool exact) = controller.expectedAmount(1);
        assertEq(amt, 99_500);
        assertTrue(exact);
    }

    function test_C5_nonDivisibleAmountRejected() public {
        ISeriesController.SeriesTerms memory t = _defaultTerms();
        t.faceValueMinor = 1; // 1 cent × 99,5% không chia hết
        SeriesController c = _freshProxy();
        (address tk,) = _tokenFor(address(c));
        c.initialize(t, tk);
        (, bool exact) = c.expectedAmount(1);
        assertFalse(exact);
        (, exact) = c.expectedAmount(2);
        assertFalse(exact);
        uint256 amt;
        (amt, exact) = c.expectedAmount(200); // 200 × 1 × 99,5% = 199 cent
        assertTrue(exact);
        assertEq(amt, 199);
    }

    function test_mint_exceedsAllocatedRejected() public {
        // Danh sách có tổng lớn hơn totalQuantity khai báo: hợp đồng chặn ở totalAllocated.
        Alloc[] memory a = _a();
        _toReady();
        vm.prank(ubnd);
        controller.setAllocation(MerkleHelper.root(_allocLeaves(a)), keccak256("list"), 1500, _rates(51_250));
        bytes32 root = _addBatch(a);
        _mintAt(a, 0, root); // 1 000
        vm.expectRevert(ISeriesController.ExceedsAllocated.selector);
        _mintAt(a, 1, root); // +2 500 > 1 500
    }

    function test_mint_unverifiedWalletRejected() public {
        address ghost = makeAddr("ghost");
        Alloc[] memory a = new Alloc[](1);
        a[0] = Alloc(keccak256("alloc-ghost"), ghost, 10, keccak256("REF-G"));
        _toAllocated(a);
        bytes32 root = _addBatch(a);
        vm.expectRevert(ISeriesController.NotVerified.selector);
        _mintAt(a, 0, root);
    }

    function test_mint_onlyOperatorAndState() public {
        Alloc[] memory a = _a();
        ISeriesController.MintItem memory item;
        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.BadState.selector);
        controller.mint(item, new bytes32[](0), bytes32(0), new bytes32[](0));

        _toAllocated(a);
        vm.prank(ubnd);
        vm.expectRevert(ISeriesController.Unauthorized.selector);
        controller.mint(item, new bytes32[](0), bytes32(0), new bytes32[](0));
    }

    // Module tuân thủ vẫn chặn khi mint: ví có loại không được phép dù nằm trong danh sách.
    function test_mint_complianceStillApplies() public {
        address bad = _investor("badClass", CLASS_NOT_ELIGIBLE);
        Alloc[] memory a = new Alloc[](1);
        a[0] = Alloc(keccak256("alloc-bad"), bad, 10, keccak256("REF-B"));
        _toAllocated(a);
        bytes32 root = _addBatch(a);
        vm.expectRevert("Compliance not followed");
        _mintAt(a, 0, root);
    }

    // Tắt điều kiện BIDV (BIDV không ký): mint chỉ cần chứng minh phân bổ.
    function test_paymentAttestationOff_mintWithoutBatch() public {
        Alloc[] memory a = _a();
        _toAllocated(a);
        vm.expectEmit(false, false, false, true, address(controller));
        emit PaymentAttestationToggled(false);
        vm.prank(governance3of3);
        controller.setPaymentAttestationRequired(false);

        ISeriesController.MintItem memory item = ISeriesController.MintItem({
            allocationId: a[0].allocationId,
            wallet: alice,
            quantity: a[0].quantity,
            amountMinor: 0,
            bankRefHash: bytes32(0)
        });
        vm.prank(holcoOperator);
        controller.mint(item, MerkleHelper.proof(_allocLeaves(a), 0), bytes32(0), new bytes32[](0));
        assertEq(token.balanceOf(alice), 1000);
    }

    function test_setPaymentAttestationRequired_rules() public {
        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.Unauthorized.selector);
        controller.setPaymentAttestationRequired(false);

        _toActive(_a());
        vm.prank(governance3of3);
        vm.expectRevert(ISeriesController.BadState.selector);
        controller.setPaymentAttestationRequired(false);
    }

    // =====================================================================
    // correctionBurn
    // =====================================================================

    // Máy Series cạnh 10: VSDC từ chối, Holco hủy mục sai rồi mint lại trong phạm vi danh sách.
    function test_correctionBurn_reopensAllocation() public {
        Alloc[] memory a = _a();
        _toAllocated(a);
        bytes32 root = _addBatch(a);
        _mintAt(a, 0, root);
        _mintAt(a, 1, root);

        vm.expectEmit(true, true, false, false, address(controller));
        emit StateChanged(SeriesState.ISSUING, SeriesState.ISSUING);
        vm.prank(holcoOperator);
        controller.correctionBurn(a[0].allocationId, alice, 1000);

        assertEq(token.balanceOf(alice), 0);
        assertEq(controller.totalMinted(), 2500);
        assertFalse(controller.isMinted(a[0].allocationId));
        assertEq(token.totalSupply(), controller.totalMinted());

        _mintAt(a, 0, root);
        assertEq(token.balanceOf(alice), 1000);
    }

    function test_correctionBurn_checks() public {
        Alloc[] memory a = _a();
        _toAllocated(a);
        bytes32 root = _addBatch(a);
        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.BadState.selector);
        controller.correctionBurn(a[0].allocationId, alice, 1000);

        _mintAt(a, 0, root);
        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.NotMinted.selector);
        controller.correctionBurn(a[1].allocationId, bob, 2500);
        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.MismatchedItem.selector);
        controller.correctionBurn(a[0].allocationId, bob, 1000);
        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.MismatchedItem.selector);
        controller.correctionBurn(a[0].allocationId, alice, 999);
        vm.prank(stranger);
        vm.expectRevert(ISeriesController.Unauthorized.selector);
        controller.correctionBurn(a[0].allocationId, alice, 1000);
    }

    // =====================================================================
    // attestRegister
    // =====================================================================

    // US-3.05-AC5: mọi mục có tiền đã mint, VSDC ký xác nhận sổ thì sang ACTIVE, mở chuyển nhượng.
    function test_US_3_05_AC5_attestRegisterActivatesAndOpensTransfers() public {
        _toActive(_a());
        assertEq(uint8(_state()), uint8(SeriesState.ACTIVE));
        assertTrue(controller.isRegisterAttested());
        ISeriesController.RegistrarAttestation memory att = controller.registerAttestation();
        assertEq(att.registerHash, keccak256("register after issuance"));
        assertEq(att.registrar, vsdc);

        vm.prank(alice);
        token.transfer(carol, 100);
        assertEq(token.balanceOf(carol), 600);
    }

    function test_attestRegister_checks() public {
        vm.prank(vsdc);
        vm.expectRevert(ISeriesController.BadState.selector);
        controller.attestRegister(bytes32(0), 0);

        Alloc[] memory a = _a();
        _toAllocated(a);
        _mintAt(a, 0, _addBatch(a));
        vm.prank(vsdc);
        vm.expectRevert(ISeriesController.InvalidBlock.selector);
        controller.attestRegister(bytes32(0), uint64(block.number + 1));
        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.Unauthorized.selector);
        controller.attestRegister(bytes32(0), uint64(block.number));
    }

    function test_mintBlockedAfterActive() public {
        Alloc[] memory a = _a();
        _toActive(a);
        vm.expectRevert(ISeriesController.BadState.selector);
        _mintAt(a, 0, bytes32(0));
    }

    // =====================================================================
    // cancel
    // =====================================================================

    function test_cancel_fromDeployedReadyAllocated() public {
        for (uint256 s = 0; s < 3; s++) {
            if (s > 0) _deploySeries();
            if (s >= 1) _toReady();
            if (s == 2) {
                vm.prank(ubnd);
                controller.setAllocation(bytes32(uint256(1)), bytes32(0), 1, _rates(50_000));
            }
            vm.expectEmit(false, false, false, true, address(controller));
            emit SeriesCancelled(keccak256("UBND decision"));
            vm.prank(governance3of3);
            controller.cancel(keccak256("UBND decision"));
            assertEq(uint8(_state()), uint8(SeriesState.CANCELLED));
        }
    }

    // I8: CANCELLED chỉ đạt được khi chưa mint; sau mục đầu tiên series ở ISSUING, hủy bị từ chối.
    function test_I8_cancelRejectedAfterFirstMint() public {
        Alloc[] memory a = _a();
        _toAllocated(a);
        _mintAt(a, 0, _addBatch(a));
        vm.prank(governance3of3);
        vm.expectRevert(ISeriesController.BadState.selector);
        controller.cancel(keccak256("late"));
    }

    function test_cancel_onlyGovernance() public {
        vm.prank(ubnd);
        vm.expectRevert(ISeriesController.Unauthorized.selector);
        controller.cancel(keccak256("x"));
    }

    // US-2.07-AC3: chuyển không có trong sơ đồ bị từ chối khi gửi thẳng lên chuỗi.
    function test_US_2_07_AC3_offDiagramTransitionsRejected() public {
        // DEPLOYED: không ghi phân bổ, không xác nhận sổ, không mint
        vm.prank(ubnd);
        vm.expectRevert(ISeriesController.BadState.selector);
        controller.setAllocation(bytes32(uint256(1)), bytes32(0), 1, _rates(50_000));
        vm.prank(vsdc);
        vm.expectRevert(ISeriesController.BadState.selector);
        controller.attestRegister(bytes32(0), 0);

        _toReady();
        // READY: không gắn ISIN lần hai, không lô BIDV, không mint
        vm.prank(bidv);
        vm.expectRevert(ISeriesController.BadState.selector);
        controller.addPaymentBatch(bytes32(uint256(1)), bytes32(uint256(1)));

        vm.prank(governance3of3);
        controller.cancel(keccak256("d"));
        // CANCELLED: chỉ đọc
        vm.prank(enforcement2of2);
        vm.expectRevert(ISeriesController.BadState.selector);
        controller.pause();
        vm.prank(governance3of3);
        vm.expectRevert(ISeriesController.BadState.selector);
        controller.cancel(keccak256("d"));
    }

    // =====================================================================
    // pause / unpause (G5, C6)
    // =====================================================================

    function test_pause_blocksMintAndTransfers_unpauseByGovernance() public {
        Alloc[] memory a = _a();
        _toAllocated(a);
        bytes32 root = _addBatch(a);
        _mintAt(a, 0, root);

        vm.prank(enforcement2of2);
        controller.pause();
        assertTrue(controller.paused());
        assertTrue(token.paused());
        vm.expectRevert(ISeriesController.SeriesPaused.selector);
        _mintAt(a, 1, root);
        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.SeriesPaused.selector);
        controller.correctionBurn(a[0].allocationId, alice, 1000);

        // ENFORCEMENT không tự mở lại được; chỉ 3/3.
        vm.prank(enforcement2of2);
        vm.expectRevert(ISeriesController.Unauthorized.selector);
        controller.unpause();
        vm.prank(governance3of3);
        controller.unpause();
        assertFalse(controller.paused());
        assertFalse(token.paused());
        _mintAt(a, 1, root);
    }

    function test_pause_pausesTokenTransfersWhenActive() public {
        _toActive(_a());
        vm.prank(enforcement2of2);
        controller.pause();
        vm.prank(alice);
        vm.expectRevert("Pausable: paused");
        token.transfer(bob, 1);
    }

    function test_pause_idempotentAndRoles() public {
        vm.prank(governance3of3);
        vm.expectRevert(ISeriesController.Unauthorized.selector);
        controller.pause();
        vm.startPrank(enforcement2of2);
        controller.pause();
        controller.pause(); // lần hai vẫn phát sự kiện, không lỗi
        vm.stopPrank();
        vm.startPrank(governance3of3);
        controller.unpause();
        controller.unpause();
        vm.stopPrank();
        assertFalse(controller.paused());
    }

    // =====================================================================
    // Hỗ trợ
    // =====================================================================

    function _freshProxy() internal returns (SeriesController c) {
        c = SeriesController(address(new BeaconProxy(address(beacon), "")));
    }

    function _tokenFor(address agent_) internal returns (address t, address comp) {
        (Token tk, ModularCompliance mc) = _deployToken("fresh", "FRSH", agent_);
        return (address(tk), address(mc));
    }
}

/// @notice So IsinLib với cách tính chuẩn trên nhiều mã thật (đối chiếu bằng thuật toán Luhn tham chiếu).
contract IsinLibTest {
    function check(bytes12 isin) external pure returns (bool) {
        return IsinLib.isValid(isin);
    }
}

contract IsinLibVectorsTest is Test {
    IsinLibTest internal lib = new IsinLibTest();

    function test_validVectors() public view {
        assertTrue(lib.check("US0378331005"));
        assertTrue(lib.check("DE000BAY0017"));
        assertTrue(lib.check("GB0002634946"));
    }

    function test_invalidVectors() public view {
        assertFalse(lib.check("US0378331006"));
        assertFalse(lib.check("XS2238342143"));
        assertFalse(lib.check(bytes12(0)));
    }
}
