#!/usr/bin/env bash
set -euo pipefail

IMAGE_NAME="${IMAGE_NAME:-fluss-manager-web}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
RUN_TESTS="${RUN_TESTS:-false}"
PLATFORM="${PLATFORM:-linux/amd64}"
PLATFORMS="${PLATFORMS:-}"
PUSH="${PUSH:-false}"

usage() {
  cat <<EOF
Usage: ./build.sh [image-name] [image-tag]

Environment:
  IMAGE_NAME   Docker image name, default: fluss-manager-web
  IMAGE_TAG    Docker image tag, default: latest
  RUN_TESTS    Run Maven tests before Docker build, default: false
  PLATFORM     Single target platform, default: linux/amd64
  PLATFORMS    Multi-arch target platforms, example: linux/amd64,linux/arm64
  PUSH         Push multi-arch image, default: false

Examples:
  ./build.sh
  ./build.sh my-registry/fluss-manager-web 0.1.0
  PLATFORM=linux/arm64 ./build.sh
  PLATFORMS=linux/amd64,linux/arm64 PUSH=true ./build.sh my-registry/fluss-manager-web 0.1.0
  RUN_TESTS=true ./build.sh
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -ge 1 ]]; then
  IMAGE_NAME="$1"
fi

if [[ $# -ge 2 ]]; then
  IMAGE_TAG="$2"
fi

if [[ $# -gt 2 ]]; then
  usage
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [[ "$RUN_TESTS" == "true" ]]; then
  mvn test
else
  mvn -DskipTests package
fi

if [[ -n "$PLATFORMS" ]]; then
  if [[ "$PUSH" != "true" ]]; then
    cat >&2 <<EOF
Multi-arch builds require PUSH=true so Docker can publish a manifest list.
Example:
  PLATFORMS=linux/amd64,linux/arm64 PUSH=true ./build.sh ${IMAGE_NAME} ${IMAGE_TAG}
EOF
    exit 1
  fi
  docker buildx build \
    --platform "$PLATFORMS" \
    -t "${IMAGE_NAME}:${IMAGE_TAG}" \
    --push \
    .
else
  docker build \
    --platform "$PLATFORM" \
    -t "${IMAGE_NAME}:${IMAGE_TAG}" \
    .
fi

cat <<EOF

Built image: ${IMAGE_NAME}:${IMAGE_TAG}
Platform: ${PLATFORMS:-$PLATFORM}

Run:
  docker run --rm -p 8080:8080 \\
    -v "\$PWD/data:/app/data" \\
    -e ADMIN_USERNAME=admin \\
    -e ADMIN_PASSWORD=admin \\
    ${IMAGE_NAME}:${IMAGE_TAG}
EOF
