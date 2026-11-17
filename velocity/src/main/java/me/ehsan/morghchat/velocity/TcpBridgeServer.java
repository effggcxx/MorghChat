package me.ehsan.morghchat.velocity;

import org.slf4j.Logger;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Authenticated, length-prefixed TCP fan-out. The proxy treats payloads as opaque bytes. */
final class TcpBridgeServer implements AutoCloseable {
    static final int MAX_PACKET_SIZE = 65_500;
    private static final String MAGIC = "MORGHCHAT/1";
    private final Logger logger;
    private final String bindAddress;
    private final int port;
    private final byte[] expectedSecret;
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService workers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "MorghChat-TCP-client");
        thread.setDaemon(true);
        return thread;
    });
    private ServerSocket serverSocket;
    private Thread acceptThread;

    TcpBridgeServer(Logger logger, String bindAddress, int port, String secret) {
        this.logger = logger;
        this.bindAddress = bindAddress;
        this.port = port;
        this.expectedSecret = secret.getBytes(StandardCharsets.UTF_8);
    }

    void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new java.net.InetSocketAddress(bindAddress, port));
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "MorghChat-TCP-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                workers.execute(() -> handleClient(socket));
            } catch (SocketException exception) {
                if (running.get()) logger.warn("[MorghChat] TCP accept failed: {}", exception.getMessage());
            } catch (IOException exception) {
                if (running.get()) logger.warn("[MorghChat] TCP accept failed: {}", exception.getMessage());
            }
        }
    }

    private void handleClient(Socket socket) {
        Client client = null;
        try {
            socket.setSoTimeout(10_000);
            DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            String magic = input.readUTF();
            String suppliedSecret = input.readUTF();
            if (!MAGIC.equals(magic) || !MessageDigest.isEqual(expectedSecret, suppliedSecret.getBytes(StandardCharsets.UTF_8))) {
                logger.warn("[MorghChat] Rejected unauthenticated TCP connection from {}", socket.getRemoteSocketAddress());
                return;
            }
            socket.setSoTimeout(0);
            client = new Client(socket, input, output);
            clients.add(client);
            logger.info("[MorghChat] Backend connected from {} ({} connected)", socket.getRemoteSocketAddress(), clients.size());
            while (running.get() && !socket.isClosed()) {
                int length;
                try { length = input.readInt(); } catch (EOFException exception) { break; }
                if (length < 1 || length > MAX_PACKET_SIZE) throw new IOException("invalid packet length: " + length);
                byte[] packet = input.readNBytes(length);
                if (packet.length != length) throw new EOFException("truncated packet");
                broadcast(client, packet);
            }
        } catch (IOException exception) {
            if (running.get() && client != null) logger.debug("[MorghChat] Backend connection ended: {}", exception.getMessage());
        } finally {
            if (client != null) {
                clients.remove(client);
                logger.info("[MorghChat] Backend disconnected ({} connected)", clients.size());
            }
            closeQuietly(socket);
        }
    }

    private void broadcast(Client sender, byte[] packet) {
        for (Client recipient : clients) {
            if (recipient == sender) continue;
            try { recipient.send(packet); }
            catch (IOException exception) {
                clients.remove(recipient);
                recipient.close();
                logger.warn("[MorghChat] Removed backend with failed TCP write: {}", exception.getMessage());
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
    }

    @Override
    public void close() {
        running.set(false);
        closeQuietly(serverSocket);
        for (Client client : clients) client.close();
        clients.clear();
        workers.shutdownNow();
        if (acceptThread != null) acceptThread.interrupt();
    }

    private static final class Client {
        private final Socket socket;
        private final DataInputStream input;
        private final DataOutputStream output;
        private Client(Socket socket, DataInputStream input, DataOutputStream output) {
            this.socket = socket;
            this.input = input;
            this.output = output;
        }
        private synchronized void send(byte[] packet) throws IOException {
            if (socket.isClosed()) throw new SocketException("socket closed");
            output.writeInt(packet.length);
            output.write(packet);
            output.flush();
        }
        private void close() { closeQuietly(socket); }
    }
}
