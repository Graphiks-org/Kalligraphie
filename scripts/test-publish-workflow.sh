#!/usr/bin/env bash
set -euo pipefail

workflow_file=".github/workflows/publish.yml"
targets="$(sed -n 's/.*run: \.\/gradlew \(:[^ ]*\):publishToMavenCentral.*/\1/p' "${workflow_file}" | sort -u)"

if [[ -z "${targets}" ]]; then
  printf 'No Maven Central publication target found in %s\n' "${workflow_file}" >&2
  exit 1
fi

projects_output="$(./gradlew projects --no-configuration-cache --no-daemon --console=plain)"

while IFS= read -r target; do
  [[ -z "${target}" ]] && continue
  if ! grep -Fq "Project '${target}'" <<<"${projects_output}"; then
    printf 'Workflow target %s is not a declared Gradle project\n' "${target}" >&2
    exit 1
  fi

  publication_tasks="$(./gradlew "${target}:tasks" --no-configuration-cache --no-daemon --console=plain)"
  if ! grep -Fq "publishKotlinMultiplatformPublicationToMavenLocal" <<<"${publication_tasks}"; then
    printf 'Workflow target %s does not expose Kotlin Multiplatform publication tasks\n' "${target}" >&2
    exit 1
  fi
done <<<"${targets}"
