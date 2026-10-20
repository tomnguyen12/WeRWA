// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

/// @title SeriesLeaves
/// @notice Mã băm lá của cây phân bổ (UBND ký) và cây lô tiền (BIDV ký).
///         Đúng docs/design/02-mo-hinh-du-lieu.md, mục "Mã băm lá của danh sách phân bổ"; băm hai lần theo OpenZeppelin.
///         Backend dựng cây bằng đúng công thức này (test vector trong test/SeriesLeaves.t.sol).
library SeriesLeaves {
    /// @notice leaf = keccak256(bytes.concat(keccak256(abi.encode(seriesController, allocationId, wallet, quantity))))
    function allocationLeaf(address seriesController, bytes32 allocationId, address wallet, uint256 quantity)
        internal
        pure
        returns (bytes32)
    {
        return keccak256(bytes.concat(keccak256(abi.encode(seriesController, allocationId, wallet, quantity))));
    }

    /// @notice paymentLeaf = keccak256(bytes.concat(keccak256(abi.encode(seriesController, allocationId, amountMinor, bankRefHash))))
    function paymentLeaf(address seriesController, bytes32 allocationId, uint256 amountMinor, bytes32 bankRefHash)
        internal
        pure
        returns (bytes32)
    {
        return keccak256(bytes.concat(keccak256(abi.encode(seriesController, allocationId, amountMinor, bankRefHash))));
    }
}
