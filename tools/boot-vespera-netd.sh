#!/system/bin/sh
# Reload vespera-netd without duplicating the init service or umounting the HD.
# Old wrappers trap TERM and used to run hd-shutdown: SIGKILL skips that.
log=/data/local/tmp/vespera-netd-autostart.log
echo "boot-vespera-netd $(date) pid=$$" >> "$log"

chmod 755 /data/local/tmp/vespera-netd.sh /data/local/tmp/boot-vespera-netd.sh 2>/dev/null
if [ -x /system/bin/vespera-netd-autostart.sh ]; then
  chmod 755 /system/bin/vespera-netd-autostart.sh
fi
if [ -x /data/local/tmp/ntfs/mount.ntfs ]; then
  chmod 755 /data/local/tmp/ntfs/mount.ntfs
  chmod 644 /data/local/tmp/ntfs/*.so 2>/dev/null
fi

my=$$
for pid in $(pgrep -f 'vespera-netd-autostart.sh'); do
  [ "$pid" = "$my" ] && continue
  kill -9 "$pid" 2>/dev/null
done
for pid in $(pgrep -f '/system/bin/sh /data/local/tmp/vespera-netd.sh'); do
  [ "$pid" = "$my" ] && continue
  kill -9 "$pid" 2>/dev/null
done
sleep 1

if [ -f /system/etc/init/vespera-netd-autostart.rc ]; then
  stop vespera_netd 2>/dev/null
  sleep 1
  start vespera_netd 2>/dev/null
  echo "init start vespera_netd" >> "$log"
else
  trap '' HUP
  setsid /system/bin/sh /data/local/tmp/vespera-netd.sh \
    </dev/null >/data/local/tmp/vespera-netd.log 2>&1 &
  echo "fallback netd (no init rc)" >> "$log"
fi

sleep 2
if ! pgrep -f 'vespera-netd-autostart.sh' >/dev/null 2>&1 \
    && ! pgrep -f '/system/bin/sh /data/local/tmp/vespera-netd.sh' >/dev/null 2>&1; then
  trap '' HUP
  if [ -x /system/bin/vespera-netd-autostart.sh ]; then
    setsid /system/bin/sh /system/bin/vespera-netd-autostart.sh </dev/null >/dev/null 2>&1 &
  else
    setsid /system/bin/sh /data/local/tmp/vespera-netd.sh \
      </dev/null >/data/local/tmp/vespera-netd.log 2>&1 &
  fi
  echo "fallback start after init miss" >> "$log"
  sleep 1
fi

ps -A -o PID,PPID,CMDLINE | grep -F vespera-netd | grep -v grep | grep -v boot-vespera-netd
echo BOOT_DONE
