package com.vaonis.vesperawifihelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.Network;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Exposes the Vespera subnet (10.0.0.0/24) to every app (Singularity included)
 * while the helper keeps the UID-private WifiNetworkSpecifier link.
 * Only that subnet is routed into the VPN; Ethernet remains the default Internet path.
 */
public final class VesperaBridgeVpnService extends VpnService {
    public static final String ACTION_START = "com.vaonis.vesperawifihelper.START_BRIDGE";
    public static final String ACTION_STOP = "com.vaonis.vesperawifihelper.STOP_BRIDGE";
    public static final String ACTION_BRIDGE_STATUS = "com.vaonis.vesperawifihelper.BRIDGE_STATUS";
    public static final String EXTRA_STATUS = "status";

    private static final String TAG = "VesperaBridgeVpn";
    private static final String CHANNEL = "vespera_bridge";
    private static final int NOTIFICATION_ID = 43;
    private static final int VPN_ADDRESS_PREFIX = 32;
    private static final String VPN_ADDRESS = "10.255.0.1";
    private static final int MTU = 1400;

    private static volatile String lastStatus = "bridge spento";
    private static volatile boolean running;

    private ParcelFileDescriptor tunInterface;
    private Thread worker;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    public static String getLastStatus() { return lastStatus; }
    public static boolean isRunning() { return running; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopBridge();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (worker != null && worker.isAlive()) {
            return START_STICKY;
        }
        Network vespera = VesperaConnectionService.getActiveNetwork();
        if (vespera == null) {
            publish("bridge: rete Vespera assente");
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification("Bridge Singularity in avvio…"));
        if (!establishTun(vespera)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        stopRequested.set(false);
        running = true;
        worker = new Thread(() -> pump(vespera), "vespera-bridge");
        worker.start();
        publish("bridge ON: 10.0.0.0/24 → Vespera (Singularity)");
        return START_STICKY;
    }

    private boolean establishTun(Network vespera) {
        try {
            Builder builder = new Builder()
                    .setSession("Vespera Singularity Bridge")
                    .setMtu(MTU)
                    .addAddress(VPN_ADDRESS, VPN_ADDRESS_PREFIX)
                    .addRoute("10.0.0.0", 24)
                    .setBlocking(false);
            builder.setUnderlyingNetworks(new Network[]{vespera});
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                builder.setMetered(false);
            }
            tunInterface = builder.establish();
            return tunInterface != null;
        } catch (RuntimeException failure) {
            Log.e(TAG, "establishTun failed", failure);
            publish("bridge errore: " + failure.getClass().getSimpleName());
            return false;
        }
    }

    private void pump(Network vespera) {
        FileInputStream tunIn = new FileInputStream(tunInterface.getFileDescriptor());
        FileOutputStream tunOut = new FileOutputStream(tunInterface.getFileDescriptor());
        Map<String, TcpSession> tcpSessions = new ConcurrentHashMap<>();
        Map<String, UdpSession> udpSessions = new ConcurrentHashMap<>();
        ByteBuffer packet = ByteBuffer.allocate(MTU);
        try (Selector selector = Selector.open()) {
            while (!stopRequested.get()) {
                packet.clear();
                int length = tunIn.read(packet.array());
                if (length > 0) {
                    packet.limit(length);
                    handleTunPacket(packet, length, vespera, tcpSessions, udpSessions, selector, tunOut);
                }
                int ready = selector.selectNow();
                if (ready > 0) {
                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();
                        if (!key.isValid()) continue;
                        Object attachment = key.attachment();
                        if (attachment instanceof TcpSession && key.isConnectable()) {
                            ((TcpSession) attachment).finishConnect(tunOut);
                        } else if (attachment instanceof TcpSession && key.isReadable()) {
                            ((TcpSession) attachment).readRemote(tunOut);
                        } else if (attachment instanceof UdpSession && key.isReadable()) {
                            ((UdpSession) attachment).readRemote(tunOut);
                        }
                    }
                }
                if (length <= 0 && ready == 0) {
                    try { Thread.sleep(5); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } catch (IOException io) {
            Log.w(TAG, "bridge pump ended", io);
            publish("bridge interrotto: " + io.getClass().getSimpleName());
        } finally {
            for (TcpSession session : tcpSessions.values()) session.closeQuietly();
            for (UdpSession session : udpSessions.values()) session.closeQuietly();
            running = false;
        }
    }

    private void handleTunPacket(
            ByteBuffer packet,
            int length,
            Network vespera,
            Map<String, TcpSession> tcpSessions,
            Map<String, UdpSession> udpSessions,
            Selector selector,
            FileOutputStream tunOut) throws IOException {
        if (length < 20) return;
        int version = (packet.get(0) >> 4) & 0xF;
        if (version != 4) return;
        int headerLength = (packet.get(0) & 0xF) * 4;
        int protocol = packet.get(9) & 0xFF;
        byte[] srcIp = new byte[4];
        byte[] dstIp = new byte[4];
        packet.position(12);
        packet.get(srcIp);
        packet.get(dstIp);
        if (!isVesperaSubnet(dstIp)) return;

        if (protocol == 6 && length >= headerLength + 20) { // TCP
            int srcPort = ((packet.get(headerLength) & 0xFF) << 8) | (packet.get(headerLength + 1) & 0xFF);
            int dstPort = ((packet.get(headerLength + 2) & 0xFF) << 8) | (packet.get(headerLength + 3) & 0xFF);
            int seq = packet.getInt(headerLength + 4);
            int flags = packet.get(headerLength + 13) & 0xFF;
            boolean syn = (flags & 0x02) != 0;
            boolean ack = (flags & 0x10) != 0;
            boolean fin = (flags & 0x01) != 0;
            boolean rst = (flags & 0x04) != 0;
            int tcpHeaderLen = ((packet.get(headerLength + 12) & 0xF0) >> 4) * 4;
            int payloadOffset = headerLength + tcpHeaderLen;
            int payloadLen = Math.max(0, length - payloadOffset);
            String key = keyOf(srcIp, srcPort, dstIp, dstPort);
            TcpSession session = tcpSessions.get(key);
            if (syn && !ack) {
                if (session != null) session.closeQuietly();
                session = TcpSession.open(this, vespera, srcIp, srcPort, dstIp, dstPort, seq & 0xFFFFFFFFL, selector);
                if (session != null) {
                    tcpSessions.put(key, session);
                    session.maybeWriteSynAck(tunOut);
                }
                return;
            }
            if (session == null) return;
            if (rst) {
                session.closeQuietly();
                tcpSessions.remove(key);
                return;
            }
            if (payloadLen > 0) {
                session.clientToRemote(packet.array(), payloadOffset, payloadLen, seq);
            }
            if (fin) {
                session.clientFinished(tunOut);
                tcpSessions.remove(key);
            }
        } else if (protocol == 17 && length >= headerLength + 8) { // UDP
            int srcPort = ((packet.get(headerLength) & 0xFF) << 8) | (packet.get(headerLength + 1) & 0xFF);
            int dstPort = ((packet.get(headerLength + 2) & 0xFF) << 8) | (packet.get(headerLength + 3) & 0xFF);
            int payloadOffset = headerLength + 8;
            int payloadLen = Math.max(0, length - payloadOffset);
            String key = keyOf(srcIp, srcPort, dstIp, dstPort);
            UdpSession session = udpSessions.get(key);
            if (session == null) {
                session = UdpSession.open(this, vespera, srcIp, srcPort, dstIp, dstPort, selector);
                if (session == null) return;
                udpSessions.put(key, session);
            }
            if (payloadLen > 0) {
                session.clientToRemote(packet.array(), payloadOffset, payloadLen);
            }
        }
    }

    private static boolean isVesperaSubnet(byte[] ip) {
        return (ip[0] & 0xFF) == 10 && (ip[1] & 0xFF) == 0 && (ip[2] & 0xFF) == 0;
    }

    private static String keyOf(byte[] srcIp, int srcPort, byte[] dstIp, int dstPort) {
        return (srcIp[0] & 0xFF) + "." + (srcIp[1] & 0xFF) + "." + (srcIp[2] & 0xFF) + "." + (srcIp[3] & 0xFF)
                + ":" + srcPort + ">" + (dstIp[0] & 0xFF) + "." + (dstIp[1] & 0xFF) + "." + (dstIp[2] & 0xFF)
                + "." + (dstIp[3] & 0xFF) + ":" + dstPort;
    }

    private void stopBridge() {
        stopRequested.set(true);
        running = false;
        if (worker != null) {
            worker.interrupt();
            try { worker.join(1_000); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
        if (tunInterface != null) {
            try { tunInterface.close(); } catch (IOException ignored) {}
            tunInterface = null;
        }
        publish("bridge spento");
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private Notification notification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Vespera Singularity Bridge", NotificationManager.IMPORTANCE_LOW));
        }
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_vespera_notification)
                .setContentTitle("Vespera Bridge")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    private void publish(String status) {
        lastStatus = status;
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(status));
        Intent update = new Intent(ACTION_BRIDGE_STATUS).setPackage(getPackageName());
        update.putExtra(EXTRA_STATUS, status);
        sendBroadcast(update);
    }

    @Override public void onDestroy() {
        stopBridge();
        super.onDestroy();
    }

    @Override public void onRevoke() {
        stopBridge();
        stopSelf();
        super.onRevoke();
    }

    /** One client TCP flow relayed onto the Vespera Network. */
    private static final class TcpSession {
        private final byte[] clientIp;
        private final int clientPort;
        private final byte[] remoteIp;
        private final int remotePort;
        private final SocketChannel channel;
        private SelectionKey key;
        private long clientSeq;
        private long serverSeq = 1;
        private boolean connected;
        private boolean closed;
        private boolean synAckPending;

        private TcpSession(byte[] clientIp, int clientPort, byte[] remoteIp, int remotePort,
                           SocketChannel channel, long clientSeq) {
            this.clientIp = clientIp;
            this.clientPort = clientPort;
            this.remoteIp = remoteIp;
            this.remotePort = remotePort;
            this.channel = channel;
            this.clientSeq = clientSeq;
        }

        static TcpSession open(VpnService vpn, Network vespera, byte[] clientIp, int clientPort,
                               byte[] remoteIp, int remotePort, long clientSeq, Selector selector) {
            try {
                SocketChannel channel = SocketChannel.open();
                channel.configureBlocking(false);
                vpn.protect(channel.socket());
                vespera.bindSocket(channel.socket());
                boolean already = channel.connect(
                        new InetSocketAddress(InetAddress.getByAddress(remoteIp), remotePort));
                int ops = already ? SelectionKey.OP_READ : SelectionKey.OP_CONNECT;
                TcpSession session = new TcpSession(
                        clientIp, clientPort, remoteIp, remotePort, channel, clientSeq);
                session.key = channel.register(selector, ops, session);
                session.connected = already;
                session.synAckPending = true;
                return session;
            } catch (IOException failure) {
                Log.w(TAG, "TCP open failed " + remotePort, failure);
                return null;
            }
        }

        void finishConnect(FileOutputStream tunOut) throws IOException {
            if (closed) return;
            if (channel.finishConnect()) {
                connected = true;
                key.interestOps(SelectionKey.OP_READ);
                maybeWriteSynAck(tunOut);
            }
        }

        void maybeWriteSynAck(FileOutputStream tunOut) throws IOException {
            if (!synAckPending || !connected) return;
            synAckPending = false;
            ByteBuffer packet = buildTcpPacket(remoteIp, remotePort, clientIp, clientPort,
                    serverSeq, clientSeq + 1, (byte) 0x12, null, 0, 0);
            tunOut.write(packet.array(), 0, packet.limit());
            serverSeq += 1;
            clientSeq += 1;
        }

        void clientToRemote(byte[] buffer, int offset, int length, int seq) throws IOException {
            if (closed || !connected) return;
            clientSeq = (seq & 0xFFFFFFFFL) + length;
            ByteBuffer payload = ByteBuffer.wrap(buffer, offset, length);
            while (payload.hasRemaining()) {
                if (channel.write(payload) == 0) break;
            }
        }

        void readRemote(FileOutputStream tunOut) throws IOException {
            if (closed) return;
            maybeWriteSynAck(tunOut);
            ByteBuffer buffer = ByteBuffer.allocate(MTU - 60);
            int read = channel.read(buffer);
            if (read < 0) {
                writeFin(tunOut);
                closeQuietly();
                return;
            }
            if (read == 0) return;
            buffer.flip();
            ByteBuffer packet = buildTcpPacket(remoteIp, remotePort, clientIp, clientPort,
                    serverSeq, clientSeq, (byte) 0x18, buffer.array(), buffer.position(), buffer.remaining());
            tunOut.write(packet.array(), 0, packet.limit());
            serverSeq += read;
        }

        void clientFinished(FileOutputStream tunOut) throws IOException {
            writeFin(tunOut);
            closeQuietly();
        }

        private void writeFin(FileOutputStream tunOut) throws IOException {
            ByteBuffer packet = buildTcpPacket(remoteIp, remotePort, clientIp, clientPort,
                    serverSeq, clientSeq, (byte) 0x11, null, 0, 0);
            tunOut.write(packet.array(), 0, packet.limit());
            serverSeq += 1;
        }

        void closeQuietly() {
            closed = true;
            try { channel.close(); } catch (IOException ignored) {}
        }
    }

    private static final class UdpSession {
        private final byte[] clientIp;
        private final int clientPort;
        private final byte[] remoteIp;
        private final int remotePort;
        private final DatagramChannel channel;

        private UdpSession(byte[] clientIp, int clientPort, byte[] remoteIp, int remotePort,
                           DatagramChannel channel) {
            this.clientIp = clientIp;
            this.clientPort = clientPort;
            this.remoteIp = remoteIp;
            this.remotePort = remotePort;
            this.channel = channel;
        }

        static UdpSession open(VpnService vpn, Network vespera, byte[] clientIp, int clientPort,
                               byte[] remoteIp, int remotePort, Selector selector) {
            try {
                DatagramChannel channel = DatagramChannel.open();
                channel.configureBlocking(false);
                vpn.protect(channel.socket());
                vespera.bindSocket(channel.socket());
                channel.connect(new InetSocketAddress(InetAddress.getByAddress(remoteIp), remotePort));
                UdpSession session = new UdpSession(clientIp, clientPort, remoteIp, remotePort, channel);
                channel.register(selector, SelectionKey.OP_READ, session);
                return session;
            } catch (IOException failure) {
                Log.w(TAG, "UDP open failed " + remotePort, failure);
                return null;
            }
        }

        void clientToRemote(byte[] buffer, int offset, int length) throws IOException {
            channel.write(ByteBuffer.wrap(buffer, offset, length));
        }

        void readRemote(FileOutputStream tunOut) throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(MTU - 40);
            int read = channel.read(buffer);
            if (read <= 0) return;
            buffer.flip();
            ByteBuffer packet = buildUdpPacket(remoteIp, remotePort, clientIp, clientPort,
                    buffer.array(), buffer.position(), buffer.remaining());
            tunOut.write(packet.array(), 0, packet.limit());
        }

        void closeQuietly() {
            try { channel.close(); } catch (IOException ignored) {}
        }
    }

    private static ByteBuffer buildTcpPacket(
            byte[] srcIp, int srcPort, byte[] dstIp, int dstPort,
            long seq, long ack, byte flags, byte[] payload, int payloadOffset, int payloadLen) {
        int tcpHeader = 20;
        int ipHeader = 20;
        int total = ipHeader + tcpHeader + payloadLen;
        ByteBuffer buffer = ByteBuffer.allocate(total);
        // IP
        buffer.put((byte) 0x45);
        buffer.put((byte) 0);
        buffer.putShort((short) total);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0x4000);
        buffer.put((byte) 64);
        buffer.put((byte) 6);
        buffer.putShort((short) 0); // checksum later
        buffer.put(srcIp);
        buffer.put(dstIp);
        // TCP
        buffer.putShort((short) srcPort);
        buffer.putShort((short) dstPort);
        buffer.putInt((int) seq);
        buffer.putInt((int) ack);
        buffer.put((byte) 0x50); // data offset 5
        buffer.put(flags);
        buffer.putShort((short) 65535);
        buffer.putShort((short) 0); // checksum later
        buffer.putShort((short) 0);
        if (payloadLen > 0) buffer.put(payload, payloadOffset, payloadLen);
        buffer.putShort(10, ipChecksum(buffer, 0, ipHeader));
        buffer.putShort(ipHeader + 16, tcpChecksum(buffer, ipHeader, tcpHeader + payloadLen, srcIp, dstIp));
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer buildUdpPacket(
            byte[] srcIp, int srcPort, byte[] dstIp, int dstPort,
            byte[] payload, int payloadOffset, int payloadLen) {
        int ipHeader = 20;
        int udpHeader = 8;
        int total = ipHeader + udpHeader + payloadLen;
        ByteBuffer buffer = ByteBuffer.allocate(total);
        buffer.put((byte) 0x45);
        buffer.put((byte) 0);
        buffer.putShort((short) total);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0x4000);
        buffer.put((byte) 64);
        buffer.put((byte) 17);
        buffer.putShort((short) 0);
        buffer.put(srcIp);
        buffer.put(dstIp);
        buffer.putShort((short) srcPort);
        buffer.putShort((short) dstPort);
        buffer.putShort((short) (udpHeader + payloadLen));
        buffer.putShort((short) 0);
        if (payloadLen > 0) buffer.put(payload, payloadOffset, payloadLen);
        buffer.putShort(10, ipChecksum(buffer, 0, ipHeader));
        buffer.flip();
        return buffer;
    }

    private static short ipChecksum(ByteBuffer buffer, int offset, int length) {
        int sum = 0;
        int i = offset;
        int end = offset + length;
        while (i + 1 < end) {
            sum += ((buffer.get(i) & 0xFF) << 8) | (buffer.get(i + 1) & 0xFF);
            i += 2;
        }
        if (i < end) sum += (buffer.get(i) & 0xFF) << 8;
        while ((sum >>> 16) != 0) sum = (sum & 0xFFFF) + (sum >>> 16);
        return (short) ~sum;
    }

    private static short tcpChecksum(ByteBuffer buffer, int tcpOffset, int tcpLength,
                                     byte[] srcIp, byte[] dstIp) {
        int sum = 0;
        sum += ((srcIp[0] & 0xFF) << 8) | (srcIp[1] & 0xFF);
        sum += ((srcIp[2] & 0xFF) << 8) | (srcIp[3] & 0xFF);
        sum += ((dstIp[0] & 0xFF) << 8) | (dstIp[1] & 0xFF);
        sum += ((dstIp[2] & 0xFF) << 8) | (dstIp[3] & 0xFF);
        sum += 6;
        sum += tcpLength;
        int i = tcpOffset;
        int end = tcpOffset + tcpLength;
        while (i + 1 < end) {
            if (i != tcpOffset + 16) { // skip checksum field
                sum += ((buffer.get(i) & 0xFF) << 8) | (buffer.get(i + 1) & 0xFF);
            }
            i += 2;
        }
        if (i < end) sum += (buffer.get(i) & 0xFF) << 8;
        while ((sum >>> 16) != 0) sum = (sum & 0xFFFF) + (sum >>> 16);
        return (short) ~sum;
    }
}
