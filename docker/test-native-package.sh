#!/bin/sh
# Packaging smoke test with a stub executable, not a native Floci application.
# Run directly: sh docker/test-native-package.sh
set -eu

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
PREFIX="floci-packaging-stub-$(basename "$WORK" | tr '[:upper:]' '[:lower:]')"
PASS=0
cleanup() {
    docker rm -fv "${PREFIX}-run" "${PREFIX}-health" >/dev/null 2>&1 || true
    docker image rm "${PREFIX}:application" "${PREFIX}:runner" >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT
trap 'exit 1' INT TERM

assert_eq() {
    if [ "$2" != "$3" ]; then
        printf '[FAIL] %s\n  expected: %s\n  actual:   %s\n' "$1" "$2" "$3" >&2
        exit 1
    fi
    printf '[PASS] %s\n' "$1"
    PASS=$((PASS + 1))
}
run() {
    docker run --rm --name "${PREFIX}-run" "$@"
}

ARCH="$(docker version --format '{{.Server.Arch}}')"
case "$ARCH" in
    amd64|arm64) ;;
    *) printf 'Unsupported Docker architecture: %s\n' "$ARCH" >&2; exit 1 ;;
esac
mkdir -p "$WORK/docker" "$WORK/native/$ARCH"
cp "$SCRIPT_DIR/Dockerfile.native-package" "$WORK/Dockerfile"
cp "$SCRIPT_DIR/entrypoint.sh" "$SCRIPT_DIR/localstack-parity.sh" "$SCRIPT_DIR/healthcheck.sh" "$WORK/docker/"
cp "$SCRIPT_DIR/../.dockerignore" "$WORK/.dockerignore"
cat > "$WORK/stub" <<'EOF'
#!/bin/sh
# Deliberately a shell stub: this does not validate the native application.
printf 'STUB ONLY uid=%s gid=%s cwd=%s\n' "$(id -u)" "$(id -g)" "$PWD"
printf 'arg=<%s>\n' "$@"
if [ "${FLOCI_PACKAGING_STUB_SERVE:-false}" = true ]; then
    exec node -e 'require("http").createServer((req, res) => { res.writeHead(req.url === "/_floci/health" ? 200 : 404); res.end("PACKAGING STUB ONLY"); }).listen(4566, "127.0.0.1")'
fi
EOF
DEFAULT='STUB ONLY uid=1001 gid=0 cwd=/app
arg=<-Dquarkus.http.host=0.0.0.0>
arg=<-Dfloci.security.allow-unsafe-network-exposure=true>'

for layout in application runner; do
    rm -f "$WORK/native/$ARCH/"*
    if [ "$layout" = application ]; then
        cp "$WORK/stub" "$WORK/native/$ARCH/application"
    else
        cp "$WORK/stub" "$WORK/native/$ARCH/floci-packaging-stub-runner"
    fi
    IMAGE="${PREFIX}:${layout}"
    docker build --platform "linux/$ARCH" --build-arg VERSION=packaging-stub-only \
        -t "$IMAGE" -f "$WORK/Dockerfile" "$WORK"
    assert_eq "$layout: default CMD drops privileges and supplies network consent" "$DEFAULT" "$(run "$IMAGE")"
    assert_eq "$layout: empty argv keeps native fallback and consent" "$DEFAULT" \
        "$(run --entrypoint /usr/local/bin/docker-entrypoint.sh "$IMAGE")"
    assert_eq "$layout: explicit native argv keeps argument boundaries" \
        'STUB ONLY uid=1001 gid=0 cwd=/app
arg=<one two>
arg=<>
arg=<three>' "$(run "$IMAGE" /app/application 'one two' '' three)"
    assert_eq "$layout: Node 22 and healthcheck/privilege-drop prerequisites" \
        'runtime prerequisites verified' "$(run "$IMAGE" sh -ec '
            test "$(node -p "process.versions.node.split(\".\")[0]")" = 22
            test -x /bin/bash
            test -x /usr/local/bin/healthcheck.sh
            chroot --help | grep -q -- --skip-chdir
            test -w /app/data
            test "$FLOCI_VERSION" = packaging-stub-only
            test "$FLOCI_TLS_ENABLED" = true
            test -r "$AWS_CONFIG_FILE"
            printf "runtime prerequisites verified\n"
        ')"
    assert_eq "$layout: explicit root opt-out" 0 "$(run -e FLOCI_RUN_AS_ROOT=true "$IMAGE" id -u)"
    assert_eq "$layout: non-root cannot elevate with opt-out" 1001 \
        "$(run --user 1001:0 -e FLOCI_RUN_AS_ROOT=true "$IMAGE" id -u)"
    assert_eq "$layout: healthcheck rejects a closed port" rejected \
        "$(run "$IMAGE" sh -c 'if /usr/local/bin/healthcheck.sh 2>/dev/null; then exit 1; else printf "rejected\n"; fi')"
    assert_eq "$layout: default Docker healthcheck configured" \
        '["CMD","/usr/local/bin/healthcheck.sh"]' \
        "$(docker image inspect --format '{{json .Config.Healthcheck.Test}}' "$IMAGE")"

    docker run -d --name "${PREFIX}-health" --health-interval=1s \
        -e FLOCI_PACKAGING_STUB_SERVE=true "$IMAGE" >/dev/null
    attempts=0
    health=starting
    while [ "$attempts" -lt 30 ]; do
        health="$(docker inspect --format '{{.State.Health.Status}}' "${PREFIX}-health")"
        [ "$health" = healthy ] && break
        [ "$health" = unhealthy ] && break
        attempts=$((attempts + 1))
        sleep 1
    done
    assert_eq "$layout: Bash TCP healthcheck reaches stub HTTP server" healthy "$health"
    docker rm -fv "${PREFIX}-health" >/dev/null

done

IMAGE="${PREFIX}:runner"
assert_eq 'runner: packaging normalizes runner to application symlink' \
    floci-packaging-stub-runner "$(run "$IMAGE" readlink /app/application)"
assert_eq 'runner: runtime fallback survives absent application symlink' "$DEFAULT" \
    "$(run --entrypoint /bin/sh "$IMAGE" -ec '
        rm /app/application
        exec /usr/local/bin/docker-entrypoint.sh /app/application \
            -Dquarkus.http.host=0.0.0.0 -Dfloci.security.allow-unsafe-network-exposure=true
    ')"
assert_eq 'runner: empty argv survives absent application symlink' "$DEFAULT" \
    "$(run --entrypoint /bin/sh "$IMAGE" -ec '
        rm /app/application
        exec /usr/local/bin/docker-entrypoint.sh
    ')"

# A synthetic socket avoids mounting or changing the host Docker socket.
assert_eq 'socket group is inherited and root-owned data becomes writable' \
    'uid=1001 gid=0 groups=0 34567 writable=yes cwd=/app' \
    "$(run --entrypoint /bin/sh "$IMAGE" -ec '
        node -e '\''require("net").createServer().listen("/var/run/docker.sock")'\'' &
        attempts=0
        while [ ! -S /var/run/docker.sock ]; do
            attempts=$((attempts + 1))
            test "$attempts" -lt 10
            sleep 1
        done
        chown 0:34567 /var/run/docker.sock
        chmod 660 /var/run/docker.sock
        chown 0:0 /app/data
        chmod 700 /app/data
        exec /usr/local/bin/docker-entrypoint.sh sh -ec '\''
            test -w /var/run/docker.sock
            touch /app/data/privilege-drop-probe
            printf "uid=%s gid=%s groups=%s writable=yes cwd=%s\n" "$(id -u)" "$(id -g)" "$(id -G)" "$PWD"
        '\''
    ')"
printf '\n%d packaging assertions passed (%s, stub executable only).\n' "$PASS" "$ARCH"
