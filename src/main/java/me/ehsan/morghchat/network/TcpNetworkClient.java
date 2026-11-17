package me.ehsan.morghchat.network;

import me.ehsan.morghchat.MorghChat;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Reconnecting TCP client used by Paper backends to communicate with MorghChatVelocity. */
final class TcpNetworkClient implements AutoCloseable {
    private static final String MAGIC = "MORGHCHAT/1";
    private static final int MAX_PACKET_SIZE = 65_500;
    private static final int OUTBOUND_QUEUE_SIZE = 1024;

    private final MorghChat plugin;
    private final String host;
    private final int port;
    private final String secret;
    private final Consumer<byte[]> receiveHandler;
    private final ArrayBlockingQueue<byte[]> outbound = new ArrayBlockingQueue<>(OUTBOUND_QUEUE_SIZE);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread connectionThread;

    TcpNetworkClient(MorghChat plugin, String host, int port, String secret, Consumer<byte[]> receiveHandler) {
        this.plugin = plugin;
        this.host = host;
        this.port = port;
        this.secret = secret;
        this.receiveHandler = receiveHandler;
        connectionThread = new Thread(this::connectionLoop, "MorghChat-TCP-connection");
        connectionThread.setDaemon(true);
        connectionThread.start();
    }

    boolean send(byte[] packet) {
        return packet != null && packet.length > 0 && packet.length <= MAX_PACKET_SIZE && outbound.offer(packet.clone());
    }

    private void connectionLoop() {
        while (running.get()) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 5000);
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                output.writeUTF(MAGIC);
                output.writeUTF(secret);
                output.flush();
                plugin.getLogger().info("Connected to MorghChat proxy bridge at " + host + ":" + port);

                Thread reader = new Thread(() -> readLoop(socket, input), "MorghChat-TCP-reader");
                reader.setDaemon(true);
                reader.start();

                while (running.get() && !socket.isClosed()) {
                    byte[] packet = outbound.poll(250, TimeUnit.MILLISECONDS);
                    if (packet == null) continue;
                    output.writeInt(packet.length);
                    output.write(packet);
                    output.flush();
                }
                reader.interrupt();
            } catch (InterruptedException exception) {
                if (!running.get()) break;
                Thread.currentThread().interrupt();
            } catch (IOException exception) {
                if (running.get()) plugin.getLogger().fine("MorghChat proxy connection unavailable: " + exception.getMessage());
            }

            if (running.get()) {
                try { Thread.sleep(5000); }
                catch (InterruptedException exception) { if (!running.get()) break; }
            }
        }
    }

    private void readLoop(Socket socket, DataInputStream input) {
        try {
            while (running.get() && !socket.isClosed()) {
                int length = input.readInt();
                if (length < 1 || length > MAX_PACKET_SIZE) throw new IOException("Invalid proxy packet length: " + length);
                byte[] packet = input.readNBytes(length);
                if (packet.length != length) throw new EOFException("Truncated proxy packet");
                receiveHandler.accept(packet);
            }
        } catch (IOException exception) {
            if (running.get()) plugin.getLogger().fine("MorghChat proxy connection closed: " + exception.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    @Override
    public void close() {
        running.set(false);
        connectionThread.interrupt();
    }
}
