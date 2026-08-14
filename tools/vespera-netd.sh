#!/system/bin/sh
# Root helper for Vespera Wi-Fi Helper on Ethernet+WiFi Pi images.
# The app cannot run `su` (SELinux). This daemon applies:
#   - cmd wifi connect-network  (system-wide SSID, OwnerUid 1000)
#   - ip rule for 10.0.0.0/24 via wlan0 (Singularity reaches API without VPN)
#
# Start once after boot (adb root):
#   adb push tools/vespera-netd.sh /data/local/tmp/vespera-netd.sh
#   adb shell chmod 755 /data/local/tmp/vespera-netd.sh
#   adb shell "/data/local/tmp/vespera-netd.sh >/data/local/tmp/vespera-netd.log 2>&1 &"

REQ_DIR="/sdcard/Android/data/com.vaonis.vesperawifihelper/files"
REQ_FILE="$REQ_DIR/net.req"
ACK_FILE="$REQ_DIR/net.ack"
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

echo "vespera-netd started $(date)" > "$ACK_FILE"

while true; do
  if [ -f "$REQ_FILE" ]; then
    # Format: promote|<ssid>|<bssid>   OR   route
    line=$(tr -d '\r' < "$REQ_FILE" | head -n 1)
    rm -f "$REQ_FILE"
    cmd=$(echo "$line" | cut -d'|' -f1)
    a=$(echo "$line" | cut -d'|' -f2)
    b=$(echo "$line" | cut -d'|' -f3)
    case "$cmd" in
      promote) promote "$a" "$b" ;;
      route) apply_route ;;
      *) echo "unknown:$line" > "$ACK_FILE" ;;
    esac
  fi
  sleep 1
done
