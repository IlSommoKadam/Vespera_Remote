#!/system/bin/sh
# Single-instance keep-alive for vespera-netd. Init owns this service;
# boot-vespera-netd.sh restarts it via stop/start, never setsid a second copy.
# Do not umount the HD here — that is vespera_hd_shutdown on sys.powerctl.
#
# Do not use toybox flock: it is an external applet and mksh does not inherit
# extra fds, so flock always fails with EBADF.
log=/data/local/tmp/vespera-netd-autostart.log
PIDFILE=/data/local/tmp/vespera-netd.pid
echo "vespera-netd-autostart $(date) pid=$$" >> "$log"

chmod 755 /data/local/tmp/vespera-netd.sh 2>/dev/null

chmod 755 /data/local/tmp/vespera-netd.sh 2>/dev/null
if [ -x /data/local/tmp/ntfs/mount.ntfs ]; then
  chmod 755 /data/local/tmp/ntfs/mount.ntfs
  chmod 644 /data/local/tmp/ntfs/*.so 2>/dev/null
fi

if [ ! -x /data/local/tmp/vespera-netd.sh ]; then
  echo "missing /data/local/tmp/vespera-netd.sh" >> "$log"
  exit 1
fi

my=$$
for pid in $(pgrep -f 'vespera-netd-autostart.sh'); do
  [ "$pid" = "$my" ] && continue
  echo "kill extra autostart pid=$pid" >> "$log"
  kill -9 "$pid" 2>/dev/null
done
for pid in $(pgrep -f '/system/bin/sh /data/local/tmp/vespera-netd.sh'); do
  echo "kill extra netd pid=$pid" >> "$log"
  kill -9 "$pid" 2>/dev/null
done
echo "$my" > "$PIDFILE"

stopping=0
child=0
on_stop() {
  stopping=1
  echo "stop-signal $(date) child=$child" >> "$log"
  if [ "$child" -gt 0 ] 2>/dev/null; then
    kill -TERM "$child" 2>/dev/null
  fi
  rm -f "$PIDFILE"
  exit 0
}
trap on_stop TERM INT
trap '' HUP

while [ "$stopping" -eq 0 ]; do
  echo "start $(date)" >> "$log"
  /system/bin/sh /data/local/tmp/vespera-netd.sh </dev/null >>/data/local/tmp/vespera-netd.log 2>&1 &
  child=$!
  wait "$child"
  code=$?
  child=0
  echo "exit $code $(date)" >> "$log"
  [ "$stopping" -ne 0 ] && break
  sleep 2
done
