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

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        System.out.println("Client connected: " + session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            Operations op = Operations.fromJson(message.getPayload());

            switch (op.type) {

                case "CREATE_SESSION" -> {
                    SessionManager.Session s = sessionManager.createSession(session, op.username);
                    Operations response = new Operations();
                    response.type = "SESSION_CREATED";
                    response.sessionCode = s.getCode();
                    sendTo(session, response.toJson());
                    System.out.println("Session created: " + s.getCode());
                }

                case "JOIN_SESSION" -> {
                    SessionManager.Session s = sessionManager.joinSession(
                            op.sessionCode, session, op.username);
                    if (s == null) {
                        Operations err = new Operations();
                        err.type = "ERROR";
                        err.payload = "Invalid session code";
                        sendTo(session, err.toJson());
                    } else {
                        Operations response = new Operations();
                        response.type = "SESSION_JOINED";
                        response.sessionCode = s.getCode();
                        sendTo(session, response.toJson());
                        broadcastActiveUsers(s.getCode());
                    }
                }

                case "INSERT_CHAR", "DELETE_CHAR", "CURSOR" -> {
                    List<WebSocketSession> others = sessionManager.getOtherClients(session);
                    for (WebSocketSession other : others) {
                        sendTo(other, message.getPayload());
                    }
                }

                default -> {
                    Operations err = new Operations();
                    err.type = "ERROR";
                    err.payload = "Unknown type: " + op.type;
                    sendTo(session, err.toJson());
                }
            }

        } catch (Exception e) {
            System.err.println("Bad message: " + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        System.out.println("Client disconnected: " + session.getId());
    }

    public void sendTo(WebSocketSession session, String json) {
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (Exception e) {
            System.err.println("Send failed: " + e.getMessage());
        }
    }
    private void broadcastActiveUsers(String sessionCode) {
        List<WebSocketSession> clients = sessionManager.getAllClientsInSession(sessionCode);
        List<String> names = sessionManager.getActiveUserNames(sessionCode);
        Operations op = new Operations();
        op.type = "ACTIVE_USERS";
        op.payload = new com.google.gson.Gson().toJson(names);
        String json = op.toJson();
        for (WebSocketSession client : clients) {
            sendTo(client, json);
        }
    }

}

