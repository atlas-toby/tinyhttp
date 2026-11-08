package com.erenboz.tinyhttp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * A small HTTP/1.1 server built directly on {@link ServerSocket}.
 * <p>
 * One platform thread accepts connections; every connection is then served by its own virtual
 * thread, so handlers can use plain blocking code while the server still scales to thousands of
 * concurrent connections.
 *
 * <pre>{@code
 * Router router = new Router().get("/hello/{name}",
 *         req -> HttpResponse.text("Hello, " + req.pathParam("name").orElseThrow()));
 * HttpServer server = HttpServer.builder().port(8080).router(router).build().start();
 * }</pre>
 */
public final class HttpServer implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(HttpServer.class.getName());

    private final int port;
    private final Router router;
    private final Duration readTimeout;
    private final int maxBodyBytes;
    private final Duration shutdownGrace;
    private final boolean accessLog;

    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private volatile boolean running;
    private boolean started;
    private ServerSocket serverSocket;
    private ExecutorService workers;
    private Thread acceptor;

    private HttpServer(Builder builder) {
        this.port = builder.port;
        this.router = builder.router;
        this.readTimeout = builder.readTimeout;
        this.maxBodyBytes = builder.maxBodyBytes;
        this.shutdownGrace = builder.shutdownGrace;
        this.accessLog = builder.accessLog;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Binds the port and starts accepting connections. Returns immediately. */
    public synchronized HttpServer start() throws IOException {
        if (started) {
            throw new IllegalStateException("Server can only be started once");
        }
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port), 128);

        serverSocket = socket;
        workers = Executors.newVirtualThreadPerTaskExecutor();
        running = true;
        started = true;
        acceptor = Thread.ofPlatform().name("tinyhttp-acceptor").start(this::acceptLoop);
        return this;
    }

    /** The bound port. Useful when the server was configured with port 0 (any free port). */
    public int port() {
        if (serverSocket == null) {
            throw new IllegalStateException("Server is not started");
        }
        return serverSocket.getLocalPort();
    }

    /**
     * Stops the server gracefully: no new connections are accepted, idle keep-alive connections
     * are closed right away, and in-flight requests get up to the shutdown grace period to finish.
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        closeQuietly(serverSocket);
        connections.forEach(Connection::closeIfIdle);
        workers.shutdown();
        try {
            if (!workers.awaitTermination(shutdownGrace.toMillis(), TimeUnit.MILLISECONDS)) {
                connections.forEach(Connection::forceClose);
                workers.shutdownNow();
            }
            acceptor.join(shutdownGrace.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        stop();
    }

    private void acceptLoop() {
        while (running) {
            Socket client;
            try {
                client = serverSocket.accept();
            } catch (IOException e) {
                if (running) {
                    LOG.log(System.Logger.Level.WARNING, "Failed to accept connection", e);
                }
                continue;
            }
            Connection connection = new Connection(client);
            connections.add(connection);
            try {
                workers.execute(connection);
            } catch (RejectedExecutionException e) {
                connections.remove(connection);
                closeQuietly(client);
            }
        }
    }

    private HttpResponse dispatch(HttpRequest request) {
        try {
            HttpResponse response = switch (router.resolve(request.method(), request.path())) {
                case RouteMatch.Found found -> found.handler().handle(request.withPathParams(found.params()));
                case RouteMatch.MethodNotAllowed notAllowed -> {
                    String allow = notAllowed.allowed().stream().map(Enum::name).collect(Collectors.joining(", "));
                    yield request.method() == HttpMethod.OPTIONS
                            ? HttpResponse.of(HttpStatus.NO_CONTENT).header("Allow", allow)
                            : errorResponse(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed").header("Allow", allow);
                }
                case RouteMatch.NotFound notFound -> errorResponse(HttpStatus.NOT_FOUND, "Not found");
            };
            return response != null ? response : HttpResponse.of(HttpStatus.NO_CONTENT);
        } catch (HttpException e) {
            return errorResponse(e.status(), e.getMessage());
        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR, "Handler failed for " + request, e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
        }
    }

    private static HttpResponse errorResponse(HttpStatus status, String message) {
        return HttpResponse.text(message + "\n").status(status);
    }

    private static boolean wantsKeepAlive(HttpRequest request) {
        if (request.headers().hasToken("Connection", "close")) {
            return false;
        }
        if (request.version().equals("HTTP/1.0")) {
            return request.headers().hasToken("Connection", "keep-alive");
        }
        return true;
    }

    private void logAccess(InetSocketAddress remote, String requestLine, HttpStatus status, long startNanos) {
        if (!accessLog) {
            return;
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        String client = remote == null ? "-" : remote.getAddress().getHostAddress();
        System.out.printf("%s \"%s\" %d %dms%n", client, requestLine, status.code(), millis);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Nothing useful to do while shutting down.
        }
    }

    /** Serves all requests arriving on one TCP connection. Runs on a virtual thread. */
    private final class Connection implements Runnable {

        private final Socket socket;
        private volatile boolean idle = true;

        Connection(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            InetSocketAddress remote = (InetSocketAddress) socket.getRemoteSocketAddress();
            RequestParser parser = new RequestParser(maxBodyBytes);
            try (socket) {
                socket.setSoTimeout((int) readTimeout.toMillis());
                socket.setTcpNoDelay(true);
                InputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = new BufferedOutputStream(socket.getOutputStream());

                while (true) {
                    // Mark idle before checking 'running' so stop() never misses a waiting connection.
                    idle = true;
                    if (!running) {
                        break;
                    }
                    long start = System.nanoTime();
                    HttpRequest request;
                    try {
                        request = parser.parse(in, remote);
                    } catch (HttpException e) {
                        idle = false;
                        ResponseWriter.write(out, errorResponse(e.status(), e.getMessage()), false, false);
                        logAccess(remote, "<malformed>", e.status(), start);
                        break;
                    }
                    if (request == null) {
                        break;
                    }
                    idle = false;

                    boolean keepAlive = wantsKeepAlive(request) && running;
                    HttpResponse response = dispatch(request);
                    ResponseWriter.write(out, response, request.method() == HttpMethod.HEAD, keepAlive);
                    logAccess(remote, request.toString(), response.status(), start);
                    if (!keepAlive) {
                        break;
                    }
                }
            } catch (SocketTimeoutException e) {
                // The client kept the connection open without sending anything; just close it.
            } catch (IOException e) {
                if (running) {
                    LOG.log(System.Logger.Level.DEBUG, "Connection error: " + e.getMessage());
                }
            } finally {
                connections.remove(this);
            }
        }

        void closeIfIdle() {
            if (idle) {
                forceClose();
            }
        }

        void forceClose() {
            closeQuietly(socket);
        }
    }

    /** Configuration for {@link HttpServer}. */
    public static final class Builder {

        private int port = 8080;
        private Router router = new Router();
        private Duration readTimeout = Duration.ofSeconds(15);
        private int maxBodyBytes = 1024 * 1024;
        private Duration shutdownGrace = Duration.ofSeconds(5);
        private boolean accessLog;

        private Builder() {
        }

        /** Port to listen on; 0 picks any free port. Default 8080. */
        public Builder port(int port) {
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("Port out of range: " + port);
            }
            this.port = port;
            return this;
        }

        public Builder router(Router router) {
            this.router = Objects.requireNonNull(router, "router");
            return this;
        }

        /** How long a connection may stay silent before it is closed. Default 15 seconds. */
        public Builder readTimeout(Duration readTimeout) {
            this.readTimeout = Objects.requireNonNull(readTimeout, "readTimeout");
            return this;
        }

        /** Largest accepted request body; bigger requests get 413. Default 1 MiB. */
        public Builder maxBodyBytes(int maxBodyBytes) {
            if (maxBodyBytes < 0) {
                throw new IllegalArgumentException("maxBodyBytes must not be negative");
            }
            this.maxBodyBytes = maxBodyBytes;
            return this;
        }

        /** How long {@link HttpServer#stop()} waits for in-flight requests. Default 5 seconds. */
        public Builder shutdownGrace(Duration shutdownGrace) {
            this.shutdownGrace = Objects.requireNonNull(shutdownGrace, "shutdownGrace");
            return this;
        }

        /** Prints one line per request to standard output. Default off. */
        public Builder accessLog(boolean accessLog) {
            this.accessLog = accessLog;
            return this;
        }

        public HttpServer build() {
            return new HttpServer(this);
        }
    }
}
