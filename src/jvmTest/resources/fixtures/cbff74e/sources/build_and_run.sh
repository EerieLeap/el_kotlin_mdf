#!/usr/bin/env bash
# The commands the cbff74e fixtures were made with, for the container rt_daq_devcontainer-zephyr-dev-1 (user ubuntu).
# Nothing here touches the user's checkouts: rt_core is a scratch clone at cbff74e inside the scratch folder (made by the
# commands below, which only read the checkout), linked as a Zephyr module by app/CMakeLists.txt, and the build directory
# is in the scratch folder too.
#
#   1. copy this folder's app/ to the container (from Git Bash, MSYS_NO_PATHCONV=1 keeps the paths as they are; docker cp
#      writes root-owned files, so copy through /tmp and then as ubuntu):
#        cd <fixtures>/cbff74e/sources
#        docker cp app/. rt_daq_devcontainer-zephyr-dev-1:/tmp/app_in
#        docker exec -u ubuntu rt_daq_devcontainer-zephyr-dev-1 bash -c 'mkdir -p /home/ubuntu/zephyr/workspace/scratch_mdf_fixtures_cbff74e/app && cp -r /tmp/app_in/. /home/ubuntu/zephyr/workspace/scratch_mdf_fixtures_cbff74e/app/'
#   2. run this script inside the container
#   3. copy the output out and extract (see ../README.md)
set -euo pipefail

WORKSPACE=/home/ubuntu/zephyr/workspace
SCR=$WORKSPACE/scratch_mdf_fixtures_cbff74e     # must be under the west workspace
USER_RT_CORE=$WORKSPACE/project/modules/eerie_leap_rt_core   # read only
OUT=${1:-$SCR/out}

free -g | head -2                      # stop if under 6 GB available: an overloaded VM once crashed both containers
mkdir -p "$SCR"
cd "$SCR"

if [ ! -d rt_core ]; then
  git clone -q "$USER_RT_CORE" rt_core
  git -C rt_core checkout -q cbff74e0f8ef2029c72c919dd80d02f37d098877
  # the submodules, cloned from the checkout's copies at the commits rt_core records
  rmdir rt_core/modules/expression_engine rt_core/modules/zephyr_lua
  git clone -q "$USER_RT_CORE/modules/expression_engine" rt_core/modules/expression_engine
  git -C rt_core/modules/expression_engine checkout -q 17faeaf2399389b7c902c4deaae88721b038bcaf
  git clone -q "$USER_RT_CORE/modules/zephyr_lua" rt_core/modules/zephyr_lua
  git -C rt_core/modules/zephyr_lua checkout -q c5d257c98cf38f12c440052ee442a1a55cda8abc
  rmdir rt_core/modules/zephyr_lua/lua
  git clone -q "$USER_RT_CORE/modules/zephyr_lua/lua" rt_core/modules/zephyr_lua/lua
  git -C rt_core/modules/zephyr_lua/lua checkout -q 6e22fedb74cf0c9b6656e9fce8b7331db847c605
fi

export PATH=/home/ubuntu/zephyr/.venv/bin:$PATH
export CMAKE_BUILD_PARALLEL_LEVEL=4    # capped, one build at a time
export ZEPHYR_TOOLCHAIN_VARIANT=host

west build -b native_sim -d "$SCR/build" "/home/ubuntu/zephyr/workspace/scratch_mdf_fixtures_cbff74e/app"

mkdir -p "$OUT"
MDF_FIXTURE_OUT="$OUT" "$SCR/build/zephyr/zephyr.exe" | grep -E "FIXTURE|PASS|FAIL"
ls "$OUT"
