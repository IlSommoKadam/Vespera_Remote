#!/system/bin/sh
# Root helper for Vespera Wi-Fi Helper on Ethernet+WiFi Pi images.
# The app cannot run `su` (SELinux). This daemon applies:
#   - cmd wifi connect-network  (system-wide SSID, OwnerUid 1000)
#   - ip rule for 10.0.0.0/24 via wlan0 (Singularity reaches API without VPN)
#   - am force-stop com.vaonis.barnard on restart-singularity (watchdog recovery)
#   - check-singularity writes singularity.ack (socket/UI probe inside Singularity)
#
# Start once after boot (adb root):
#   adb push tools/vespera-netd.sh /data/local/tmp/vespera-netd.sh
#   adb shell chmod 755 /data/local/tmp/vespera-netd.sh
#   adb shell "/data/local/tmp/vespera-netd.sh >/data/local/tmp/vespera-netd.log 2>&1 &"

REQ_DIR="/sdcard/Android/data/com.vaonis.vesperawifihelper/files"
REQ_FILE="$REQ_DIR/net.req"
ACK_FILE="$REQ_DIR/net.ack"
SINGULARITY_ACK="$REQ_DIR/singularity.ack"
SINGULARITY_PKG="com.vaonis.barnard"
TABLE=94
PRIORITY=94

mkdir -p "$REQ_DIR" 2>/dev/null

apply_route() {
  ip route replace 10.0.0.0/24 dev wlan0 table "$TABLE" 2>/dev/null
  ip rule del to 10.0.0.0/24 lookup "$TABLE" priority "$PRIORITY" 2>/dev/null
  ip rule add to 10.0.0.0/24 lookup "$TABLE" priority "$PRIORITY" 2>/dev/null
  echo "route-ok $(date)" > "$ACK_FILE"
}

promote() {
  ssid="$1"
  bssid="$2"
  if [ -z "$ssid" ] || [ -z "$bssid" ]; then
    echo "promote-bad-args" > "$ACK_FILE"
    return 1
  fi
  cmd wifi connect-network "$ssid" open -b "$bssid"
  sleep 2
  apply_route
  echo "promote-ok $ssid $(date)" > "$ACK_FILE"
}

check_singularity() {
  if ! cmd wifi status 2>/dev/null | grep -qiE 'vespera|VESPERAPRO'; then
    echo "singularity-missing wifi-not-vespera $(date)" > "$SINGULARITY_ACK"
    return 1
  fi
  if ! nc -z -w1 10.0.0.1 8083 2>/dev/null && ! nc -z -w1 10.0.0.1 8082 2>/dev/null; then
    echo "singularity-missing vespera-api-down $(date)" > "$SINGULARITY_ACK"
    return 1
  fi
  if ! pidof "$SINGULARITY_PKG" >/dev/null 2>&1; then
    echo "singularity-disconnected not-running $(date)" > "$SINGULARITY_ACK"
    return 1
  fi
  UID=$(dumpsys package "$SINGULARITY_PKG" 2>/dev/null | sed -n 's/.*userId=//p' | head -1)
  if [ -n "$UID" ] && ss -tnp state established 2>/dev/null | grep "uid=$UID" | grep -qE '10\.0\.0\.1:8083|10\.0\.0\.1:8082'; then
    echo "singularity-connected socket $(date)" > "$SINGULARITY_ACK"
    return 0
  fi
  PID=$(pidof "$SINGULARITY_PKG" 2>/dev/null | awk '{print $1}')
  if [ -n "$PID" ] && grep -qE '0100000A:1F63|0100000A:1F62' "/proc/$PID/net/tcp" 2>/dev/null; then
    echo "singularity-connected proc-tcp $(date)" > "$SINGULARITY_ACK"
    return 0
  fi
  if uiautomator dump /data/local/tmp/singularity-ui.xml 2>/dev/null; then
    if grep -qiE 'open wi-?fi|no instrument|instrumento non|nessuno strumento|aucun instrument|sin instrumento' /data/local/tmp/singularity-ui.xml; then
      echo "singularity-disconnected ui-prompt $(date)" > "$SINGULARITY_ACK"
      return 1
    fi
  fi
  echo "singularity-disconnected no-socket $(date)" > "$SINGULARITY_ACK"
  return 1
}

echo "vespera-netd started $(date)" > "$ACK_FILE"

while true; do
  if [ -f "$REQ_FILE" ]; then
    # Format: promote|<ssid>|<bssid>   OR   route   OR   restart-singularity   OR   check-singularity
    line=$(tr -d '\r' < "$REQ_FILE" | head -n 1)
    rm -f "$REQ_FILE"
    cmd=$(echo "$line" | cut -d'|' -f1)
    a=$(echo "$line" | cut -d'|' -f2)
    b=$(echo "$line" | cut -d'|' -f3)
    case "$cmd" in
      promote) promote "$a" "$b" ;;
      route) apply_route ;;
      restart-singularity)
        am force-stop "$SINGULARITY_PKG" 2>/dev/null
        echo "singularity-restart-ok $(date)" > "$ACK_FILE"
        ;;
      check-singularity) check_singularity ;;
      *) echo "unknown:$line" > "$ACK_FILE" ;;
    esac
  fi
  sleep 1
done
