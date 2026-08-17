#!/system/bin/sh
DIR="/data/media/0/Android/data/com.vaonis.vesperahelper/files"
echo "=== child 5211 ==="
if [ -e /proc/5211/cmdline ]; then
  tr '\0' ' ' < /proc/5211/cmdline
  echo
  cat /proc/5211/wchan
  echo
  awk '/PPid/ {print "PPID="$2}' /proc/5211/status
else
  echo gone
fi
echo
echo "=== time cmd wifi status ==="
time cmd wifi status >/dev/null
echo
echo "=== time dumpsys wifi head ==="
time dumpsys wifi 2>/dev/null | head -n 80 >/dev/null
echo
echo "=== trigger check-singularity ==="
rm -f "$DIR/singularity.ack"
echo check-singularity > "$DIR/singularity.req"
chmod 666 "$DIR/singularity.req"
date
i=0
while [ "$i" -lt 20 ]; do
  if [ -s "$DIR/singularity.ack" ]; then
    echo "ACK_AFTER=${i}s"
    cat "$DIR/singularity.ack"
    echo
    break
  fi
  i=$((i + 1))
  sleep 1
done
if [ ! -s "$DIR/singularity.ack" ]; then
  echo "NO_ACK after 20s"
  echo "req left:"
  cat "$DIR/singularity.req" 2>/dev/null
fi
echo
echo "=== fuse vs media inodes ==="
ls -li "$DIR/singularity.ack" \
  /sdcard/Android/data/com.vaonis.vesperahelper/files/singularity.ack \
  /storage/emulated/0/Android/data/com.vaonis.vesperahelper/files/singularity.ack 2>/dev/null
echo
echo "=== daemon ==="
ps -A -o PID,PPID,CMDLINE | grep vespera-netd | grep -v grep
echo
echo "=== singularity pid ==="
pidof com.vaonis.barnard
pidof .vaonis.barnard
pgrep -f com.vaonis.barnard
