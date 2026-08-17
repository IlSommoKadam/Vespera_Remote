#!/system/bin/sh
# Keep vespera-netd running. Used by init after boot and by boot-vespera-netd.sh.
log=/data/local/tmp/vespera-netd-autostart.log
echo "vespera-netd-autostart $(date)" >> "$log"

chmod 755 /data/local/tmp/vespera-netd.sh 2>/dev/null
if [ -x /data/local/tmp/ntfs/mount.ntfs ]; then
  chmod 755 /data/local/tmp/ntfs/mount.ntfs
  chmod 644 /data/local/tmp/ntfs/*.so 2>/dev/null
fi

if [ ! -x /data/local/tmp/vespera-netd.sh ]; then
  echo "missing /data/local/tmp/vespera-netd.sh" >> "$log"
  exit 1
fi

trap '' HUP
while true; do
  echo "start $(date)" >> "$log"
  /system/bin/sh /data/local/tmp/vespera-netd.sh </dev/null >>/data/local/tmp/vespera-netd.log 2>&1
  echo "exit $? $(date)" >> "$log"
  sleep 2
done
