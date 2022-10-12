package ok.dht.test.shestakova;

import jdk.incubator.foreign.MemorySegment;
import ok.dht.Service;
import ok.dht.ServiceConfig;
import ok.dht.test.ServiceFactory;
import ok.dht.test.shestakova.dao.MemorySegmentDao;
import ok.dht.test.shestakova.dao.base.BaseEntry;
import ok.dht.test.shestakova.dao.base.Config;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.net.Session;
import one.nio.server.AcceptorConfig;
import one.nio.server.SelectorThread;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.net.http.HttpClient.newHttpClient;

public class DemoService implements Service {

    private final ServiceConfig config;
    private HttpServer server;
    private MemorySegmentDao dao;
    private static final long FLUSH_THRESHOLD = 1 << 20; // 1 MB
    private static final int POOL_SIZE = Runtime.getRuntime().availableProcessors();
    private static final int QUEUE_CAPACITY = 256;
    private static final String PATH = "/v0/entity?id=";
    private ExecutorService workersPool;
    private HttpClient httpClient;

    public DemoService(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<?> start() throws IOException {
        if (Files.notExists(config.workingDir())) {
            Files.createDirectory(config.workingDir());
        }

        dao = new MemorySegmentDao(new Config(config.workingDir(), FLUSH_THRESHOLD));
        workersPool = new ThreadPoolExecutor(
                POOL_SIZE,
                POOL_SIZE,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY)
        );
        httpClient = newHttpClient();

        server = new HttpServer(createConfigFromPort(config.selfPort())) {
            @Override
            public void handleRequest(Request request, HttpSession session) {
                String key = request.getParameter("id=");
                if (key == null || key.isEmpty()) {
                    try {
                        handleDefault(request, session);
                        return;
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

                String targetCluster = getClusterByRendezvousHashing(key);
                if (!targetCluster.equals(config.selfUrl())) {
                    try {
                        HttpRequest httpRequest = buildHttpRequest(key, targetCluster, request);

                        if (httpRequest == null) {
                            handleDefault(request, session);
                            return;
                        }

                        HttpResponse<byte[]> response = httpClient
                                .sendAsync(
                                        httpRequest,
                                        HttpResponse.BodyHandlers.ofByteArray()
                                )
                                .get(1, TimeUnit.SECONDS);
                        session.sendResponse(new Response(
                                String.valueOf(response.statusCode()),
                                response.body()
                        ));
                    } catch (IOException | InterruptedException | ExecutionException | TimeoutException e) {
                        throw new RuntimeException(e);
                    }
                    return;
                }

                workersPool.execute(() -> {
                    try {
                        super.handleRequest(request, session);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }

            @Override
            public void handleDefault(Request request, HttpSession session) throws IOException {
                Response response;
                int requestMethod = request.getMethod();
                if (requestMethod == Request.METHOD_GET
                        || requestMethod == Request.METHOD_PUT
                        || requestMethod == Request.METHOD_DELETE) {
                    response = new Response(
                            Response.BAD_REQUEST,
                            Response.EMPTY
                    );
                } else {
                    response = new Response(
                            Response.METHOD_NOT_ALLOWED,
                            Response.EMPTY
                    );
                }
                session.sendResponse(response);
            }

            @Override
            public synchronized void stop() {
                for (SelectorThread selectorThread : selectors) {
                    for (Session session : selectorThread.selector) {
                        session.close();
                    }
                }
                super.stop();
            }

            private HttpRequest.Builder request(String path, String clusterUrl) {
                return HttpRequest.newBuilder(URI.create(clusterUrl + path));
            }

            private HttpRequest.Builder requestForKey(String key, String clusterUrl) {
                return request(PATH + key, clusterUrl);
            }

            private HttpRequest buildHttpRequest(String key, String targetCluster, Request request) {
                HttpRequest.Builder httpRequest = requestForKey(key, targetCluster);
                int requestMethod = request.getMethod();
                if (requestMethod == Request.METHOD_GET) {
                    httpRequest.GET();
                } else if (requestMethod == Request.METHOD_PUT) {
                    httpRequest.PUT(HttpRequest.BodyPublishers.ofByteArray(request.getBody()));
                } else if (requestMethod == Request.METHOD_DELETE) {
                    httpRequest.DELETE();
                } else {
                    return null;
                }
                return httpRequest.build();
            }
        };
        server.addRequestHandlers(this);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<?> stop() throws IOException {
        server.stop();
        workersPool.shutdown();
        try {
            if (!workersPool.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new RuntimeException("Error during termination");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        dao.close();
        return CompletableFuture.completedFuture(null);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response handleGet(@Param(value = "id") String id) {
        BaseEntry<MemorySegment> entry = dao.get(fromString(id));

        if (entry == null) {
            return new Response(
                    Response.NOT_FOUND,
                    Response.EMPTY
            );
        }

        return new Response(
                Response.OK,
                entry.value().toByteArray()
        );
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response handlePut(Request request, @Param(value = "id") String id) {
        dao.upsert(new BaseEntry<>(
                fromString(id),
                MemorySegment.ofArray(request.getBody())
        ));

        return new Response(
                Response.CREATED,
                Response.EMPTY
        );
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response handleDelete(@Param(value = "id") String id) {
        dao.upsert(new BaseEntry<>(
                fromString(id),
                null
        ));

        return new Response(
                Response.ACCEPTED,
                Response.EMPTY
        );
    }

    private String getClusterByRendezvousHashing(String key) {
        int hashVal = Integer.MIN_VALUE;
        String cluster = null;
        final int keyHash = key.hashCode();

        for (String clusterUrl : config.clusterUrls()) {
            int tmpHash = getHashCodeForTwoElements(keyHash, clusterUrl);
            if (tmpHash > hashVal) {
                hashVal = tmpHash;
                cluster = clusterUrl;
            }
        }
        return cluster;
    }

    private int getHashCodeForTwoElements(int hash, String s) {
        int h = hash;
        for (char v : s.toCharArray()) {
            h = 31 * h + v;
        }
        return h;
    }

    private static HttpServerConfig createConfigFromPort(int port) {
        HttpServerConfig httpConfig = new HttpServerConfig();
        AcceptorConfig acceptor = new AcceptorConfig();
        acceptor.port = port;
        acceptor.reusePort = true;
        httpConfig.acceptors = new AcceptorConfig[]{acceptor};
        return httpConfig;
    }

    public MemorySegment fromString(String data) {
        return data == null ? null : MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }

    @ServiceFactory(stage = 3, week = 1)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new DemoService(config);
        }
    }
}
