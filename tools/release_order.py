"""Publish only APKs with a higher build number than every published APK."""

import json
import re
import sys
from collections.abc import Iterable


def release_build(release: dict) -> int:
    # CI titles record the built APK number, including for manually pushed tags.
    title = re.search(r"\(build ([0-9]+)\)$", release.get("name") or "")
    tag = re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+-build([0-9]+)", release.get("tag_name") or "")
    match = title or tag
    if not match:
        raise ValueError("Cannot determine the build number of a published Ravine APK")
    return int(match[1])


def should_publish(candidate: int, metadata: Iterable[str]) -> bool:
    published = (release_build(json.loads(line)) for line in metadata if line.strip())
    return candidate > max(110, max(published, default=0))


if __name__ == "__main__":
    decision = should_publish(int(sys.argv[1]), sys.stdin)
    print(f"publish={str(decision).lower()}")
