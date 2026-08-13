#!/usr/bin/env python3
"""Create a PaperReader Ed25519 signed extension-store envelope.

The private key stays on the machine running this command. The output contains only the public
index bytes and their signature.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


ED25519_SPKI_PREFIX = bytes.fromhex("302a300506032b6570032100")


def run_openssl(executable: str, arguments: list[str], *, input_bytes: bytes | None = None) -> bytes:
    try:
        result = subprocess.run(
            [executable, *arguments],
            input=input_bytes,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
    except FileNotFoundError as error:
        raise RuntimeError(f"OpenSSL executable was not found: {executable}") from error
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"OpenSSL failed: {detail or 'unknown error'}")
    return result.stdout


def sign_index(index_path: Path, private_key_path: Path, output_path: Path, openssl: str) -> tuple[str, str]:
    index_path = index_path.resolve(strict=True)
    private_key_path = private_key_path.resolve(strict=True)
    output_path = output_path.resolve()
    if output_path in {index_path, private_key_path}:
        raise ValueError("Output must not overwrite the index or private key")

    payload = index_path.read_bytes()
    if not payload or len(payload) > 1024 * 1024:
        raise ValueError("Index must contain between 1 byte and 1 MiB")
    try:
        json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("Index must be valid UTF-8 JSON") from error

    signature = run_openssl(
        openssl,
        [
            "pkeyutl",
            "-sign",
            "-rawin",
            "-inkey",
            str(private_key_path),
            "-in",
            str(index_path),
        ],
    )
    if len(signature) != 64:
        raise RuntimeError("The private key did not produce a 64-byte Ed25519 signature")

    subject_public_key_info = run_openssl(
        openssl,
        ["pkey", "-in", str(private_key_path), "-pubout", "-outform", "DER"],
    )
    if not subject_public_key_info.startswith(ED25519_SPKI_PREFIX) or len(subject_public_key_info) != 44:
        raise RuntimeError("The private key is not an Ed25519 key")
    public_key = subject_public_key_info[len(ED25519_SPKI_PREFIX) :]

    envelope = {
        "payload": base64.b64encode(payload).decode("ascii"),
        "signature": base64.b64encode(signature).decode("ascii"),
    }
    encoded = (json.dumps(envelope, separators=(",", ":"), ensure_ascii=True) + "\n").encode("ascii")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    file_descriptor, temporary_name = tempfile.mkstemp(prefix=f".{output_path.name}.", dir=output_path.parent)
    try:
        with os.fdopen(file_descriptor, "wb") as temporary:
            temporary.write(encoded)
            temporary.flush()
            os.fsync(temporary.fileno())
        os.replace(temporary_name, output_path)
    finally:
        try:
            os.unlink(temporary_name)
        except FileNotFoundError:
            pass

    return (
        base64.b64encode(public_key).decode("ascii"),
        hashlib.sha256(public_key).hexdigest(),
    )


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--index", required=True, type=Path, help="Unsigned schema-v1 index JSON")
    parser.add_argument("--private-key", required=True, type=Path, help="PEM Ed25519 private key")
    parser.add_argument("--output", required=True, type=Path, help="Signed envelope JSON")
    parser.add_argument("--openssl", default="openssl", help="OpenSSL executable (default: openssl)")
    return parser.parse_args()


def main() -> int:
    args = parse_arguments()
    try:
        public_key, fingerprint = sign_index(args.index, args.private_key, args.output, args.openssl)
    except (OSError, RuntimeError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(f"Wrote signed index: {args.output.resolve()}")
    print(f"Public key (Base64): {public_key}")
    print(f"Public-key SHA-256: {fingerprint}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
