package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class Server extends TextWebSocketHandler {

    private final SessionManager sessionManager = new SessionManager();

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        System.out.println("[Server] Client connected: " + session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        System.out.println("[Server] Client disconnected: " + session.getId());
        String code = sessionManager.getSessionCode(session);
        sessionManager.removeClient(session);
        if (code != null) {
            broadcastActiveUsers(code);
        }
    }

    // -----------------------------------------------------------------------
    // Message dispatch
    // -----------------------------------------------------------------------

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            Operations op = Operations.fromJson(message.getPayload());
            if (op == null || op.type == null) {
                sendError(session, "Malformed message");
                return;
            }

            switch (op.type) {

                // ----------------------------------------------------------
                // Session management
                // ----------------------------------------------------------
                case "CREATE_SESSION" -> {
                    if (op.username == null || op.username.isBlank()) {
                        sendError(session, "Username is required");
                        return;
                    }
                    SessionManager.Session s = sessionManager.createSession(session, op.username.trim());

                    Operations response = new Operations();
                    response.type        = "SESSION_CREATED";
                    response.sessionCode = s.getCode();
                    sendTo(session, response.toJson());
                    broadcastActiveUsers(s.getCode());

                    System.out.println("[Server] Session created: " + s.getCode()
                            + " by " + op.username);
                }

                case "JOIN_SESSION" -> {
                    if (op.username == null || op.username.isBlank()) {
                        sendError(session, "Username is required");
                        return;
                    }
                    if (op.sessionCode == null || op.sessionCode.isBlank()) {
                        sendError(session, "Session code is required");
                        return;
                    }

                    SessionManager.Session s = sessionManager.joinSession(
                            op.sessionCode.trim().toUpperCase(), session, op.username.trim());

                    if (s == null) {
                        sendError(session, "Session not found: " + op.sessionCode);
                    } else {
                        Operations response = new Operations();
                        response.type        = "SESSION_JOINED";
                        response.sessionCode = s.getCode();
                        sendTo(session, response.toJson());

                        broadcastActiveUsers(s.getCode());
                        System.out.println("[Server] " + op.username + " joined " + s.getCode());
                    }

                }

                // ----------------------------------------------------------
                // CRDT operations — relay to all OTHER clients in the same session
                // ----------------------------------------------------------
                case "INSERT_CHAR", "DELETE_CHAR" -> {
                    String code = sessionManager.getSessionCode(session);
                    if (code == null) {
                        sendError(session, "Not in a session");
                        return;
                    }
                    List<WebSocketSession> others = sessionManager.getOtherClients(session);
                    String payload = message.getPayload();
                    for (WebSocketSession other : others) {
                        sendTo(other, payload);
                    }
                }

                // ----------------------------------------------------------
                // Cursor broadcast — relay as-is (lightweight, no validation needed)
                // ----------------------------------------------------------
                case "CURSOR" -> {
                    List<WebSocketSession> others = sessionManager.getOtherClients(session);
                    String payload = message.getPayload();
                    for (WebSocketSession other : others) {
                        sendTo(other, payload);
                    }
                }
                case "GET_ACTIVE_USERS" -> {
                    String code = sessionManager.getSessionCode(session);
                    if (code == null) {
                        sendError(session, "Not in a session");
                        return;
                    }
                    broadcastActiveUsers(code);
                }

                // ----------------------------------------------------------
                // Unknown type
                // ----------------------------------------------------------
                default -> {
                    sendError(session, "Unknown message type: " + op.type);
                    System.err.println("[Server] Unknown type '" + op.type
                            + "' from " + session.getId());
                }
            }

        } catch (Exception e) {
            System.err.println("[Server] Failed to handle message: " + e.getMessage());
            sendError(session, "Internal server error");
        }
    }

    // -----------------------------------------------------------------------
    // Broadcast helpers
    // -----------------------------------------------------------------------

    /**
     * Sends the current list of active usernames to every client in the session.
     * Called after JOIN_SESSION and after a client disconnects.
     */
    private void broadcastActiveUsers(String sessionCode) {
        List<WebSocketSession> clients  = sessionManager.getAllClientsInSession(sessionCode);
        List<String>           names    = sessionManager.getActiveUserNames(sessionCode);

        Operations op = new Operations();
        op.type    = "ACTIVE_USERS";
        op.payload = new com.google.gson.Gson().toJson(names);
        String json = op.toJson();

        for (WebSocketSession client : clients) {
            sendTo(client, json);
        }

        System.out.println("[Server] Active users in " + sessionCode + ": " + names);
    }

    // -----------------------------------------------------------------------
    // Low-level send utilities
    // -----------------------------------------------------------------------

    /** Thread-safe send that swallows IO exceptions so one bad client can't crash the loop. */
    public void sendTo(WebSocketSession session, String json) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception e) {
            System.err.println("[Server] Send failed to " + session.getId()
                    + ": " + e.getMessage());
        }
    }

    private void sendError(WebSocketSession session, String message) {
        Operations err = new Operations();
        err.type    = "ERROR";
        err.payload = message;
        sendTo(session, err.toJson());
    }
}