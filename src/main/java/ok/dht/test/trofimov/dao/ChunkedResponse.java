package ok.dht.test.trofimov.dao;

import one.nio.http.Response;

public class ChunkedResponse extends Response {

    private final Entry<String> data;

    public ChunkedResponse(String resultCode, Entry<String> data) {
        super(resultCode);
        this.data = data;
        addHeader("Transfer-Encoding: chunked");
    }

    public Entry<String> getData() {
        return data;
    }
}
