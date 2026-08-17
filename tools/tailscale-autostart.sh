#!/system/bin/sh
# Start Tailscale after boot. Always-on VPN may not apply on this AOSP image,
# so we also send Tailscale's CONNECT_VPN broadcast once the network is up.

log=/data/local/tmp/tailscale-autostart.log
echo "tailscale-autostart $(date)" > "$log"

i=0
while [ "$i" -lt 60 ]; do
  if ip link show eth0 2>/dev/null | grep -q "state UP"; then
    echo "eth0 up after ${i}s" >> "$log"
    break
  fi
  i=$((i + 1))
  sleep 1
done
sleep 8

settings put --user 0 secure always_on_vpn_app com.tailscale.ipn
settings put --user 0 secure always_on_vpn_lockdown 0
echo "always_on=$(settings get --user 0 secure always_on_vpn_app)" >> "$log"

am broadcast --user 0 -a com.tailscale.ipn.CONNECT_VPN \
  -n com.tailscale.ipn/.IPNReceiver >> "$log" 2>&1

echo "done $(date)" >> "$log"
