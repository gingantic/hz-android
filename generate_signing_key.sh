#!/usr/bin/env bash
#
# generate_signing_key.sh — create the shared OTA signing keystore.
#
# Produces:
#   .signing/release.keystore.jks   keystore (gitignored)
#   .signing/release_keystore.b64   base64 of keystore for the GitHub secret
#   local.properties                gets SIGNING_* entries appended/updated (gitignored)
#
# After running, add these as GitHub repo secrets (Settings -> Secrets -> Actions):
#   RELEASE_KEYSTORE_B64 = contents of .signing/release_keystore.b64
#   KEYSTORE_PASS        = the printed password
#   KEY_ALIAS            = hzplayer
#   KEY_PASS             = the printed password
#
# Env overrides: KEY_ALIAS, KEYSTORE_PASS, KEY_VALIDITY_DAYS, STORE_PATH, B64_PATH

set -euo pipefail

ALIAS="${KEY_ALIAS:-hzplayer}"
VALIDITY="${KEY_VALIDITY_DAYS:-10000}"
STORE_PATH="${STORE_PATH:-.signing/release.keystore.jks}"
B64_PATH="${B64_PATH:-.signing/release_keystore.b64}"

# Random 24-char password if not supplied.
# Pull plenty of bytes (tr drops non-alnum), then take the first 24 via a bash
# substring — no early-closing pipe, so no SIGPIPE under `set -o pipefail`.
if [ -z "${KEYSTORE_PASS:-}" ]; then
  KEYSTORE_PASS="$(head -c 64 /dev/urandom | tr -dc 'A-Za-z0-9')"
  KEYSTORE_PASS="${KEYSTORE_PASS:0:24}"
fi

# Locate a JDK tool by name. Tries, in order:
#   PATH  ->  JAVA_HOME/bin  ->  derive from `java` on PATH  ->  Windows `where`
resolve_tool() {
  local name="$1"
  local found java_bin

  found="$(command -v "$name" 2>/dev/null)" && { echo "$found"; return 0; }

  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/$name" ]; then
    echo "$JAVA_HOME/bin/$name"; return 0
  fi

  # Derive from a `java` that IS reachable (covers Git Bash where keytool may
  # not be on PATH but java is).
  java_bin="$(command -v java 2>/dev/null)"
  if [ -n "$java_bin" ]; then
    # strip trailing /java[.exe] to get the bin dir
    local bin_dir="${java_bin%/*}"
    if [ -x "$bin_dir/$name" ]; then
      echo "$bin_dir/$name"; return 0
    fi
  fi

  # Windows: ask `where` via cmd (try both spellings).
  for cmdname in cmd cmd.exe; do
    found="$($cmdname //c "where $name" 2>/dev/null)"
    if [ -n "$found" ]; then
      found="${found%%$'\n'*}"        # first line only
      found="${found%$'\r'}"
      command -v cygpath >/dev/null 2>&1 && found="$(cygpath -u "$found")"
      echo "$found"; return 0
    fi
  done

  return 1
}

if [ -z "${KEYTOOL:-}" ]; then
  KEYTOOL="$(resolve_tool keytool)" || {
    echo "keytool not found on PATH, JAVA_HOME, via 'java', or Windows 'where'." >&2
    echo "Fix one of:" >&2
    echo "  export JAVA_HOME=/path/to/jdk   # e.g. /mnt/c/java21" >&2
    echo "  KEYTOOL=/path/to/keytool.exe ./generate_signing_key.sh" >&2
    exit 1
  }
fi
[ -x "$KEYTOOL" ] || { echo "keytool not executable: $KEYTOOL" >&2; exit 1; }

mkdir -p "$(dirname "$STORE_PATH")"

if [ -f "$STORE_PATH" ]; then
  echo "Keystore already exists at $STORE_PATH — aborting to avoid overwriting." >&2
  echo "Delete it first if you intend to regenerate (this invalidates already-signed APKs)." >&2
  exit 1
fi

"$KEYTOOL" -genkeypair -v \
  -keystore "$STORE_PATH" \
  -keyalg RSA -keysize 2048 \
  -validity "$VALIDITY" \
  -alias "$ALIAS" \
  -storepass "$KEYSTORE_PASS" \
  -keypass "$KEYSTORE_PASS" \
  -dname "CN=Hz Player, OU=Dev, O=rhnxdev, C=US" | sed "s#$KEYSTORE_PASS#*****#g"

# base64 for the GitHub secret.
base64 "$STORE_PATH" > "$B64_PATH"

# Update local.properties without clobbering existing entries (e.g. sdk.dir).
if [ -f local.properties ]; then
  # Strip any prior SIGNING_* lines.
  grep -v '^SIGNING_' local.properties > local.properties.tmp || true
  mv local.properties.tmp local.properties
fi

{
  echo ""
  echo "# OTA signing (gitignored — same cert for local release builds and CI OTA APKs)"
  echo "SIGNING_STORE_PATH=$STORE_PATH"
  echo "SIGNING_STORE_PASSWORD=$KEYSTORE_PASS"
  echo "SIGNING_KEY_ALIAS=$ALIAS"
  echo "SIGNING_KEY_PASSWORD=$KEYSTORE_PASS"
} >> local.properties

echo "=== Signing key generated ==="
echo "Keystore : $STORE_PATH"
echo "Base64   : $B64_PATH  (use its contents for secret RELEASE_KEYSTORE_B64)"
echo "Alias    : $ALIAS"
echo "Password : $KEYSTORE_PASS"
echo ""
echo "Add these GitHub repo secrets:"
echo "  RELEASE_KEYSTORE_B64 = <contents of $B64_PATH>"
echo "  KEYSTORE_PASS        = $KEYSTORE_PASS"
echo "  KEY_ALIAS            = $ALIAS"
echo "  KEY_PASS             = $KEYSTORE_PASS"
