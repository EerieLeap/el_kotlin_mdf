#!/usr/bin/env bash
# The commands the fixtures were made with, for the container rt_daq_devcontainer-zephyr-dev-1 (user ubuntu).
# Nothing here writes to the user's checkouts: rt_core is linked read-only as a Zephyr module by
# app/CMakeLists.txt, and the build directory is inside the scratch folder.
#
#   1. copy this folder's app/ to the container (from Windows, Git Bash; MSYS_NO_PATHCONV=1 keeps the path as is):
#        docker exec rt_daq_devcontainer-zephyr-dev-1 mkdir -p /home/ubuntu/zephyr/workspace/scratch_mdf_fixtures
#        docker cp sources/app/. rt_daq_devcontainer-zephyr-dev-1:/home/ubuntu/zephyr/workspace/scratch_mdf_fixtures/app
#        (docker cp writes root-owned files; copy a file over itself as ubuntu if it must be edited in place)
#   2. run this script inside the container
#   3. copy the output out and extract (see README.md)
set -euo pipefail

SCR=/home/ubuntu/zephyr/workspace/scratch_mdf_fixtures
OUT=${1:-$SCR/out}

free -g | head -2                      # stop if under 6 GB available: an overloaded VM once crashed both containers
cd "$SCR"
export PATH=/home/ubuntu/zephyr/.venv/bin:$PATH
export CMAKE_BUILD_PARALLEL_LEVEL=6    # capped, one build at a time

west build -b native_sim -d "$SCR/build" "$SCR/app"

mkdir -p "$OUT"
MDF_FIXTURE_OUT="$OUT" "$SCR/build/zephyr/zephyr.exe" | grep -E "FIXTURE|PASS|FAIL"
ls "$OUT"
