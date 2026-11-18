package me.ehsan.morghchat.network;

import me.ehsan.morghchat.MorghChat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Network transport and packet dispatcher for TCP proxy bridge or legacy Bungee forwarding. */
public final class NetworkMessenger implements PluginMessageListener {
    private static final String CHANNEL = "BungeeCord";
    private static final String SUBCHANNEL = "MorghChat";
    private final MorghChat plugin;
    private final Map<UUID, Long> seenPackets = new ConcurrentHashMap<>();
    private final AtomicLong lastQueueDrain = new AtomicLong();
    private final TcpNetworkClient tcpClient;

    public NetworkMessenger(MorghChat plugin) {
        this.plugin = plugin;
        String transport = plugin.getPluginConfig().getString("network.transport", "bungee");
        if ("tcp".equalsIgnoreCase(transport)) {
            String host = plugin.getPluginConfig().getString("network.tcp.host", "127.0.0.1");
            int port = plugin.getPluginConfig().getInt("network.tcp.port", 25590);
            String secret = plugin.getPluginConfig().getString("network.tcp.secret", "");
            if (host == null || host.isBlank() || port < 1 || port > 65535 || secret == null || secret.length() < 32 || secret.startsWith("CHANGE_THIS")) {
                plugin.getLogger().severe("TCP transport is selected, but network.tcp host/port/secret are invalid. Network messaging is disabled.");
                tcpClient = null;
            } else {
                tcpClient = new TcpNetworkClient(plugin, host, port, secret, this::dispatchTcp);
            }
        } else {
            tcpClient = null;
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
            plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        }
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            long cutoff = System.currentTimeMillis() - 120_000;
            seenPackets.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        }, 2400L, 2400L);
    }

    public void forwardAll(Player carrier, byte[] payload) throws IOException {
        if (!plugin.getPluginConfig().getBoolean("network.enabled", true)) return;
        validatePayload(payload);
        byte[] envelope = wrap(payload);
        if (isTcpTransport()) {
            sendTcp(envelope);
            return;
        }
        if (carrier == null) throw new IOException("No connected player carrier is available");
        sendRaw(carrier, envelope);
        queueForConfiguredBackends(envelope);
    }

    /** Sends a packet without requiring an online player when using the TCP bridge. */
    public void forwardAll(byte[] payload) throws IOException {
        if (!plugin.getPluginConfig().getBoolean("network.enabled", true)) return;
        validatePayload(payload);
        byte[] envelope = wrap(payload);
        if (isTcpTransport()) sendTcp(envelope);
        else queueForConfiguredBackends(envelope);
    }

    private boolean isTcpTransport() {
        return "tcp".equalsIgnoreCase(plugin.getPluginConfig().getString("network.transport", "bungee"));
    }

    private void sendTcp(byte[] envelope) throws IOException {
        if (tcpClient == null || !tcpClient.send(envelope)) {
            throw new IOException("TCP bridge is unavailable or its outbound queue is full");
        }
    }

    private void validatePayload(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0 || payload.length > 65_484) {
            throw new IOException("Invalid MorghChat network payload");
        }
    }

    public void sendPrivateMessage(Player carrier, UUID senderId, String senderName, String targetName, UUID targetId, String message) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(1);
        out.writeUTF("PM");
        out.writeUTF(senderId.toString());
        out.writeUTF(senderName);
        out.writeBoolean(carrier.hasPermission("morghchat.bypass.spy"));
        out.writeBoolean(targetId != null);
        if (targetId != null) out.writeUTF(targetId.toString());
        out.writeUTF(targetName);
        out.writeUTF(message);
        out.flush();
        forwardAll(carrier, bytes.toByteArray());
    }

    public void sendIgnoreChange(Player carrier, UUID viewer, UUID target, boolean ignored) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(1);
        out.writeUTF("IGNORE");
        out.writeUTF(viewer.toString());
        out.writeUTF(target.toString());
        out.writeBoolean(ignored);
        out.flush();
        forwardAll(carrier, bytes.toByteArray());
    }

    private byte[] wrap(byte[] payload) throws IOException {
        UUID id = UUID.randomUUID();
        seenPackets.put(id, System.currentTimeMillis());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(payload.length + 16);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
        out.write(payload);
        out.flush();
        return bytes.toByteArray();
    }

    private void sendRaw(Player carrier, byte[] payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF("Forward");
        out.writeUTF("ALL");
        out.writeUTF(SUBCHANNEL);
        out.writeShort(payload.length);
        out.write(payload);
        out.flush();
        carrier.sendPluginMessage(plugin, CHANNEL, bytes.toByteArray());
    }

    private void queueForConfiguredBackends(byte[] payload) {
        if (!plugin.getStorage().isEnabled()) return;
        String local = plugin.getPluginConfig().getString("network.server-name", "server");
        for (String server : plugin.getPluginConfig().getStringList("network.servers")) {
            if (!server.equalsIgnoreCase(local) && !server.isBlank()) plugin.getStorage().enqueueNetwork(server, payload);
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player source, byte[] data) {
        if (CHANNEL.equals(channel) && plugin.getPluginConfig().getBoolean("network.enabled", true) && data != null) {
            dispatch(data, source);
        }
    }

    /** Called by the TCP reader thread; Bukkit-facing packet handlers run on the server thread. */
    public void dispatchTcp(byte[] envelope) {
        if (!plugin.getPluginConfig().getBoolean("network.enabled", true) || envelope == null) return;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(envelope.length + 16);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF(SUBCHANNEL);
            out.writeShort(envelope.length);
            out.write(envelope);
            out.flush();
            byte[] proxyFrame = bytes.toByteArray();
            plugin.getServer().getScheduler().runTask(plugin, () -> dispatch(proxyFrame, null));
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not process TCP packet: " + exception.getMessage());
        }
    }

    public boolean dispatch(byte[] data) {
        return dispatch(data, null);
    }

    private boolean dispatch(byte[] data, Player source) {
        try {
            DataInputStream outer = new DataInputStream(new ByteArrayInputStream(data));
            if (!"MorghChat".equals(readUtfBounded(outer, 64))) return true;
            int length = outer.readUnsignedShort();
            if (length <= 0 || length > outer.available()) return true;
            byte[] envelope = outer.readNBytes(length);
            if (envelope.length < 16) return true;
            DataInputStream wrapped = new DataInputStream(new ByteArrayInputStream(envelope));
            UUID packetId = new UUID(wrapped.readLong(), wrapped.readLong());
            if (seenPackets.putIfAbsent(packetId, System.currentTimeMillis()) != null) return true;
            byte[] payload = wrapped.readNBytes(wrapped.available());
            DataInputStream packet = new DataInputStream(new ByteArrayInputStream(payload));
            if (packet.readInt() != 1) return true;
            String kind = readUtfBounded(packet, 32);
            if ("MUTE".equals(kind)) {
                plugin.getMuteManager().onNetworkPacket("BungeeCord", source, payload);
                return true;
            }
            if ("IGNORE".equals(kind)) {
                plugin.getIgnoreManager().onNetworkPacket(payload);
                return true;
            }
            if ("PM".equals(kind)) {
                UUID senderId = UUID.fromString(packet.readUTF());
                packet.readUTF();
                packet.readBoolean();
                boolean hasTargetId = packet.readBoolean();
                UUID targetId = hasTargetId ? UUID.fromString(packet.readUTF()) : null;
                String targetName = packet.readUTF();
                if (plugin.getServer().getPlayer(senderId) != null) return true;
                boolean handled = targetId != null ? plugin.getServer().getPlayer(targetId) != null
                        : plugin.getServer().getPlayerExact(targetName) != null;
                plugin.getMessageManager().onNetworkPacket(payload);
                return handled;
            }
            if ("CHAT".equals(kind) || "CLEAR".equals(kind) || "ALERT".equals(kind)) {
                plugin.getGlobalChatManager().onNetworkPacket("BungeeCord", source, payload);
                return !Bukkit.getOnlinePlayers().isEmpty();
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().warning("Malformed MorghChat network packet: " + exception.getMessage());
            return true;
        }
    }

    private String readUtfBounded(DataInputStream input, int max) throws IOException {
        String value = input.readUTF();
        if (value.length() > max) throw new IOException("network string too long");
        return value;
    }

    public void drainQueuedPackets() {
        if (isTcpTransport()) return;
        long now = System.currentTimeMillis();
        long last = lastQueueDrain.get();
        if (now - last < 1000 || !lastQueueDrain.compareAndSet(last, now) || !plugin.getStorage().isEnabled()) return;
        String server = plugin.getPluginConfig().getString("network.server-name", "server");
        plugin.getStorage().drainNetwork(server, this::dispatch);
    }

    public void close() {
        if (tcpClient != null) tcpClient.close();
        if (!isTcpTransport()) {
            plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
            plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
        }
    }
}
