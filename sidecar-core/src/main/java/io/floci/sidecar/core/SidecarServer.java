package io.floci.sidecar.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The HTTP boundary of a sidecar, implementing the sidecar contract v1 ({@code docs/contract.md}):
 * {@code PORT}, {@code GET /health}, JSON request and response handling, and the 400/405/500
 * error envelope. A sidecar registers its {@code /v1/*} handlers and nothing else.
 */
public final class SidecarServer {

    /** The contract major this bootstrap implements. */
    public static final String CONTRACT = "1";

    static final String VERSION_ENV = "SIDECAR_VERSION";
    static final String PORT_ENV = "PORT";
    private static final String DEV_VERSION = "dev";

    private static final Logger LOG = Logger.getLogger(SidecarServer.class);

    private final String name;
    private final HttpServer server;

    private SidecarServer(String name, HttpServer server) {
        this.name = name;
        this.server = server;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** The port the server is bound to; the OS-assigned one when started on port 0. */
    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    public void stop() {
        server.stop(0);
        LOG.infov("{0} sidecar stopped", name);
    }

    /** Reads {@code SIDECAR_VERSION}, which the image bakes in at build time, or {@code dev}. */
    public static String version() {
        String version = System.getenv(VERSION_ENV);
        return version == null || version.isBlank() ? DEV_VERSION : version;
    }

    public static final class Builder {

        private final String name;
        private final Map<String, JsonHandler> routes = new LinkedHashMap<>();
        private final List<Class<? extends Throwable>> badRequestTypes = new ArrayList<>();
        private int defaultPort = 8080;
        private String version = SidecarServer.version();

        private Builder(String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("A sidecar needs a name.");
            }
            this.name = name;
            badRequestTypes.add(IllegalArgumentException.class);
        }

        /** The port used when {@code PORT} is unset. Must match the image's {@code io.floci.sidecar.port} label. */
        public Builder defaultPort(int port) {
            this.defaultPort = port;
            return this;
        }

        /** Overrides the version reported by {@code /health}; tests use it, images rely on {@code SIDECAR_VERSION}. */
        public Builder version(String version) {
            this.version = version;
            return this;
        }

        /** A {@code POST} JSON endpoint. The path must start with {@code /v1/}. */
        public Builder route(String path, JsonHandler handler) {
            if (path == null || !path.startsWith("/v")) {
                throw new IllegalArgumentException("Sidecar routes live under a versioned prefix such as /v1/: " + path);
            }
            routes.put(path, handler);
            return this;
        }

        /** An exception type a handler throws to mean "the request is wrong" (400) rather than "I failed" (500). */
        public Builder badRequestOn(Class<? extends Throwable> type) {
            badRequestTypes.add(type);
            return this;
        }

        /** Starts on {@code PORT}, or on the default port when it is unset. */
        public SidecarServer start() throws IOException {
            return start(portFromEnvironment());
        }

        /** Starts on the given port; {@code 0} lets the OS choose, which tests use. */
        public SidecarServer start(int port) throws IOException {
            HttpServer httpServer = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            ObjectNode health = Json.object()
                    .put("status", "ok")
                    .put("name", name)
                    .put("version", version)
                    .put("contract", CONTRACT);
            httpServer.createContext("/health", exchange -> respondJson(exchange, 200, health));
            for (Map.Entry<String, JsonHandler> route : routes.entrySet()) {
                JsonHandler handler = route.getValue();
                httpServer.createContext(route.getKey(), exchange -> handleJson(exchange, handler));
            }
            httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            httpServer.start();
            SidecarServer started = new SidecarServer(name, httpServer);
            LOG.infov("{0} sidecar {1} (contract {2}) listening on port {3}", name, version, CONTRACT, started.port());
            return started;
        }

        private int portFromEnvironment() {
            String configured = System.getenv(PORT_ENV);
            if (configured == null || configured.isBlank()) {
                return defaultPort;
            }
            try {
                return Integer.parseInt(configured.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(PORT_ENV + " must be a port number, got: " + configured, e);
            }
        }

        private void handleJson(HttpExchange exchange, JsonHandler handler) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respondJson(exchange, 405, Json.error("Method not allowed"));
                return;
            }
            try {
                JsonNode body = Json.mapper().readTree(exchange.getRequestBody());
                if (body == null || body.isMissingNode()) {
                    throw new IllegalArgumentException("A JSON request body is required.");
                }
                respondJson(exchange, 200, handler.handle(body));
            } catch (Exception e) {
                int status = isBadRequest(e) ? 400 : 500;
                if (status == 500) {
                    LOG.warnv(e, "{0} sidecar failed on {1}", name, exchange.getRequestURI().getPath());
                }
                respondJson(exchange, status, Json.error(safeMessage(e)));
            }
        }

        private boolean isBadRequest(Exception e) {
            for (Class<? extends Throwable> type : badRequestTypes) {
                if (type.isInstance(e)) {
                    return true;
                }
            }
            return e instanceof JsonProcessingException;
        }
    }

    private static void respondJson(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = Json.mapper().writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
