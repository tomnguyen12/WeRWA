#!/usr/bin/env python3
"""Tính mã TOTP (RFC 6238) cho người dùng thử của realm werwa. Chỉ dùng thư viện chuẩn.

    totp.py <secret> [--offset N] [--digits 6] [--period 30] [--algorithm SHA1]

<secret> là chuỗi khóa đúng như Keycloak lưu (trường "value" trong secretData của credential OTP).
Keycloak lấy byte UTF-8 của chuỗi này làm khóa HMAC, nên mã QR/URI otpauth mang base32 của các byte đó.
--offset N lùi/tiến N bước thời gian (kiểm thử OTP cũ hoặc OTP tương lai).
"""
import argparse
import hashlib
import hmac
import struct
import sys
import time


def totp(secret: str, at: float, digits: int = 6, period: int = 30, algorithm: str = "SHA1") -> str:
    counter = int(at) // period
    mac = hmac.new(secret.encode("utf-8"), struct.pack(">Q", counter), getattr(hashlib, algorithm.lower())).digest()
    offset = mac[-1] & 0x0F
    code = (struct.unpack(">I", mac[offset:offset + 4])[0] & 0x7FFFFFFF) % (10 ** digits)
    return str(code).zfill(digits)


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("secret")
    p.add_argument("--offset", type=int, default=0)
    p.add_argument("--digits", type=int, default=6)
    p.add_argument("--period", type=int, default=30)
    p.add_argument("--algorithm", default="SHA1")
    a = p.parse_args()
    print(totp(a.secret, time.time() + a.offset * a.period, a.digits, a.period, a.algorithm))
    return 0


if __name__ == "__main__":
    sys.exit(main())
