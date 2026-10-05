package com.collabcanvas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** End-to-end: real Tomcat, real WebSocket clients. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "app.allowed-origins=http://test.local"
})
class BoardSocketIntegrationTest {

    private static final String ORIGIN = "http://test.local";

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate http;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Collects incoming messages so tests can wait for a specific type. */
    static final class Client extends TextWebSocketHandler {
        final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
        final ObjectMapper mapper = new ObjectMapper();
        WebSocketSession session;

        @Override
        protected void handleTextMessage(WebSocketSession s, TextMessage m) throws Exception {
            inbox.add(mapper.readTree(m.getPayload()));
        }

        JsonNode next(String type) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                JsonNode n = inbox.poll(100, TimeUnit.MILLISECONDS);
                if (n != null && type.equals(n.path("t").asText())) {
                    return n;
                }
            }
            throw new AssertionError("no '" + type + "' message within 5s");
        }

        void send(String json) throws Exception {
            session.sendMessage(new TextMessage(json));
        }
    }

    private String createBoard() {
        ResponseEntity<JsonNode> r = http.postForEntity("/api/boards", null, JsonNode.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().get("boardId").asText();
    }

    private Client connect(String boardId, String origin) throws Exception {
        Client c = new Client();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin(origin);
        c.session = new StandardWebSocketClient()
                .execute(c, headers, URI.create("ws://127.0.0.1:" + port + "/ws/boards/" + boardId))
                .get(5, TimeUnit.SECONDS);
        return c;
    }

    @Test
    void opsFromOnePeerReachTheOtherAndLateJoinersGetTheSnapshot() throws Exception {
        String board = createBoard();
        Client a = connect(board, ORIGIN);
        JsonNode welcomeA = a.next("welcome");
        assertThat(welcomeA.get("seq").asLong()).isZero();
        Client b = connect(board, ORIGIN);
        b.next("welcome");
        assertThat(a.next("presence").get("peers")).hasSize(2);

        a.send("""
                {"t":"ops","ops":[{"opId":"op-1","kind":"add","shape":{"id":"s1","type":"RECT",
                 "x":10,"y":20,"w":100,"h":50,"stroke":"#112233","fill":"none","width":2,"z":"a0"}}]}""");

        JsonNode ackA = a.next("committed");
        JsonNode seenB = b.next("committed");
        assertThat(ackA.get("opId").asText()).isEqualTo("op-1");
        assertThat(seenB.get("seq").asLong()).isEqualTo(1);
        assertThat(seenB.get("author").asText()).isEqualTo(welcomeA.get("you").get("userId").asText());
        assertThat(seenB.at("/op/shape/version").asLong()).isEqualTo(1);

        b.send("""
                {"t":"ops","ops":[{"opId":"op-2","kind":"update","id":"s1","set":{"x":300}}]}""");
        assertThat(a.next("committed").at("/op/set/x").asDouble()).isEqualTo(300);

        Client c = connect(board, ORIGIN);
        JsonNode welcomeC = c.next("welcome");
        assertThat(welcomeC.get("seq").asLong()).isEqualTo(2);
        assertThat(welcomeC.get("shapes")).hasSize(1);
        assertThat(welcomeC.at("/shapes/0/x").asDouble()).isEqualTo(300);

        a.session.close();
        b.session.close();
        c.session.close();
    }

    @Test
    void previewIsRelayedToOthersButNotStored() throws Exception {
        String board = createBoard();
        Client a = connect(board, ORIGIN);
        a.next("welcome");
        Client b = connect(board, ORIGIN);
        b.next("welcome");

        a.send("""
                {"t":"preview","sid":"p1","pts":[1,2,3,4],"stroke":"#000000","width":3,"done":false}""");
        JsonNode p = b.next("preview");
        assertThat(p.get("pts")).hasSize(4);

        ResponseEntity<JsonNode> info = http.getForEntity("/api/boards/" + board, JsonNode.class);
        assertThat(info.getBody().get("seq").asLong()).isZero();
        a.session.close();
        b.session.close();
    }

    @Test
    void invalidOpIsRejectedToAuthorOnly() throws Exception {
        String board = createBoard();
        Client a = connect(board, ORIGIN);
        a.next("welcome");
        a.send("""
                {"t":"ops","ops":[{"opId":"op-9","kind":"add","shape":{"id":"s1","type":"RECT",
                 "x":10,"y":20,"w":100,"h":50,"stroke":"red","fill":"none","width":2,"z":"a0"}}]}""");
        JsonNode r = a.next("rejected");
        assertThat(r.get("opId").asText()).isEqualTo("op-9");
        assertThat(r.get("reason").asText()).isEqualTo("invalid_stroke");
        a.session.close();
    }

    @Test
    void foreignOriginIsRefused() {
        String board = createBoard();
        assertThatThrownBy(() -> connect(board, "http://evil.example"))
                .isInstanceOf(ExecutionException.class)
                .hasStackTraceContaining("403");
    }

    @Test
    void unknownBoardIsRefused() {
        assertThatThrownBy(() -> connect("00000000-0000-4000-8000-000000000000", ORIGIN))
                .isInstanceOf(ExecutionException.class)
                .hasStackTraceContaining("404");
    }

    @Test
    void securityHeadersAreSent() {
        ResponseEntity<String> r = http.getForEntity("/api/health", String.class);
        assertThat(r.getHeaders().getFirst("Content-Security-Policy")).contains("script-src 'self'");
        assertThat(r.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(r.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }
}
