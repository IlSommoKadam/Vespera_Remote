#!/system/bin/sh
# Safe sync+umount of the Vespera HD before reboot/shutdown.
# Keeps /data/local/tmp/vespera-hd.state so the next boot can remount.
log=/data/local/tmp/vespera-hd-shutdown.log
echo "hd-shutdown $(date)" >> "$log"

HD_MOUNT="/mnt/vespera-hd"
HD_STATE="/data/local/tmp/vespera-hd.state"
HELPER_PKG="com.vaonis.vesperahelper"
if [ -d "/data/media/0/Android/data/$HELPER_PKG/files" ]; then
  bind="/data/media/0/Android/data/$HELPER_PKG/files/vespera-photos"
else
  bind="/sdcard/Android/data/$HELPER_PKG/files/vespera-photos"
fi

state_mnt=""
dev=""
if [ -f "$HD_STATE" ]; then
  state_bind=$(grep '^BIND=' "$HD_STATE" | cut -d= -f2)
  state_mnt=$(grep '^MOUNT=' "$HD_STATE" | cut -d= -f2)
  dev=$(grep '^DEV=' "$HD_STATE" | cut -d= -f2)
  [ -n "$state_bind" ] && bind="$state_bind"
fi

sync
umount "$bind" 2>/dev/null || umount -l "$bind" 2>/dev/null
umount "$HD_MOUNT" 2>/dev/null || umount -l "$HD_MOUNT" 2>/dev/null
[ -n "$state_mnt" ] && { umount "$state_mnt" 2>/dev/null || umount -l "$state_mnt" 2>/dev/null; }
if [ -n "$dev" ]; then
  base=$(basename "$dev")
  awk -v d="$dev" -v b="$base" '
    $1 == d { print $2 }
    $1 ~ ("/dev(/block)?/" b "$") { print $2 }
  ' /proc/mounts 2>/dev/null | while read -r mp; do
    [ -n "$mp" ] || continue
    umount "$mp" 2>/dev/null || umount -l "$mp" 2>/dev/null
  done
fi
sync
echo "done $(date) mounts=$(grep -c vespera /proc/mounts 2>/dev/null || echo 0)" >> "$log"
