package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Tracks active sessions, connected clients, their roles (editor / viewer),
 * and temporarily disconnected clients (for the Reconnection bonus).
 *
 * Member 2 – Viewer Permissions, Undo/Redo, Reconnection
 * Member 1 – two-code session creation
 */
public class SessionManager {

    // -----------------------------------------------------------------------
    // Primary maps
    // -----------------------------------------------------------------------

    /** All active sessions, keyed by their EDITOR code (upper-case). */
    private final Map<String, Session>          sessions       = new HashMap<>();

    /** editorCode → session  AND  viewerCode → session for fast lookup. */
    private final Map<String, Session>          codeIndex      = new HashMap<>();

    /** WebSocket → display name */
    private final Map<WebSocketSession, String> clientNames    = new HashMap<>();

    /** WebSocket → editor code of the session this client belongs to */
    private final Map<WebSocketSession, String> clientSessions = new HashMap<>();

    /** WebSocket → "editor" | "viewer" */
    private final Map<WebSocketSession, String> clientRoles    = new HashMap<>();

    // -----------------------------------------------------------------------
    // Reconnection support (Member 2 Bonus)
    // -----------------------------------------------------------------------

    /** username → disconnected client info */
    private final Map<String, DisconnectedClient> disconnectedClients = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    private static final long RECONNECT_TIMEOUT_SECONDS = 5 * 60; // 5 minutes

    // -----------------------------------------------------------------------
    // Session lifecycle
    // -----------------------------------------------------------------------

    /**
     * Creates a new session with TWO codes: one for editors and one for viewers.
     * The creator is always an editor.
     */
    public Session createSession(WebSocketSession conn, String username) {
        String editorCode = generateCode();
        String viewerCode = generateCode();
        // Guarantee the two codes are different
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

    /**
     * Joins an existing session using either the editor code or the viewer code.
     * The role is determined by which code was used.
     *
     * @param code  The 6-char code provided by the client (editor OR viewer code).
     */
    public Session joinSession(String code, WebSocketSession conn, String username) {
        String upperCode = code.toUpperCase();
        Session session = codeIndex.get(upperCode);
        if (session == null) return null;

        session.addClient(conn);

        // Determine role
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

        // Move to disconnected map instead of full removal (Reconnection bonus)
        if (username != null && editorCode != null) {
            DisconnectedClient dc = new DisconnectedClient(
                    username, editorCode, clientRoles.getOrDefault(conn, "editor"),
                    Instant.now()
            );
            disconnectedClients.put(username, dc);

            // Schedule permanent removal after 5 minutes
            scheduler.schedule(() -> disconnectedClients.remove(username),
                    RECONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        clientNames.remove(conn);
        clientSessions.remove(conn);
        clientRoles.remove(conn);
    }

    // -----------------------------------------------------------------------
    // Reconnection (Member 2 Bonus)
    // -----------------------------------------------------------------------

    /**
     * Returns the DisconnectedClient info if the username is still within the
     * reconnect window, or null otherwise.
     */
    public DisconnectedClient getDisconnectedClient(String username) {
        return disconnectedClients.get(username);
    }

    /** Call after the client has fully reconnected to purge the disconnected record. */
    public void clearDisconnectedClient(String username) {
        disconnectedClients.remove(username);
    }

    /**
     * Appends a missed operation JSON string to all clients that are currently
     * in the disconnected map.  Called every time an op is broadcast.
     */
    public void bufferMissedOp(String sessionEditorCode, String opJson) {
        for (DisconnectedClient dc : disconnectedClients.values()) {
            if (sessionEditorCode.equals(dc.sessionEditorCode)) {
                dc.missedOps.add(opJson);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Role queries (Member 2)
    // -----------------------------------------------------------------------

    /** Returns true if the given WebSocket connection has "editor" role. */
    public boolean isEditor(WebSocketSession conn) {
        return "editor".equals(clientRoles.get(conn));
    }

    /** Returns the role ("editor" | "viewer") for a connection, or null. */
    public String getRole(WebSocketSession conn) {
        return clientRoles.get(conn);
    }

    // -----------------------------------------------------------------------
    // Query helpers
    // -----------------------------------------------------------------------

    public List<WebSocketSession> getOtherClients(WebSocketSession sender) {
        String editorCode = clientSessions.get(sender);
        if (editorCode == null) return new ArrayList<>();
        Session session = sessions.get(editorCode);
        if (session == null) return new ArrayList<>();
        List<WebSocketSession> others = new ArrayList<>(session.getClients());
        others.remove(sender);
        return others;
    }

    /** Returns the EDITOR code the given connection belongs to, or null. */
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

    /** Look up a session by editor OR viewer code. */
    public Session getSession(String code) {
        return codeIndex.get(code.toUpperCase());
    }

    // -----------------------------------------------------------------------
    // Code generation
    // -----------------------------------------------------------------------

    private String generateCode() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder code = new StringBuilder();
        Random random = new Random();
        for (int i = 0; i < 6; i++) code.append(chars.charAt(random.nextInt(chars.length())));
        return code.toString();
    }

    // -----------------------------------------------------------------------
    // Inner classes
    // -----------------------------------------------------------------------

    /** Holds state for a temporarily disconnected user (Reconnection bonus). */
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

    // -----------------------------------------------------------------------
    // Inner Session class
    // -----------------------------------------------------------------------

    public static class Session {
        private final String editorCode;
        private final String viewerCode;
        private final List<WebSocketSession> clients      = new ArrayList<>();
        private final List<String>           operationLog = new ArrayList<>();

        /** Operation counter for auto-save (triggers every 10 ops). */
        private int opCount = 0;

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

        /** Returns true every 10th operation (triggers auto-save). */
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
