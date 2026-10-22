#!/usr/bin/env bash
# Creates a local, self-signed code-signing identity ("Task Baba Local Signing") in your login Keychain.
#
# Why: ad-hoc signatures change on every build, and macOS ties notification and Keychain permissions to the
# signature, so each rebuild "forgets" them. Signing with a stable local certificate keeps the app's identity
# the same across rebuilds. It is only trusted on this Mac and is not a substitute for Apple notarization.
#
# Safe to re-run: does nothing if the identity already exists. Remove it with:
#   security delete-identity -c "Task Baba Local Signing"
set -euo pipefail
NAME="Task Baba Local Signing"
KEYCHAIN="$HOME/Library/Keychains/login.keychain-db"

# macOS only lets tools on a key's access list use it; adding codesign needs your login password.
# Only this one key (matched by label) is changed.
allow_codesign() {
  echo "Allowing codesign to use the key — enter your Mac login password if asked."
  security set-key-partition-list -S apple-tool:,apple:,codesign: -s -l "$NAME" "$KEYCHAIN" >/dev/null
  echo "Done. Rebuild with scripts/build-app.sh."
}

if security find-identity -p codesigning "$KEYCHAIN" | grep -q "\"$NAME\""; then
  echo "Signing identity \"$NAME\" already exists."
  allow_codesign
  exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cat > "$work/openssl.cnf" <<CNF
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = $NAME
[ext]
basicConstraints = critical, CA:false
keyUsage = critical, digitalSignature
extendedKeyUsage = critical, codeSigning
subjectKeyIdentifier = hash
CNF

# /usr/bin/openssl (LibreSSL) writes PKCS#12 files that `security import` accepts.
/usr/bin/openssl req -new -x509 -newkey rsa:2048 -nodes -days 3650 -sha256 \
  -config "$work/openssl.cnf" -keyout "$work/key.pem" -out "$work/cert.pem" 2>/dev/null
pass="$(/usr/bin/openssl rand -hex 16)"
/usr/bin/openssl pkcs12 -export -inkey "$work/key.pem" -in "$work/cert.pem" -name "$NAME" \
  -passout "pass:$pass" -out "$work/identity.p12" 2>/dev/null

# -T lets codesign use the key without a prompt on every build.
security import "$work/identity.p12" -k "$KEYCHAIN" -P "$pass" -T /usr/bin/codesign >/dev/null
echo "Created signing identity \"$NAME\" in your login Keychain."
allow_codesign

