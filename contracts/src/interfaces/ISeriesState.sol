// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

/// @notice Trạng thái trên chuỗi của series, theo docs/design/03-may-trang-thai.md, mục "Trạng thái trên chuỗi của series".
/// @dev Thứ tự giá trị là một phần của ABI; không đổi thứ tự, chỉ thêm ở cuối.
enum SeriesState {
    DEPLOYED,
    READY,
    ALLOCATED,
    ISSUING,
    ACTIVE,
    DEFAULTED,
    MATURED,
    CLOSED,
    CANCELLED
}

/// @notice Phần đọc của SeriesController mà module tuân thủ cần.
interface ISeriesState {
    /// @notice Trạng thái hiện tại của series.
    function state() external view returns (SeriesState);

    /// @notice VSDC đã ký xác nhận sổ sau phát hành (attestRegister).
    function isRegisterAttested() external view returns (bool);
}
