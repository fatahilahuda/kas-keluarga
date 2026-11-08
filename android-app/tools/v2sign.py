"""Sign an APK with APK Signature Scheme v2 (RSA PKCS#1 v1.5 + SHA-256).

Usage: python3 v2sign.py UNSIGNED.apk SIGNED.apk KEYSTORE.p12 PASSWORD_FILE

v2 covers Android 7.0+ (API 24), which is this app's minSdk. The APK Signing
Block is inserted between the ZIP entries and the central directory; entry
offsets don't move, so existing zipalign alignment is preserved.
"""
import hashlib
import struct
import sys

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.serialization import pkcs12

V2_BLOCK_ID = 0x7109871A
RSA_PKCS1_SHA256 = 0x0103
CHUNK = 1024 * 1024
MAGIC = b"APK Sig Block 42"


def lp(b: bytes) -> bytes:
    """uint32 little-endian length prefix."""
    return struct.pack("<I", len(b)) + b


def find_eocd(data: bytes) -> int:
    start = max(0, len(data) - 22 - 0xFFFF)
    i = data.rfind(b"PK\x05\x06", start)
    if i < 0:
        raise SystemExit("no End of Central Directory record")
    return i


def chunked_digest(sections) -> bytes:
    digests = []
    for sec in sections:
        for off in range(0, len(sec), CHUNK):
            c = sec[off:off + CHUNK]
            digests.append(hashlib.sha256(b"\xa5" + struct.pack("<I", len(c)) + c).digest())
    return hashlib.sha256(b"\x5a" + struct.pack("<I", len(digests)) + b"".join(digests)).digest()


def check_alignment(data: bytes, cd_off: int, cd_size: int) -> None:
    """Uncompressed entries must sit on 4-byte boundaries (resources.arsc is required to)."""
    p, end = cd_off, cd_off + cd_size
    while p < end:
        if data[p:p + 4] != b"PK\x01\x02":
            raise SystemExit("bad central directory")
        method = struct.unpack_from("<H", data, p + 10)[0]
        nlen, xlen, clen = struct.unpack_from("<HHH", data, p + 28)
        lho = struct.unpack_from("<I", data, p + 42)[0]
        name = data[p + 46:p + 46 + nlen].decode("utf-8", "replace")
        if method == 0:
            ln, lx = struct.unpack_from("<HH", data, lho + 26)
            doff = lho + 30 + ln + lx
            need = 4096 if name.endswith(".so") else 4
            if doff % need:
                raise SystemExit(f"entry not aligned: {name} at {doff}")
        p += 46 + nlen + xlen + clen


def main(src, dst, ks_path, pw_path):
    data = open(src, "rb").read()
    eocd = find_eocd(data)
    cd_size, cd_off = struct.unpack_from("<II", data, eocd + 12)
    if data[cd_off - 16:cd_off] == MAGIC:
        raise SystemExit("APK already has a signing block; build an unsigned APK")
    check_alignment(data, cd_off, cd_size)

    entries, cd, eocd_bytes = data[:cd_off], data[cd_off:eocd], data[eocd:]
    digest = chunked_digest([entries, cd, eocd_bytes])

    pw = open(pw_path, "rb").read().strip()
    key, cert, _ = pkcs12.load_key_and_certificates(open(ks_path, "rb").read(), pw)
    cert_der = cert.public_bytes(serialization.Encoding.DER)
    pub_der = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)

    digests = lp(lp(struct.pack("<I", RSA_PKCS1_SHA256) + lp(digest)))
    certs = lp(lp(cert_der))
    attrs = lp(b"")
    signed_data = digests + certs + attrs
    sig = key.sign(signed_data, padding.PKCS1v15(), hashes.SHA256())
    signatures = lp(lp(struct.pack("<I", RSA_PKCS1_SHA256) + lp(sig)))
    signer = lp(signed_data) + signatures + lp(pub_der)
    v2_value = lp(lp(signer))

    pair = struct.pack("<Q", 4 + len(v2_value)) + struct.pack("<I", V2_BLOCK_ID) + v2_value
    block_size = len(pair) + 8 + 16  # pairs + trailing size + magic
    block = struct.pack("<Q", block_size) + pair + struct.pack("<Q", block_size) + MAGIC

    new_eocd = bytearray(eocd_bytes)
    struct.pack_into("<I", new_eocd, 16, cd_off + len(block))
    open(dst, "wb").write(entries + block + cd + bytes(new_eocd))
    print(f"signed {dst}: {len(data)} -> {len(data) + len(block)} bytes, cert SHA-256 {hashlib.sha256(cert_der).hexdigest()}")


if __name__ == "__main__":
    main(*sys.argv[1:5])
