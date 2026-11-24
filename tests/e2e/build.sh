#!/usr/bin/env bash
set -euo pipefail

if [[ -z "$PAPER_TEST_CLASSPATH" ]]; then
  echo "Set PAPER_TEST_CLASSPATH to a classpath for the matching Paper API." >&2
  exit 2
fi

TEST_DIR=$(cd "$(dirname "$0")" && pwd)
BUILD_DIR="$TEST_DIR/build"
mkdir -p "$BUILD_DIR/classes"
javac -classpath "$PAPER_TEST_CLASSPATH" -d "$BUILD_DIR/classes" "$TEST_DIR/FiestaTierE2E.java"
jar --create --file "$BUILD_DIR/FiestaTierE2E.jar" -C "$BUILD_DIR/classes" . -C "$TEST_DIR" plugin.yml
