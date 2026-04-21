package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import org.springframework.web.socket.WebSocketSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class SessionManager {

    private final Map<String, Session>           sessions      = new HashMap<>();
    private final Map<WebSocketSession, String>  clientNames   = new HashMap<>();
    private final Map<WebSocketSession, String>  clientSessions = new HashMap<>();



    // -----------------------------------------------------------------------
    // Session lifecycle
    // -----------------------------------------------------------------------

    public Session createSession(WebSocketSession conn, String username) {
        String editorCode = generateCode();
        Session session = new Session(editorCode, username, conn);
        sessions.put(editorCode, session);
        clientNames.put(conn, username);
        clientSessions.put(conn, editorCode);
        return session;
    }

    public Session getSession(String code) {
        return sessions.get(code.toUpperCase());
    }

    public Session joinSession(String code, WebSocketSession conn, String username) {
        Session session = sessions.get(code.toUpperCase());
        if (session == null) return null;
        session.addClient(conn);
        clientNames.put(conn, username);
        clientSessions.put(conn, code.toUpperCase());
        return session;
    }

    public void removeClient(WebSocketSession conn) {
        String code = clientSessions.get(conn);
        if (code != null) {
            Session session = sessions.get(code);
            if (session != null) {
                session.removeClient(conn);
                // Clean up empty sessions
                if (session.getClients().isEmpty()) {
                    sessions.remove(code);
                }
            }
        }
        clientNames.remove(conn);
        clientSessions.remove(conn);
    }

    // -----------------------------------------------------------------------
    // Query helpers
    // -----------------------------------------------------------------------

    /** Returns all WebSocket sessions in the same session as {@code sender}, excluding sender itself. */
    public List<WebSocketSession> getOtherClients(WebSocketSession sender) {
        String code = clientSessions.get(sender);
        if (code == null) return new ArrayList<>();
        Session session = sessions.get(code);
        if (session == null) return new ArrayList<>();
        List<WebSocketSession> others = new ArrayList<>(session.getClients());
        others.remove(sender);
        return others;
    }

    /** Returns the session code the given connection belongs to, or null. */
    public String getSessionCode(WebSocketSession conn) {
        return clientSessions.get(conn);
    }

    /** Returns the display names of every active client in the given session. */
    public List<String> getActiveUserNames(String code) {
        Session session = sessions.get(code.toUpperCase());
        if (session == null) return new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (WebSocketSession client : session.getClients()) {
            String name = clientNames.get(client);
            if (name != null) names.add(name);
        }
        return names;
    }

    /**
     * Returns ALL WebSocket sessions (including the requester) that belong to a session.
     * Used by Server when broadcasting to everyone (e.g. ACTIVE_USERS).
     */
    public List<WebSocketSession> getAllClientsInSession(String code) {
        Session session = sessions.get(code.toUpperCase());
        if (session == null) return new ArrayList<>();
        return new ArrayList<>(session.getClients());
    }

    // -----------------------------------------------------------------------
    // Code generation
    // -----------------------------------------------------------------------

    private String generateCode() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder code = new StringBuilder();
        Random random = new Random();
        for (int i = 0; i < 6; i++) {
            code.append(chars.charAt(random.nextInt(chars.length())));
        }
        return code.toString();
    }

    // -----------------------------------------------------------------------
    // Inner Session class
    // -----------------------------------------------------------------------

    /*public static class Session {
        private final String code;
        private final List<WebSocketSession> clients = new ArrayList<>();

        public Session(String code, String ownerName, WebSocketSession owner) {
            this.code = code;
            this.clients.add(owner);
        }

        public void addClient(WebSocketSession conn)    { clients.add(conn); }
        public void removeClient(WebSocketSession conn) { clients.remove(conn); }
        public List<WebSocketSession> getClients()      { return clients; }
        public String getCode()                         { return code; }
    }/
     */
    public static class Session {
        private final String code;
        private final List<WebSocketSession> clients = new ArrayList<>();
        private final List<String> operationLog = new ArrayList<>(); // stores all past ops

        public Session(String code, String ownerName, WebSocketSession owner) {
            this.code = code;
            this.clients.add(owner);
        }

        public void addClient(WebSocketSession conn)    { clients.add(conn); }
        public void removeClient(WebSocketSession conn) { clients.remove(conn); }
        public List<WebSocketSession> getClients()      { return clients; }
        public String getCode()                         { return code; }
        public void logOperation(String json)           { operationLog.add(json); }
        public List<String> getOperationLog()           { return operationLog; }
    }
}