#!/bin/sh
# Unit tests for entrypoint.sh argument handling.
# Run directly: sh docker/test-entrypoint.sh
# Exit 0 on success, non-zero on first failure summary.
#
# These tests run as an unprivileged user with LOCALSTACK_PARITY=false.
# Stub id and chroot to check the root path without changing privileges.

set -eu

SCRIPT="$(cd "$(dirname "$0")" && pwd)/entrypoint.sh"
PASS=0
FAIL=0

assert_eq() {
    desc="$1"; expected="$2"; actual="$3"
    if [ "${actual}" = "${expected}" ]; then
        printf '[PASS] %s\n' "${desc}"
        PASS=$((PASS + 1))
    else
        printf '[FAIL] %s\n  expected: %s\n  actual:   %s\n' "${desc}" "${expected}" "${actual}"
        FAIL=$((FAIL + 1))
    fi
}

if [ "$(id -u)" = '0' ]; then
    echo "These tests must run as an unprivileged user (the root path re-execs via chroot)." >&2
    exit 1
fi

WORK="$(mktemp -d)"
trap 'chmod -R u+w "${WORK}"; rm -rf "${WORK}"' EXIT
trap 'exit 1' INT TERM

# Redirect container-only paths without touching an installed application or socket.
mkdir -p "${WORK}/app"
sed -e "s|/app/|${WORK}/app/|g" \
    -e "s|/var/run/docker.sock|${WORK}/docker.sock|g" \
    "${SCRIPT}" > "${WORK}/entrypoint.sh"
SCRIPT="${WORK}/entrypoint.sh"
unset FLOCI_RUN_AS_ROOT
export FLOCI_STORAGE_PERSISTENT_PATH="${WORK}/absent"

# Stub java on PATH that prints the argv it was exec'd with.
mkdir -p "${WORK}/bin"
cat > "${WORK}/bin/java" <<'EOF'
#!/bin/sh
printf '%s\n' "java $*"
EOF
chmod +x "${WORK}/bin/java"

mkdir -p "${WORK}/root-bin"
cat > "${WORK}/root-bin/id" <<'EOF'
#!/bin/sh
if [ "$1" = '-u' ]; then
    printf '0\n'
else
    /usr/bin/id "$@"
fi
EOF
cat > "${WORK}/root-bin/chroot" <<'EOF'
#!/bin/sh
printf '<%s>\n' "$@"
EOF
chmod +x "${WORK}/root-bin/id" "${WORK}/root-bin/chroot"
DROP_EXPECTED="<--userspec=1001:0>
<--groups=0>
<--skip-chdir>
</>
<${SCRIPT}>
<echo>
<preserved>"

assert_eq "root drops privileges by default" \
    "${DROP_EXPECTED}" \
    "$(PATH="${WORK}/root-bin:${PATH}" FLOCI_STORAGE_PERSISTENT_PATH="${WORK}/absent" LOCALSTACK_PARITY=false sh "${SCRIPT}" echo preserved)"

assert_eq "false keeps the unprivileged default" \
    "${DROP_EXPECTED}" \
    "$(PATH="${WORK}/root-bin:${PATH}" FLOCI_RUN_AS_ROOT=false FLOCI_STORAGE_PERSISTENT_PATH="${WORK}/absent" LOCALSTACK_PARITY=false sh "${SCRIPT}" echo preserved)"

assert_eq "other values keep the unprivileged default" \
    "${DROP_EXPECTED}" \
    "$(PATH="${WORK}/root-bin:${PATH}" FLOCI_RUN_AS_ROOT=TRUE FLOCI_STORAGE_PERSISTENT_PATH="${WORK}/absent" LOCALSTACK_PARITY=false sh "${SCRIPT}" echo preserved)"

assert_eq "explicit root option preserves command arguments" \
    "one two|three" \
    "$(PATH="${WORK}/root-bin:${PATH}" FLOCI_RUN_AS_ROOT=true FLOCI_STORAGE_PERSISTENT_PATH="${WORK}/absent" LOCALSTACK_PARITY=false sh "${SCRIPT}" sh -c 'printf "%s|%s\n" "$1" "$2"' _ "one two" three)"

assert_eq "non-root process cannot elevate with root option" \
    "one two" \
    "$(FLOCI_RUN_AS_ROOT=true LOCALSTACK_PARITY=false sh "${SCRIPT}" echo one two)"

# --- explicit arguments are exec'd unchanged ---
assert_eq "explicit command is exec'd unchanged" \
    "one two" \
    "$(LOCALSTACK_PARITY=false sh "${SCRIPT}" echo one two)"

assert_eq "explicit java command bypasses the fallback" \
    "java -jar /custom/app.jar" \
    "$(PATH="${WORK}/bin:${PATH}" LOCALSTACK_PARITY=false sh "${SCRIPT}" java -jar /custom/app.jar)"

# --- empty argv falls back to the image default command ---
DEFAULT_ARGS='-Dquarkus.http.host=0.0.0.0 -Dfloci.security.allow-unsafe-network-exposure=true'
assert_eq "empty argv falls back to JVM with consent before -jar" \
    "java ${DEFAULT_ARGS} -jar ${WORK}/app/quarkus-app/quarkus-run.jar" \
    "$(PATH="${WORK}/bin:${PATH}" LOCALSTACK_PARITY=false sh "${SCRIPT}")"

cat > "${WORK}/app/floci-test-runner" <<'EOF'
#!/bin/sh
printf '%s\n' "$0 $*"
EOF
chmod +x "${WORK}/app/floci-test-runner"
# A non-executable earlier match must not mask the executable runner.
touch "${WORK}/app/aaa-runner"

assert_eq "empty argv uses fork runner with unsafe-network consent" \
    "${WORK}/app/floci-test-runner ${DEFAULT_ARGS}" \
    "$(LOCALSTACK_PARITY=false sh "${SCRIPT}")"
assert_eq "missing application rewrites to fork runner preserving consent" \
    "${WORK}/app/floci-test-runner ${DEFAULT_ARGS}" \
    "$(LOCALSTACK_PARITY=false sh "${SCRIPT}" "${WORK}/app/application" \
        -Dquarkus.http.host=0.0.0.0 -Dfloci.security.allow-unsafe-network-exposure=true)"

cp "${WORK}/app/floci-test-runner" "${WORK}/app/application"
assert_eq "empty argv prefers application over fork runner with consent" \
    "${WORK}/app/application ${DEFAULT_ARGS}" \
    "$(LOCALSTACK_PARITY=false sh "${SCRIPT}")"
assert_eq "explicit native command is not given extra flags" \
    "${WORK}/app/application --custom" \
    "$(LOCALSTACK_PARITY=false sh "${SCRIPT}" "${WORK}/app/application" --custom)"
chmod -x "${WORK}/app/application"
assert_eq "non-executable application falls back to fork runner" \
    "${WORK}/app/floci-test-runner ${DEFAULT_ARGS}" \
    "$(LOCALSTACK_PARITY=false sh "${SCRIPT}")"

cat > "${WORK}/app/floci-test-runner" <<'EOF'
#!/bin/sh
printf '<%s>\n' "$@"
EOF
assert_eq "fork runner rewrite preserves argument boundaries" \
    '<one two>
<>
<three>' \
    "$(LOCALSTACK_PARITY=false sh "${SCRIPT}" "${WORK}/app/application" 'one two' '' three)"
assert_eq "privilege drop preserves argument boundaries and working directory option" \
    "<--userspec=1001:0>
<--groups=0>
<--skip-chdir>
</>
<${SCRIPT}>
<echo>
<one two>
<>
<three>" \
    "$(PATH="${WORK}/root-bin:${PATH}" LOCALSTACK_PARITY=false sh "${SCRIPT}" echo 'one two' '' three)"

if LOCALSTACK_PARITY=false sh "${SCRIPT}" sh -c 'exit 37'; then
    status=0
else
    status=$?
fi
assert_eq "explicit command exit status is preserved" 37 "${status}"

# --- unwritable state dir prints a warning but still execs the command ---
RO_DIR="${WORK}/ro-data"
mkdir -p "${RO_DIR}"
chmod 555 "${RO_DIR}"
RO_ERR="${WORK}/ro-err.txt"
assert_eq "unwritable state dir still execs the command" \
    "ok" \
    "$(FLOCI_STORAGE_PERSISTENT_PATH="${RO_DIR}" LOCALSTACK_PARITY=false sh "${SCRIPT}" echo ok 2>"${RO_ERR}")"
if grep -q 'not writable' "${RO_ERR}"; then
    printf '[PASS] unwritable state dir prints a loud warning\n'
    PASS=$((PASS + 1))
else
    printf '[FAIL] unwritable state dir prints a loud warning\n  stderr was:\n%s\n' "$(cat "${RO_ERR}")"
    FAIL=$((FAIL + 1))
fi
chmod 755 "${RO_DIR}"

# --- writable state dir stays quiet and leaves no probe behind ---
RW_DIR="${WORK}/rw-data"
mkdir -p "${RW_DIR}"
RW_ERR="${WORK}/rw-err.txt"
FLOCI_STORAGE_PERSISTENT_PATH="${RW_DIR}" LOCALSTACK_PARITY=false sh "${SCRIPT}" echo ok >/dev/null 2>"${RW_ERR}"
if ! grep -q 'not writable' "${RW_ERR}" && [ -z "$(ls -A "${RW_DIR}")" ]; then
    printf '[PASS] writable state dir stays quiet and leaves no probe file\n'
    PASS=$((PASS + 1))
else
    printf '[FAIL] writable state dir stays quiet and leaves no probe file\n'
    FAIL=$((FAIL + 1))
fi

printf '\n%d passed, %d failed\n' "${PASS}" "${FAIL}"
[ "${FAIL}" -eq 0 ]
