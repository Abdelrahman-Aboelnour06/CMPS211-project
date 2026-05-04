package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;


public class SessionManager {




    private final Map<String, Session>          sessions       = new HashMap<>();


    private final Map<String, Session>          codeIndex      = new HashMap<>();


    private final Map<WebSocketSession, String> clientNames    = new HashMap<>();


    private final Map<WebSocketSession, String> clientSessions = new HashMap<>();


    private final Map<WebSocketSession, String> clientRoles    = new HashMap<>();


    private final Map<String, DisconnectedClient> disconnectedClients = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    private static final long RECONNECT_TIMEOUT_SECONDS = 5 * 60; // 5 minutes


    public Session createSession(WebSocketSession conn, String username) {
        String editorCode = generateCode();
        String viewerCode = generateCode();

        while (viewerCode.equals(editorCode)) viewerCode = generateCode();

        Session session = new Session(editorCode, viewerCode, username, conn);
        sessions.put(editorCode, session);
        codeIndex.put(editorCode, session);
        codeIndex.put(viewerCode, session);

        clientNames.put(conn, username);
        clientSessions.put(conn, editorCode);
        clientRoles.put(conn, "editor");
        return session;
    }


    public Session joinSession(String code, WebSocketSession conn, String username) {
        String upperCode = code.toUpperCase();
        Session session = codeIndex.get(upperCode);
        if (session == null) return null;

        session.addClient(conn);

        String role = upperCode.equals(session.getEditorCode()) ? "editor" : "viewer";

        clientNames.put(conn, username);
        clientSessions.put(conn, session.getEditorCode());
        clientRoles.put(conn, role);
        return session;
    }

    public void removeClient(WebSocketSession conn) {
        String editorCode = clientSessions.get(conn);
        String username   = clientNames.get(conn);

        if (editorCode != null) {
            Session session = sessions.get(editorCode);
            if (session != null) {
                session.removeClient(conn);
                if (session.getClients().isEmpty()) {
                    sessions.remove(editorCode);
                    codeIndex.remove(editorCode);
                    codeIndex.remove(session.getViewerCode());
                }
            }
        }



        if (username != null && editorCode != null) {
            DisconnectedClient dc = new DisconnectedClient(
                    username, editorCode, clientRoles.getOrDefault(conn, "editor"),
                    Instant.now()
            );
            disconnectedClients.put(username, dc);


            scheduler.schedule(() -> disconnectedClients.remove(username),
                    RECONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        clientNames.remove(conn);
        clientSessions.remove(conn);
        clientRoles.remove(conn);
    }

     public DisconnectedClient getDisconnectedClient(String username) {
        return disconnectedClients.get(username);
    }


    public void clearDisconnectedClient(String username) {
        disconnectedClients.remove(username);
    }

    public void bufferMissedOp(String sessionEditorCode, String opJson) {
        for (DisconnectedClient dc : disconnectedClients.values()) {
            if (sessionEditorCode.equals(dc.sessionEditorCode)) {
                dc.missedOps.add(opJson);
            }
        }
    }


    public boolean isEditor(WebSocketSession conn) {
        return "editor".equals(clientRoles.get(conn));
    }

    public String getRole(WebSocketSession conn) {
        return clientRoles.get(conn);
    }

    public List<WebSocketSession> getOtherClients(WebSocketSession sender) {
        String editorCode = clientSessions.get(sender);
        if (editorCode == null) return new ArrayList<>();
        Session session = sessions.get(editorCode);
        if (session == null) return new ArrayList<>();
        List<WebSocketSession> others = new ArrayList<>(session.getClients());
        others.remove(sender);
        return others;
    }

    public String getSessionCode(WebSocketSession conn) {
        return clientSessions.get(conn);
    }

    public List<String> getActiveUserNames(String editorCode) {
        Session session = sessions.get(editorCode.toUpperCase());
        if (session == null) return new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (WebSocketSession client : session.getClients()) {
            String name = clientNames.get(client);
            if (name != null) names.add(name);
        }
        return names;
    }

    public List<WebSocketSession> getAllClientsInSession(String editorCode) {
        Session session = sessions.get(editorCode.toUpperCase());
        if (session == null) return new ArrayList<>();
        return new ArrayList<>(session.getClients());
    }


    public Session getSession(String code) {
        return codeIndex.get(code.toUpperCase());
    }

    private String generateCode() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder code = new StringBuilder();
        Random random = new Random();
        for (int i = 0; i < 6; i++) code.append(chars.charAt(random.nextInt(chars.length())));
        return code.toString();
    }

    public static class DisconnectedClient {
        public final String username;
        public final String sessionEditorCode;
        public final String role;
        public final Instant disconnectedAt;
        public final List<String> missedOps = new ArrayList<>();

        DisconnectedClient(String username, String sessionEditorCode,
                           String role, Instant disconnectedAt) {
            this.username          = username;
            this.sessionEditorCode = sessionEditorCode;
            this.role              = role;
            this.disconnectedAt    = disconnectedAt;
        }
    }

    public static class Session {
        private final String editorCode;
        private final String viewerCode;
        private final List<WebSocketSession> clients      = new ArrayList<>();
        private final List<String>           operationLog = new ArrayList<>();



        private int opCount = 0;

        private final java.util.List<Comment> sessionComments = new java.util.ArrayList<>();

        public java.util.List<Comment> getSessionComments() { return sessionComments; }

        public void addSessionComment(Comment c) {
            sessionComments.add(c);
        }

        public boolean removeSessionComment(String commentId) {
            return sessionComments.removeIf(c -> c.id.equals(commentId));
        }

        public void resolveSessionComment(String commentId) {
            sessionComments.stream()
                    .filter(c -> c.id.equals(commentId))
                    .findFirst()
                    .ifPresent(c -> c.resolved = true);
        }



        public Session(String editorCode, String viewerCode,
                       String ownerName, WebSocketSession owner) {
            this.editorCode = editorCode;
            this.viewerCode = viewerCode;
            this.clients.add(owner);
        }

        public void addClient(WebSocketSession conn)    { clients.add(conn); }
        public void removeClient(WebSocketSession conn) { clients.remove(conn); }
        public List<WebSocketSession> getClients()      { return clients; }
        public String getEditorCode()                   { return editorCode; }
        public String getViewerCode()                   { return viewerCode; }

        public void logOperation(String json)           {
            operationLog.add(json);
            opCount++;
        }
        public List<String> getOperationLog()           { return operationLog; }


        public boolean shouldAutoSave()                 { return opCount % 10 == 0 && opCount > 0; }
    }
    public Session restoreSession(String editorCode, String viewerCode,
                                  String username, WebSocketSession conn) {
        Session session = new Session(editorCode, viewerCode, username, conn);
        sessions.put(editorCode, session);
        codeIndex.put(editorCode, session);
        codeIndex.put(viewerCode, session);
        clientNames.put(conn, username);
        clientSessions.put(conn, editorCode);
        clientRoles.put(conn, "editor");
        return session;
    }
}
