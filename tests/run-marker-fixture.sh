#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
fixture_classes=$(mktemp -d)
trap 'rm -rf "$fixture_classes"' EXIT HUP INT TERM

javac --release 21 -d "$fixture_classes" \
  "$repo_root/paper-server/src/main/java/net/minecraft/world/level/chunk/storage/FiestaWorldTierRestoreException.java" \
  "$repo_root/paper-server/src/main/java/net/minecraft/world/level/chunk/storage/FiestaRegionRestorer.java" \
  "$repo_root/paper-server/src/main/java/net/minecraft/world/level/chunk/storage/FiestaRegionOffloader.java" \
  "$repo_root/paper-server/src/main/java/ca/spottedleaf/moonrise/patches/chunk_system/io/FiestaRegionLeaseManager.java" \
  "$repo_root/paper-server/src/main/java/ca/spottedleaf/moonrise/patches/chunk_system/io/FiestaRegionAccessTracker.java" \
  "$repo_root/tests/FiestaRegionLeaseManagerTest.java" \
  "$repo_root/tests/FiestaRegionAccessTrackerTest.java" \
  "$repo_root/tests/FiestaRegionRestorerMarkerTest.java"
java -cp "$fixture_classes" FiestaRegionLeaseManagerTest
java -cp "$fixture_classes" FiestaRegionAccessTrackerTest
java -cp "$fixture_classes" net.minecraft.world.level.chunk.storage.FiestaRegionRestorerMarkerTest
