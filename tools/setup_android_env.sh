#!/usr/bin/env bash
# PhotographerCamera — one-shot Android build environment bootstrap + APK build.
#
# Installs everything into an isolated directory (never touches the user's env):
#   <ROOT>\android-sdk    commandline-tools, platform 34, build-tools 34, platform-tools
#   <ROOT>\jdk17          Microsoft OpenJDK 17 (AGP 8.5 requires JDK 17; JDK 25 is too new)
#   <ROOT>\gradle-8.9     Gradle distribution (project has no wrapper yet)
#
# Then renders local.properties, generates the Gradle wrapper and runs assembleDebug.
#
# NOTE: all paths are Windows-style (C:/...) because native Windows binaries
# (curl.exe, java.exe, sdkmanager.bat) cannot resolve MSYS /c/... paths.
set -uo pipefail

ROOT="C:/Users/EDY/.workbuddy/binaries/android"
PROJ="C:/Users/EDY/WorkBuddy/2026-09-03-15-26-41/PhotographerCamera"
SDK="$ROOT/android-sdk"
# Isolate Gradle's user home. The default C:/Users/EDY/.gradle/caches/8.9/transforms/
# repeatedly hit "Access is denied" writing metadata.bin (leftover read-only state from
# killed builds / OneDrive / Defender lock). A fresh, fully-owned dir avoids that.
export GRADLE_USER_HOME="$ROOT/.gradle"
mkdir -p "$ROOT" "$SDK" "$GRADLE_USER_HOME"

log() { echo "[$(date +%H:%M:%S)] $*"; }
die() { echo "[FATAL] $*" >&2; exit 1; }
# Windows-backslash form, for local.properties and .bat consumers.
bs() { sed 's|/|\\|g' <<< "$1"; }

# ---------------------------------------------------------------- JDK 17
if [ ! -f "$ROOT/jdk17/bin/java.exe" ]; then
  log "Downloading Microsoft OpenJDK 17 ..."
  curl.exe -L --retry 3 --max-time 900 -o "$ROOT/jdk17.zip" \
    "https://aka.ms/download-jdk/microsoft-jdk-17.0.13-windows-x64.zip" \
    || die "JDK download failed"
  log "JDK archive size: $(stat -c%s "$ROOT/jdk17.zip" 2>/dev/null) bytes"
  log "Extracting JDK ..."
  rm -rf "$ROOT/jdk17.tmp" "$ROOT/jdk17"
  mkdir -p "$ROOT/jdk17.tmp"
  unzip -q -o "$ROOT/jdk17.zip" -d "$ROOT/jdk17.tmp" || die "JDK unzip failed"
  SRC="$(find "$ROOT/jdk17.tmp" -maxdepth 1 -mindepth 1 -type d | head -1)"
  [ -n "$SRC" ] || die "JDK extract produced no directory"
  mv "$SRC" "$ROOT/jdk17" || die "JDK move failed"
  rm -rf "$ROOT/jdk17.tmp" "$ROOT/jdk17.zip"
else
  log "JDK 17 already present, skipping."
fi
JAVA_HOME="$ROOT/jdk17"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
log "java: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"

# ------------------------------------------------------- commandline-tools
if [ ! -f "$SDK/cmdline-tools/latest/bin/sdkmanager.bat" ]; then
  log "Downloading Android commandline-tools ..."
  curl.exe -L --retry 3 --max-time 1200 -o "$ROOT/cmdline.zip" \
    "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" \
    || die "cmdline-tools download failed"
  log "cmdline archive size: $(stat -c%s "$ROOT/cmdline.zip" 2>/dev/null) bytes"
  mkdir -p "$SDK/cmdline-tools"
  unzip -q -o "$ROOT/cmdline.zip" -d "$SDK/cmdline-tools" || die "cmdline unzip failed"
  [ -d "$SDK/cmdline-tools/cmdline-tools" ] && [ ! -d "$SDK/cmdline-tools/latest" ] \
    && mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -f "$ROOT/cmdline.zip"
else
  log "commandline-tools already present, skipping."
fi
SDKMGR="$SDK/cmdline-tools/latest/bin/sdkmanager.bat"
[ -f "$SDKMGR" ] || die "sdkmanager not found at $SDKMGR"

# Idempotent SDK bootstrap. sdkmanager needs network to fetch package manifests;
# if the components are already on disk (from a previous run) we must NOT re-run
# install — it would fail offline and block the whole build. Only reach for the
# network when a key binary is actually missing.
sdk_present() {
  [ -f "$SDK/platform-tools/adb.exe" ] \
    && [ -f "$SDK/platforms/android-34/android.jar" ] \
    && [ -f "$SDK/build-tools/34.0.0/aapt2.exe" ]
}
if sdk_present; then
  log "SDK components already present, skipping sdkmanager (offline-safe)."
else
  log "SDK components incomplete, bootstrapping via sdkmanager ..."

  # Pre-seeding the licence hashes is not enough on recent cmdline-tools builds:
  # sdkmanager still prompts "Accept? (y/N)" and silently SKIPS every package when
  # stdin is not interactive. Feed it a stream of y so it accepts all licences.
  mkdir -p "$SDK/licenses"
  printf '\n24333f8a63b6825ea9c5518f83c2829b004d1fee\n8933bad161af4178b1185d1a37fbf41ea5269c55\nd56f5187479451eabf01fb78af6dfcb131a6481e\n601085b94cd77f0b54ff86406957099ebe79c4d6\n' \
    > "$SDK/licenses/android-sdk-license"
  printf '\n84831b9409646a918e30573bab4c9c91346d8abd\n' > "$SDK/licenses/android-sdk-preview-license"

  log "Accepting SDK licences (non-interactive) ..."
  for _i in $(seq 1 40); do echo y; done | "$SDKMGR" --sdk_root="$(bs "$SDK")" --licenses \
    > "$ROOT/sdkmanager_licenses.log" 2>&1 || true

  log "Installing platform-tools / platform 34 / build-tools 34.0.0 ..."
  for _i in $(seq 1 10); do echo y; done | "$SDKMGR" --sdk_root="$(bs "$SDK")" \
    "platform-tools" "platforms;android-34" "build-tools;34.0.0" \
    > "$ROOT/sdkmanager_install.log" 2>&1 || {
      tail -20 "$ROOT/sdkmanager_install.log" >&2
      die "SDK component install failed"
    }
fi

# Hard gate: a silently-skipped package is the classic cause of a 12-minute
# Gradle run that only fails at the very end.
for need in "platforms/android-34" "build-tools/34.0.0" "platform-tools"; do
  [ -d "$SDK/$need" ] || die "SDK component missing after install: $need"
done
log "SDK components verified: platforms/android-34, build-tools/34.0.0, platform-tools"

# ---------------------------------------------------------------- Gradle 8.9
GRADLE_VER="8.9"
if [ ! -f "$ROOT/gradle-$GRADLE_VER/bin/gradle" ]; then
  log "Downloading Gradle $GRADLE_VER ..."
  curl.exe -L --retry 3 --max-time 1200 -o "$ROOT/gradle.zip" \
    "https://services.gradle.org/distributions/gradle-$GRADLE_VER-bin.zip" \
    || die "Gradle download failed"
  log "Gradle archive size: $(stat -c%s "$ROOT/gradle.zip" 2>/dev/null) bytes"
  unzip -q -o "$ROOT/gradle.zip" -d "$ROOT" || die "Gradle unzip failed"
  rm -f "$ROOT/gradle.zip"
else
  log "Gradle $GRADLE_VER already present, skipping."
fi
GRADLE="$ROOT/gradle-$GRADLE_VER/bin/gradle"
chmod +x "$GRADLE" 2>/dev/null

# ---------------------------------------------------------------- project wiring
log "Writing local.properties ..."
# IMPORTANT: local.properties is a Java .properties file, where backslashes are
# escape characters. A Windows 'C:\...' path gets mangled (e.g. \U, \w are not
# valid escapes and corrupt the SDK location). Use forward slashes — the Android
# SDK locator accepts them on Windows.
printf 'sdk.dir=%s\n' "$SDK" > "$PROJ/android/local.properties"
cat "$PROJ/android/local.properties"

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"

cd "$PROJ/android" || die "cannot cd to project"

# services.gradle.org is unreachable in this environment, so `gradle wrapper`
# (which validates the distribution URL) fails. We already have a local Gradle
# 8.9 distribution, so run assembleDebug directly with it — no wrapper needed.
log "Building assembleDebug with local Gradle $GRADLE_VER (no wrapper; pulls Compose/CameraX deps) ..."
# tee to a file so the run can be watched live; tail only summarises at the end.
# Force -g so Gradle uses the isolated user home (the default C:/Users/EDY/.gradle
# had uncurable "Access is denied" writing transform metadata.bin in this sandbox).
"$GRADLE" assembleDebug --no-daemon --stacktrace -g "$GRADLE_USER_HOME" 2>&1 \
  | tee "$ROOT/gradle_build.log" | tail -120
BUILD_RC=${PIPESTATUS[0]}

if [ "$BUILD_RC" -eq 0 ]; then
  APK=$(ls -1 app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)
  log "BUILD OK -> $APK"
  ls -lh app/build/outputs/apk/debug/*.apk 2>/dev/null
else
  log "BUILD FAILED (rc=$BUILD_RC)"
fi
exit "$BUILD_RC"
