#!/usr/bin/env bash
set -euo pipefail

expected_version="1.0.0-SNAPSHOT"
actual_version="$(./gradlew :kalligraphie:properties --no-daemon --console=plain -PreleaseVersion="${expected_version}" \
  | sed -n 's/^version: //p' \
  | tail -n 1)"

if [[ "${actual_version}" != "${expected_version}" ]]; then
  printf 'Expected version %s, got %s\n' "${expected_version}" "${actual_version}" >&2
  exit 1
fi
