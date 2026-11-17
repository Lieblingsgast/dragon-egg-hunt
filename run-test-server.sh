#!/usr/bin/env bash
# Sets up and starts a local Fabric test server with Dragon Egg Hunt (macOS).
#
#   bash run-test-server.sh            only reachable from this Mac (localhost)
#   bash run-test-server.sh --lan      also reachable from other devices in your home network
#   bash run-test-server.sh --rebuild  build the mod with Gradle first
#   bash run-test-server.sh --offline  offline mode: join with any name, e.g. a second test player
#   bash run-test-server.sh --deps     only download the build dependencies into build/devlibs and stop
#                                      (options can be combined; starting without --offline switches it back)
#
# Everything is downloaded into this project folder (.tools/ and run/), nothing is installed system-wide.
# Without --lan the server only listens on 127.0.0.1. With --lan it listens on this Mac's network address,
# which is still not reachable from the internet unless you forward ports on your router.

set -euo pipefail

REBUILD=0
LAN=0
OFFLINE=0
DEPS_ONLY=0
for ARG in "$@"; do
  case "$ARG" in
    --rebuild) REBUILD=1 ;;
    --lan) LAN=1 ;;
    --offline) OFFLINE=1 ;;
    --deps) DEPS_ONLY=1 ;;
    *) printf 'Unknown option: %s\n' "$ARG" >&2; exit 1 ;;
  esac
done

JAVA_VERSION="25"
USER_AGENT="dragon-egg-hunt-test-setup/1.0.0 (local test server script)"

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
TOOLS_DIR="$PROJECT_DIR/.tools"
RUN_DIR="$PROJECT_DIR/run"

say() { printf '\n\033[1;35m==> %s\033[0m\n' "$1"; }
fail() { printf '\n\033[1;31mError: %s\033[0m\n' "$1" >&2; exit 1; }

# Reads a JSON file and evaluates a JavaScript expression on it (d = parsed JSON). macOS has no jq by default.
json_eval() {
  osascript -l JavaScript -e '
    ObjC.import("Foundation");
    function run(argv) {
      var text = $.NSString.stringWithContentsOfFileEncodingError(argv[0], 4, null).js;
      var d = JSON.parse(text);
      var result = (new Function("d", "return " + argv[1]))(d);
      return result === undefined || result === null ? "" : String(result);
    }' "$1" "$2"
}

download() {
  curl -fL --retry 2 --progress-bar -A "$USER_AGENT" -o "$2" "$1"
}

# Sets key=value in a .properties file, adding the line if it's missing.
set_property() {
  if grep -q "^$2=" "$1" 2>/dev/null; then
    sed -i '' "s|^$2=.*|$2=$3|" "$1"
  else
    echo "$2=$3" >> "$1"
  fi
}

gradle_property() {
  grep "^$1=" "$PROJECT_DIR/gradle.properties" | head -n 1 | cut -d= -f2-
}

[ "$(uname -s)" = "Darwin" ] || fail "This script is written for macOS."

MC_VERSION="$(gradle_property minecraft_version)"
LOADER_VERSION="$(gradle_property loader_version)"

# ---------------------------------------------------------------- Java 25

say "Checking for Java $JAVA_VERSION"
JAVA_HOME_FOUND="$(/usr/libexec/java_home -v "$JAVA_VERSION" 2>/dev/null || true)"
if [ -n "$JAVA_HOME_FOUND" ]; then
  export JAVA_HOME="$JAVA_HOME_FOUND"
  echo "Using installed Java: $JAVA_HOME"
else
  LOCAL_JDK="$(find "$TOOLS_DIR" -maxdepth 4 -type d -path "*jdk-$JAVA_VERSION*/Contents/Home" 2>/dev/null | head -n 1 || true)"
  if [ -z "$LOCAL_JDK" ]; then
    case "$(uname -m)" in
      arm64) ARCH="aarch64" ;;
      x86_64) ARCH="x64" ;;
      *) fail "Unsupported CPU architecture: $(uname -m)" ;;
    esac
    echo "Java $JAVA_VERSION not found. Downloading Eclipse Temurin $JAVA_VERSION ($ARCH) into .tools/ ..."
    mkdir -p "$TOOLS_DIR"
    download "https://api.adoptium.net/v3/binary/latest/$JAVA_VERSION/ga/mac/$ARCH/jdk/hotspot/normal/eclipse" "$TOOLS_DIR/jdk.tar.gz"
    tar -xzf "$TOOLS_DIR/jdk.tar.gz" -C "$TOOLS_DIR"
    rm -f "$TOOLS_DIR/jdk.tar.gz"
    LOCAL_JDK="$(find "$TOOLS_DIR" -maxdepth 4 -type d -path "*jdk-$JAVA_VERSION*/Contents/Home" | head -n 1)"
    [ -n "$LOCAL_JDK" ] || fail "Java download did not contain a JDK."
  fi
  export JAVA_HOME="$LOCAL_JDK"
  echo "Using Java from .tools: $JAVA_HOME"
fi
JAVA="$JAVA_HOME/bin/java"
"$JAVA" -version 2>&1 | head -n 1

# ---------------------------------------------------------------- build dependencies only

if [ "$DEPS_ONLY" = 1 ]; then
  say "Downloading Minecraft $MC_VERSION and the build dependencies (first time takes a few minutes)"
  (cd "$PROJECT_DIR" && sh ./gradlew copyDevLibs)
  echo "Done: $(ls "$PROJECT_DIR/build/devlibs" | wc -l | tr -d ' ') files in build/devlibs"
  exit 0
fi

# ---------------------------------------------------------------- mod jar

if [ "$REBUILD" = 1 ]; then
  say "Building the mod with Gradle"
  (cd "$PROJECT_DIR" && sh ./gradlew build)
fi

MOD_JAR="$(ls -t "$PROJECT_DIR"/build/libs/dragon-egg-hunt-*.jar 2>/dev/null | grep -v -- '-sources\|-javadoc' | head -n 1 || true)"
[ -n "$MOD_JAR" ] || fail "No mod jar in build/libs. Run: bash run-test-server.sh --rebuild"
echo "Mod: $(basename "$MOD_JAR")"

if [ "$LAN" = 1 ]; then
  BIND_ADDRESS="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)"
  [ -n "$BIND_ADDRESS" ] || fail "Couldn't find this Mac's network address. Is it connected to Wi-Fi or Ethernet?"
  JOIN_ADDRESS="$BIND_ADDRESS"
else
  BIND_ADDRESS="127.0.0.1"
  JOIN_ADDRESS="localhost"
fi

mkdir -p "$RUN_DIR/mods"

# ---------------------------------------------------------------- Fabric server

LAUNCHER="$RUN_DIR/fabric-server-launch.jar"
if [ ! -f "$LAUNCHER" ] || [ "$(cat "$RUN_DIR/fabric-version.txt" 2>/dev/null)" != "$MC_VERSION $LOADER_VERSION" ]; then
  say "Downloading the Fabric server for Minecraft $MC_VERSION (loader $LOADER_VERSION)"
  INSTALLER_JSON="$(mktemp)"
  curl -fsSL -A "$USER_AGENT" -o "$INSTALLER_JSON" "https://meta.fabricmc.net/v2/versions/installer" \
    || fail "Could not reach meta.fabricmc.net."
  INSTALLER_VERSION="$(json_eval "$INSTALLER_JSON" '(d.find(function (v) { return v.stable; }) || d[0]).version')"
  rm -f "$INSTALLER_JSON"
  download "https://meta.fabricmc.net/v2/versions/loader/$MC_VERSION/$LOADER_VERSION/$INSTALLER_VERSION/server/jar" "$LAUNCHER"
  echo "$MC_VERSION $LOADER_VERSION" > "$RUN_DIR/fabric-version.txt"
fi
echo "Fabric server: Minecraft $MC_VERSION, loader $LOADER_VERSION"

# ---------------------------------------------------------------- Fabric API

if ! ls "$RUN_DIR"/mods/fabric-api-*.jar >/dev/null 2>&1; then
  say "Downloading Fabric API"
  VERSIONS_JSON="$(mktemp)"
  curl -fsSL -A "$USER_AGENT" -o "$VERSIONS_JSON" -G "https://api.modrinth.com/v2/project/fabric-api/version" \
    --data-urlencode "game_versions=[\"$MC_VERSION\"]" --data-urlencode 'loaders=["fabric"]' \
    || fail "Could not reach Modrinth."
  FAPI="$(json_eval "$VERSIONS_JSON" 'd.length ? (d[0].files.find(function (f) { return f.primary; }) || d[0].files[0]).filename + " " + (d[0].files.find(function (f) { return f.primary; }) || d[0].files[0]).url : ""')"
  rm -f "$VERSIONS_JSON"
  [ -n "$FAPI" ] || fail "No Fabric API release found for $MC_VERSION."
  download "${FAPI#* }" "$RUN_DIR/mods/${FAPI%% *}"
fi
ls "$RUN_DIR"/mods/fabric-api-*.jar | xargs -n 1 basename

# ---------------------------------------------------------------- mod + config

rm -f "$RUN_DIR"/mods/dragon-egg-hunt-*.jar
cp "$MOD_JAR" "$RUN_DIR/mods/"

CONFIG="$RUN_DIR/config/dragonegghunt.yml"
if [ ! -f "$CONFIG" ]; then
  mkdir -p "$(dirname "$CONFIG")"
  cp "$PROJECT_DIR/src/main/resources/config.yml" "$CONFIG"
fi
# The resource pack is served from the same address players join with (updated on every start)
sed -i '' \
  -e "s/^    bind-address: .*/    bind-address: \"$BIND_ADDRESS\"/" \
  -e "s/^    public-address: .*/    public-address: \"$JOIN_ADDRESS\"/" \
  "$CONFIG"

if [ ! -f "$RUN_DIR/server.properties" ]; then
  cat > "$RUN_DIR/server.properties" <<'EOF'
server-port=25565
motd=Dragon Egg Hunt test server
spawn-protection=0
EOF
fi
set_property "$RUN_DIR/server.properties" "server-ip" "$BIND_ADDRESS"
if [ "$OFFLINE" = 1 ]; then
  set_property "$RUN_DIR/server.properties" "online-mode" "false"
  set_property "$RUN_DIR/server.properties" "enforce-secure-profile" "false"
  # whitelist entries hold the real account IDs, offline players have different ones
  set_property "$RUN_DIR/server.properties" "white-list" "false"
else
  set_property "$RUN_DIR/server.properties" "online-mode" "true"
  set_property "$RUN_DIR/server.properties" "enforce-secure-profile" "true"
fi

# ---------------------------------------------------------------- EULA

if ! grep -q '^eula=true' "$RUN_DIR/eula.txt" 2>/dev/null; then
  say "Minecraft EULA"
  echo "To run a server you have to accept the Minecraft EULA: https://aka.ms/MinecraftEULA"
  printf "Do you accept it? [y/N] "
  read -r ANSWER
  case "$ANSWER" in
    y|Y|yes|YES|j|J|ja|JA) echo "eula=true" > "$RUN_DIR/eula.txt" ;;
    *) fail "The server can't start without accepting the EULA." ;;
  esac
fi

# ---------------------------------------------------------------- start

say "Starting the server"
echo "Join with Minecraft $MC_VERSION at: $JOIN_ADDRESS"
if [ "$LAN" = 1 ]; then
  echo "If macOS asks whether Java may accept incoming network connections, click Allow."
fi
if [ "$OFFLINE" = 1 ]; then
  echo "Offline mode: names aren't checked and offline players have new IDs, so op them again (op <name>)."
fi
echo "Type 'stop' here to shut the server down."
cd "$RUN_DIR"
exec "$JAVA" -Xms1G -Xmx2G -jar fabric-server-launch.jar nogui
