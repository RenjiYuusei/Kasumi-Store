#!/usr/bin/env python3
"""Generate AES/GCM-encrypted byte arrays for SecureStrings.kt.

Run once to embed encrypted URL payloads. The output Kotlin should be pasted
into SecureStrings.kt. Re-running rotates keys/IVs.
"""
import os
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def to_kt_bytes(name: str, data: bytes) -> str:
    parts = [str(b - 256 if b >= 128 else b) for b in data]
    # Wrap at ~16 values per line for readability.
    lines = []
    for i in range(0, len(parts), 16):
        lines.append("        " + ", ".join(parts[i:i + 16]) + ",")
    body = "\n".join(lines).rstrip(",")
    return (
        f"    private val {name}: ByteArray = byteArrayOf(\n{body}\n    )"
    )


def encrypt(plaintext: bytes, key: bytes) -> bytes:
    iv = os.urandom(12)
    ct = AESGCM(key).encrypt(iv, plaintext, None)
    return iv + ct


def main() -> None:
    key_a = os.urandom(32)
    key_b = os.urandom(32)
    key = bytes(a ^ b for a, b in zip(key_a, key_b))

    apps_url = b"https://raw.githubusercontent.com/RenjiYuusei/Kasumi-Store/main/source/apps.json"
    scripts_url = b"https://raw.githubusercontent.com/RenjiYuusei/Kasumi-Store/main/source/scripts.json"

    apps_payload = encrypt(apps_url, key)
    scripts_payload = encrypt(scripts_url, key)

    print(to_kt_bytes("KEY_A", key_a))
    print()
    print(to_kt_bytes("KEY_B", key_b))
    print()
    print(to_kt_bytes("APPS_PAYLOAD", apps_payload))
    print()
    print(to_kt_bytes("SCRIPTS_PAYLOAD", scripts_payload))


if __name__ == "__main__":
    main()
