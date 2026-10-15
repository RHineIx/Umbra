#!/usr/bin/env python3
"""Derive Android version metadata and release details from a Git tag.

Accepted tags: vMAJOR.MINOR.PATCH, optionally suffixed with -rc, -rc.N, or -debug.
The numeric version code reserves two decimal digits for release stages so an RC
or debug build can be upgraded to the stable release of the same SemVer version.
"""

from __future__ import annotations

import re
import sys

TAG_PATTERN = re.compile(
    r"v(?P<major>0|[1-9][0-9]*)\."
    r"(?P<minor>0|[1-9][0-9]*)\."
    r"(?P<patch>0|[1-9][0-9]*)"
    r"(?:-(?P<suffix>debug|rc(?:\.(?P<rc_number>[1-9][0-9]*))?))?"
)
MAX_VERSION_CODE = 2_147_483_647


def derive_version(tag: str) -> dict[str, str]:
    match = TAG_PATTERN.fullmatch(tag)
    if match is None:
        raise ValueError(
            f"Invalid tag {tag!r}. Expected vMAJOR.MINOR.PATCH, optionally "
            "with -rc, -rc.N, or -debug."
        )

    major = int(match.group("major"))
    minor = int(match.group("minor"))
    patch = int(match.group("patch"))
    suffix = match.group("suffix")

    # This layout reserves two decimal digits each for minor, patch, and stage.
    if minor >= 100 or patch >= 100:
        raise ValueError("MINOR and PATCH must each be less than 100 for versionCode encoding.")

    if suffix is None:
        stage = 99
        channel = "stable"
        prerelease = "false"
        release_label = "Stable"
    elif suffix == "debug":
        stage = 0
        channel = "debug"
        prerelease = "true"
        release_label = "Debug Preview"
    else:
        rc_number = match.group("rc_number")
        stage = 1 if rc_number is None else int(rc_number) + 1
        if stage > 98:
            raise ValueError("RC tags support -rc or -rc.N where N is between 1 and 97.")
        channel = "pre-release"
        prerelease = "true"
        release_label = "Release Candidate"

    version_code = major * 1_000_000 + minor * 10_000 + patch * 100 + stage
    if not 1 <= version_code <= MAX_VERSION_CODE:
        raise ValueError(f"Calculated versionCode {version_code} is outside Android's supported range.")

    version_name = tag[1:]
    return {
        "tag_name": tag,
        "version_name": version_name,
        "version_code": str(version_code),
        "channel": channel,
        "prerelease": prerelease,
        "release_name": f"Umbra v{version_name} ({release_label})",
    }


def main() -> int:
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <tag>", file=sys.stderr)
        return 2

    try:
        values = derive_version(sys.argv[1])
    except ValueError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1

    for key, value in values.items():
        print(f"{key}={value}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
