package com.collabcanvas.config;

import com.collabcanvas.board.BoardRegistry;
import com.collabcanvas.ws.BoardSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.Arrays;
import java.util.Map;

/**
 * {@code ws(s)://host/ws/boards/{boardId}}.
 *
 * <p>Cross-site WebSocket hijacking is blocked by an Origin allow-list ({@code app.allowed-origins});
 * browsers always send Origin on WebSocket handshakes, so a page on another site is refused (403).
 * Unknown or malformed board ids are refused before the upgrade (404).
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    public static final int MAX_MESSAGE_BYTES = 256 * 1024;

    private final BoardSocketHandler handler;
    private final BoardRegistry registry;
    private final String[] allowedOrigins;

    public WebSocketConfig(BoardSocketHandler handler, BoardRegistry registry,
                           @Value("${app.allowed-origins}") String allowedOrigins) {
        this.handler = handler;
        this.registry = registry;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.equals("*"))   // never allow a wildcard origin
                .toArray(String[]::new);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry reg) {
        reg.addHandler(handler, "/ws/boards/{boardId}")
                .addInterceptors(new BoardLookupInterceptor())
                .setAllowedOrigins(allowedOrigins);
    }

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean c = new ServletServerContainerFactoryBean();
        c.setMaxTextMessageBufferSize(MAX_MESSAGE_BYTES);
        c.setMaxBinaryMessageBufferSize(1024);           // binary frames are not used
        c.setMaxSessionIdleTimeout(10L * 60 * 1000);     // client sends cursor/preview or reconnects
        return c;
    }

    /** Resolves the board from the URL before upgrading; refuses unknown boards. */
    private final class BoardLookupInterceptor implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            String path = request.getURI().getPath();
            String boardId = path.substring(path.lastIndexOf('/') + 1);
            return registry.get(boardId).map(b -> {
                attributes.put("board", b);
                return true;
            }).orElseGet(() -> {
                response.setStatusCode(HttpStatus.NOT_FOUND);
                return false;
            });
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
        }
    }
}
