#!/system/bin/sh
# Kill previous daemon/wrapper, then start the keep-alive wrapper.
for pid in $(pgrep -f '/data/local/tmp/vespera-netd.sh'); do
  [ "$pid" = "$$" ] && continue
  cmdline=$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null)
  echo "$cmdline" | grep -q 'boot-vespera-netd' && continue
  echo "$cmdline" | grep -q 'vespera-netd-autostart' && continue
  kill "$pid" 2>/dev/null
done
for pid in $(pgrep -f 'vespera-netd-autostart.sh'); do
  [ "$pid" = "$$" ] && continue
  kill "$pid" 2>/dev/null
done
sleep 1
rm -f /data/local/tmp/vespera-netd.log
if [ -x /data/local/tmp/ntfs/mount.ntfs ]; then
  chmod 755 /data/local/tmp/ntfs/mount.ntfs
  chmod 644 /data/local/tmp/ntfs/*.so 2>/dev/null
fi
chmod 755 /data/local/tmp/vespera-netd.sh /system/bin/vespera-netd-autostart.sh 2>/dev/null
trap '' HUP
if [ -x /system/bin/vespera-netd-autostart.sh ]; then
  setsid /system/bin/sh /system/bin/vespera-netd-autostart.sh </dev/null >/dev/null 2>&1 &
else
  setsid /system/bin/sh /data/local/tmp/vespera-netd.sh </dev/null >/data/local/tmp/vespera-netd.log 2>&1 &
fi
sleep 1
ps -A -o PID,CMDLINE | grep -F vespera-netd | grep -v grep | grep -v boot-vespera-netd
echo BOOT_DONE
