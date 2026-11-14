#!/usr/bin/env bash
# Run once from a trusted local shell with JDK keytool, OpenSSL and GitHub CLI.
# Never commit, upload to issues, or paste the private keystore into chat.
set -euo pipefail
umask 077

repo="reinansantos9090-debug/ReiVex"
alias_name="reivex-release"
keystore="${1:-$HOME/ReiVex-release-key.jks}"

for command_name in keytool openssl base64 gh; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Missing required tool: $command_name" >&2
    exit 1
  fi
done
if ! gh auth status >/dev/null 2>&1; then
  echo "Authenticate GitHub CLI with permission to manage repo Actions secrets and variables." >&2
  echo "Run gh auth login, then run this script again." >&2
  exit 1
fi

# ReiVex has a new Android application ID and a separate signing identity.
# Keep its certificate pin stable after the first release is configured.
new_pin="$(gh variable get REIVEX_ANDROID_EXPECTED_CERT_SHA256 --repo "$repo" 2>/dev/null || true)"
previous="$new_pin"
if [[ ! -f "$keystore" && -n "$previous" ]]; then
  echo "An installed release identity already exists; supply the ORIGINAL keystore path." >&2
  echo "Refusing to generate a new certificate that would prevent app updates." >&2
  exit 1
fi

mkdir -p "$(dirname "$keystore")"
if [[ -e "$keystore" && ! -f "$keystore" ]]; then
  echo "Refusing to overwrite a non-file keystore path." >&2
  exit 1
fi
if [[ -f "$keystore" ]]; then
  echo "Reusing an existing private keystore. The script will not replace its key."
else
  echo "Creating a NEW, permanent signing key at: $keystore"
  echo "This file and its password must be backed up privately. Do not lose them."
fi

read -r -s -p "Enter the keystore password (at least 16 characters): " signing_password
printf '\n'
if [[ ${#signing_password} -lt 16 ]]; then
  echo "Please choose a password of at least 16 characters." >&2
  exit 1
fi

if [[ ! -e "$keystore" ]]; then
  read -r -s -p "Confirm password: " confirmation
  printf '\n'
  if [[ "$signing_password" != "$confirmation" ]]; then
    echo "Passwords do not match; no keystore was created." >&2
    exit 1
  fi
  unset confirmation
  export REIVEX_ANDROID_KEYSTORE_PASSWORD="$signing_password"
  keytool -genkeypair -noprompt \
    -keystore "$keystore" -storetype PKCS12 \
    -alias "$alias_name" -keyalg RSA -keysize 3072 -validity 10000 \
    -dname "CN=ReiVex, OU=Android, O=ReiVex" \
    -storepass:env REIVEX_ANDROID_KEYSTORE_PASSWORD \
    -keypass:env REIVEX_ANDROID_KEYSTORE_PASSWORD
fi

chmod 600 "$keystore"
export REIVEX_ANDROID_KEYSTORE_PASSWORD="$signing_password"
fingerprint="$(keytool -exportcert -rfc \
  -keystore "$keystore" -alias "$alias_name" \
  -storepass:env REIVEX_ANDROID_KEYSTORE_PASSWORD |
  openssl x509 -noout -fingerprint -sha256 |
  cut -d= -f2 | tr -d ':' | tr '[:upper:]' '[:lower:]')"
if [[ ! "$fingerprint" =~ ^[0-9a-f]{64}$ ]]; then
  echo "Unable to verify the keystore signing certificate." >&2
  exit 1
fi

# If this repository already has a pinned identity, never rotate it silently.
if [[ -n "$previous" && "${previous,,}" != "$fingerprint" ]]; then
  echo "The repository already pins a different signing certificate. Aborting." >&2
  echo "Use the ORIGINAL keystore to update, not a new key." >&2
  exit 1
fi

echo "Uploading encrypted signing values to GitHub Actions secrets..."
base64 < "$keystore" | tr -d '\r\n' | gh secret set REIVEX_ANDROID_KEYSTORE_B64 --repo "$repo"
printf '%s' "$signing_password" | gh secret set REIVEX_ANDROID_KEYSTORE_PASSWORD --repo "$repo"
printf '%s' "$signing_password" | gh secret set REIVEX_ANDROID_KEY_PASSWORD --repo "$repo"
printf '%s' "$alias_name" | gh secret set REIVEX_ANDROID_KEY_ALIAS --repo "$repo"
gh variable set REIVEX_ANDROID_EXPECTED_CERT_SHA256 --body "$fingerprint" --repo "$repo"

unset signing_password REIVEX_ANDROID_KEYSTORE_PASSWORD
echo "Release signing configured for $repo."
echo "Pinned public certificate SHA-256: $fingerprint"
echo "PRIVATE KEY BACKUP REQUIRED: $keystore"
echo "Store an offline backup of this keystore and its password BEFORE releasing an APK."
