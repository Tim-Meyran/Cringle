#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
#
# Runs installer/tests/test-install.sh in a throw-away container with systemd installed, in both modes: below a
# temporary root and as a real installation (user, directories and units on the system of the container).
#
#   installer/tests/test-install-container.sh [image...]     (default: ubuntu:24.04 debian:stable)
set -eu

REPO=$(cd "$(dirname "$0")/../.." && pwd)
if command -v cygpath > /dev/null 2>&1; then REPO=$(cygpath -m "$REPO"); fi
IMAGES=("$@")
[ ${#IMAGES[@]} -gt 0 ] || IMAGES=(ubuntu:24.04 debian:stable)

for image in "${IMAGES[@]}"; do
    echo "=== $image"
    MSYS_NO_PATHCONV=1 docker run --rm -v "$REPO/installer:/src:ro" "$image" bash -ec '
        export DEBIAN_FRONTEND=noninteractive
        apt-get update -qq
        apt-get install -y -qq --no-install-recommends systemd curl ca-certificates > /dev/null
        cp -r /src /tmp/installer
        bash /tmp/installer/tests/test-install.sh
        bash /tmp/installer/tests/test-install.sh --system
    '
done
