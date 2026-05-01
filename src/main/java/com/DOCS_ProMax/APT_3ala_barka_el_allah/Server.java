package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import com.google.gson.Gson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Optional;

/**
 * Central WebSocket message handler.
 *
 * Updated for Phase 3:
 *   Member 1 – SAVE_DOC, LOAD_DOC, LIST_DOCS, DELETE_DOC, auto-save,
 *               GET_VERSIONS, ROLLBACK_VERSION
 *   Member 2 – viewer permission enforcement, UNDO, REDO, RECONNECT
 */
@Component
public class Server extends TextWebSocketHandler {

    // -----------------------------------------------------------------------
    // Dependencies
    // -----------------------------------------------------------------------

    private final SessionManager    sessionManager   = new SessionManager();
    private final UndoRedoManager   undoRedoManager  = new UndoRedoManager();

    /** Spring injects the repository only when MongoDB is on the classpath. */
    @Autowired(required = false)
    private DocumentRepository documentRepository;

    private final Gson gson = new Gson();

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
        sessionManager.removeClient(session);    // moves to disconnectedClients map
        if (code != null) broadcastActiveUsers(code);
    }

    // -----------------------------------------------------------------------
    // Message dispatch
    // -----------------------------------------------------------------------

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            Operations op = Operations.fromJson(message.getPayload());
            if (op == null || op.type == null) { sendError(session, "Malformed message"); return; }

            switch (op.type) {

                // ==============================================================
                // SESSION MANAGEMENT
                // ==============================================================

                case "CREATE_SESSION" -> {
                    if (isBlank(op.username)) { sendError(session, "Username is required"); return; }

                    SessionManager.Session s =
                            sessionManager.createSession(session, op.username.trim());

                    Operations resp = new Operations();
                    resp.type        = "SESSION_CREATED";
                    resp.sessionCode = s.getEditorCode();   // primary code
                    resp.editorCode  = s.getEditorCode();
                    resp.viewerCode  = s.getViewerCode();
                    sendTo(session, resp.toJson());
                    broadcastActiveUsers(s.getEditorCode());

                    System.out.println("[Server] Session created: editor=" + s.getEditorCode()
                            + " viewer=" + s.getViewerCode() + " by " + op.username);
                }

                case "JOIN_SESSION" -> {
                    if (isBlank(op.username))    { sendError(session, "Username is required"); return; }
                    if (isBlank(op.sessionCode)) { sendError(session, "Session code is required"); return; }

                    SessionManager.Session s = sessionManager.joinSession(
                            op.sessionCode.trim().toUpperCase(), session, op.username.trim());

                    if (s == null) {
                        sendError(session, "Session not found: " + op.sessionCode);
                    } else {
                        Operations resp = new Operations();
                        resp.type        = "SESSION_JOINED";
                        resp.sessionCode = s.getEditorCode();
                        resp.editorCode  = s.getEditorCode();
                        resp.viewerCode  = s.getViewerCode();
                        resp.role        = sessionManager.getRole(session);
                        sendTo(session, resp.toJson());

                        // Replay past operations so the joiner sees the current state
                        for (String pastOp : s.getOperationLog()) sendTo(session, pastOp);

                        broadcastActiveUsers(s.getEditorCode());
                        System.out.println("[Server] " + op.username
                                + " joined " + s.getEditorCode()
                                + " as " + resp.role);
                    }
                }

                // ==============================================================
                // RECONNECTION  (Member 2 Bonus)
                // ==============================================================

                case "RECONNECT" -> {
                    if (isBlank(op.username)) { sendError(session, "Username is required"); return; }

                    SessionManager.DisconnectedClient dc =
                            sessionManager.getDisconnectedClient(op.username.trim());

                    if (dc == null) {
                        sendError(session, "No reconnection record found for " + op.username);
                        return;
                    }

                    // Re-join the session with the stored role
                    SessionManager.Session s =
                            sessionManager.joinSession(dc.sessionEditorCode, session, op.username.trim());

                    if (s == null) {
                        sendError(session, "Original session no longer exists");
                        sessionManager.clearDisconnectedClient(op.username.trim());
                        return;
                    }

                    // Send all missed ops
                    for (String missedOp : dc.missedOps) sendTo(session, missedOp);

                    sessionManager.clearDisconnectedClient(op.username.trim());

                    Operations resp = new Operations();
                    resp.type        = "RECONNECTED";
                    resp.sessionCode = s.getEditorCode();
                    resp.role        = dc.role;
                    sendTo(session, resp.toJson());

                    broadcastActiveUsers(s.getEditorCode());
                    System.out.println("[Server] " + op.username + " reconnected to " + s.getEditorCode());
                }

                // ==============================================================
                // CRDT OPERATIONS  (editor-only for mutating ops)
                // ==============================================================

                case "INSERT_CHAR", "DELETE_CHAR" -> {
                    // Member 2: reject if viewer
                    if (!sessionManager.isEditor(session)) {
                        sendError(session, "Viewers cannot edit the document");
                        return;
                    }
                    relayAndLog(session, message.getPayload(), op, true);
                }

                case "FORMAT_CHAR" -> {
                    if (!sessionManager.isEditor(session)) {
                        sendError(session, "Viewers cannot format the document");
                        return;
                    }
                    relayAndLog(session, message.getPayload(), op, true);
                }

                // ==============================================================
                // UNDO / REDO  (Member 2)
                // ==============================================================

                case "UNDO" -> {
                    if (!sessionManager.isEditor(session)) {
                        sendError(session, "Viewers cannot undo");
                        return;
                    }
                    String username = op.username;
                    Operations inverse = undoRedoManager.undo(username);
                    if (inverse == null) { sendError(session, "Nothing to undo"); return; }

                    // Apply and broadcast inverse
                    broadcastToAll(sessionManager.getSessionCode(session), inverse.toJson());
                }

                case "REDO" -> {
                    if (!sessionManager.isEditor(session)) {
                        sendError(session, "Viewers cannot redo");
                        return;
                    }
                    String username = op.username;
                    Operations reapplied = undoRedoManager.redo(username);
                    if (reapplied == null) { sendError(session, "Nothing to redo"); return; }

                    broadcastToAll(sessionManager.getSessionCode(session), reapplied.toJson());
                }

                // ==============================================================
                // CURSOR  (viewers can still broadcast cursor position)
                // ==============================================================

                case "CURSOR" -> {
                    String code = sessionManager.getSessionCode(session);
                    List<WebSocketSession> others = sessionManager.getOtherClients(session);
                    String payload = message.getPayload();
                    sessionManager.bufferMissedOp(code, payload);   // Reconnection bonus
                    for (WebSocketSession other : others) sendTo(other, payload);
                }

                // ==============================================================
                // ACTIVE USERS
                // ==============================================================

                case "GET_ACTIVE_USERS" -> {
                    String code = sessionManager.getSessionCode(session);
                    if (code == null) { sendError(session, "Not in a session"); return; }
                    broadcastActiveUsers(code);
                }

                // ==============================================================
                // DATABASE PERSISTENCE  (Member 1)
                // ==============================================================

                case "SAVE_DOC" -> {
                    if (documentRepository == null) { sendError(session, "Database not configured"); return; }
                    if (!sessionManager.isEditor(session)) { sendError(session, "Only editors can save"); return; }

                    String code = sessionManager.getSessionCode(session);
                    if (code == null) { sendError(session, "Not in a session"); return; }

                    // op.payload carries the serialised crdtJson from the client
                    saveDocument(code, op.ownerUsername, op.payload);

                    Operations resp = new Operations();
                    resp.type    = "DOC_SAVED";
                    resp.payload = "Document saved successfully";
                    sendTo(session, resp.toJson());
                }

                case "LOAD_DOC" -> {
                    if (documentRepository == null) { sendError(session, "Database not configured"); return; }
                    if (isBlank(op.sessionCode))  { sendError(session, "Session code required"); return; }

                    Optional<DocumentEntity> found =
                            documentRepository.findByEditorCodeOrViewerCode(op.sessionCode.trim().toUpperCase());

                    if (found.isEmpty()) { sendError(session, "Document not found"); return; }

                    DocumentEntity doc = found.get();
                    Operations resp = new Operations();
                    resp.type    = "DOC_LOADED";
                    resp.payload = doc.getCrdtJson();
                    sendTo(session, resp.toJson());
                }

                case "LIST_DOCS" -> {
                    if (documentRepository == null) { sendError(session, "Database not configured"); return; }
                    if (isBlank(op.ownerUsername)) { sendError(session, "Owner username required"); return; }

                    List<DocumentEntity> docs =
                            documentRepository.findAllByOwnerUsername(op.ownerUsername.trim());

                    // Build a lightweight JSON array of {editorCode, viewerCode, id}
                    StringBuilder sb = new StringBuilder("[");
                    for (int i = 0; i < docs.size(); i++) {
                        DocumentEntity d = docs.get(i);
                        if (i > 0) sb.append(",");
                        sb.append("{\"id\":\"").append(d.getId())
                                .append("\",\"editorCode\":\"").append(d.getEditorCode())
                                .append("\",\"viewerCode\":\"").append(d.getViewerCode()).append("\"}");
                    }
                    sb.append("]");

                    Operations resp = new Operations();
                    resp.type    = "DOCS_LIST";
                    resp.payload = sb.toString();
                    sendTo(session, resp.toJson());
                }

                case "DELETE_DOC" -> {
                    if (documentRepository == null) { sendError(session, "Database not configured"); return; }
                    if (!sessionManager.isEditor(session)) { sendError(session, "Only editors can delete"); return; }
                    if (isBlank(op.sessionCode)) { sendError(session, "Session code required"); return; }

                    Optional<DocumentEntity> found =
                            documentRepository.findByEditorCode(op.sessionCode.trim().toUpperCase());

                    if (found.isEmpty()) { sendError(session, "Document not found"); return; }

                    documentRepository.delete(found.get());

                    Operations resp = new Operations();
                    resp.type    = "DOC_DELETED";
                    resp.payload = "Document deleted";
                    sendTo(session, resp.toJson());
                }

                // ==============================================================
                // VERSION HISTORY  (Member 1 Bonus)
                // ==============================================================

                case "GET_VERSIONS" -> {
                    if (documentRepository == null) { sendError(session, "Database not configured"); return; }
                    if (isBlank(op.sessionCode)) { sendError(session, "Session code required"); return; }

                    Optional<DocumentEntity> found =
                            documentRepository.findByEditorCodeOrViewerCode(op.sessionCode.trim().toUpperCase());

                    if (found.isEmpty()) { sendError(session, "Document not found"); return; }

                    Operations resp = new Operations();
                    resp.type    = "VERSIONS_LIST";
                    // Send the count and let the client request individual rollbacks
                    resp.payload = gson.toJson(found.get().getVersions());
                    sendTo(session, resp.toJson());
                }

                case "ROLLBACK_VERSION" -> {
                    if (documentRepository == null) { sendError(session, "Database not configured"); return; }
                    if (!sessionManager.isEditor(session)) { sendError(session, "Only editors can rollback"); return; }
                    if (isBlank(op.sessionCode)) { sendError(session, "Session code required"); return; }

                    Optional<DocumentEntity> found =
                            documentRepository.findByEditorCode(op.sessionCode.trim().toUpperCase());

                    if (found.isEmpty()) { sendError(session, "Document not found"); return; }

                    DocumentEntity doc = found.get();
                    List<String> versions = doc.getVersions();

                    if (op.versionIndex < 0 || op.versionIndex >= versions.size()) {
                        sendError(session, "Invalid version index: " + op.versionIndex);
                        return;
                    }

                    // Restore the chosen version
                    String rolledBack = versions.get(op.versionIndex);
                    doc.setCrdtJson(rolledBack);
                    documentRepository.save(doc);

                    // Broadcast DOC_LOADED to ALL clients in the session so their UI updates
                    String code = sessionManager.getSessionCode(session);
                    Operations notify = new Operations();
                    notify.type    = "DOC_LOADED";
                    notify.payload = rolledBack;
                    broadcastToAll(code, notify.toJson());
                }

                // ==============================================================
                // UNKNOWN
                // ==============================================================

                default -> {
                    sendError(session, "Unknown message type: " + op.type);
                    System.err.println("[Server] Unknown type '" + op.type + "' from " + session.getId());
                }
            }

        } catch (Exception e) {
            System.err.println("[Server] Failed to handle message: " + e.getMessage());
            e.printStackTrace();
            sendError(session, "Internal server error");
        }
    }

    // -----------------------------------------------------------------------
    // Database helpers  (Member 1)
    // -----------------------------------------------------------------------

    /**
     * Saves (or updates) the document for a session to MongoDB.
     * Before overwriting, snapshots the current state into the versions list.
     *
     * @param editorCode    The session's editor code (used as the natural key).
     * @param ownerUsername The session owner's display name.
     * @param crdtJson      The serialised CRDT state to persist.
     */
    private void saveDocument(String editorCode, String ownerUsername, String crdtJson) {
        if (documentRepository == null) return;

        SessionManager.Session s = sessionManager.getSession(editorCode);
        String viewerCode = s != null ? s.getViewerCode() : editorCode + "_V";

        Optional<DocumentEntity> existing = documentRepository.findByEditorCode(editorCode);
        DocumentEntity doc;

        if (existing.isPresent()) {
            doc = existing.get();
            doc.snapshotCurrentVersion();    // archive the old state BEFORE overwriting
        } else {
            doc = new DocumentEntity(ownerUsername, editorCode, viewerCode, null);
        }

        doc.setCrdtJson(crdtJson);
        documentRepository.save(doc);
        System.out.println("[Server] Document saved for session " + editorCode);
    }

    // -----------------------------------------------------------------------
    // Relay helpers
    // -----------------------------------------------------------------------

    /**
     * Logs the operation, pushes to undo stack, buffers for disconnected
     * clients, relays to all other clients in the session, and triggers
     * auto-save every 10 operations.
     */
    private void relayAndLog(WebSocketSession session, String rawJson,
                             Operations op, boolean trackUndo) {
        String editorCode = sessionManager.getSessionCode(session);
        if (editorCode == null) { sendError(session, "Not in a session"); return; }

        SessionManager.Session s = sessionManager.getSession(editorCode);
        s.logOperation(rawJson);

        // Undo/Redo tracking (Member 2)
        if (trackUndo && op.username != null) {
            undoRedoManager.push(op.username, op);
        }

        // Buffer for disconnected clients (Member 2 Bonus)
        sessionManager.bufferMissedOp(editorCode, rawJson);

        // Relay to peers
        for (WebSocketSession other : sessionManager.getOtherClients(session)) {
            sendTo(other, rawJson);
        }

        // Auto-save every 10 operations (Member 1)
        if (s.shouldAutoSave() && documentRepository != null && op.payload != null) {
            saveDocument(editorCode, op.ownerUsername, op.payload);
        }
    }

    /** Broadcast a message to every client (including the sender) in the session. */
    private void broadcastToAll(String editorCode, String json) {
        if (editorCode == null) return;
        for (WebSocketSession client : sessionManager.getAllClientsInSession(editorCode)) {
            sendTo(client, json);
        }
    }

    // -----------------------------------------------------------------------
    // Active users broadcast
    // -----------------------------------------------------------------------

    private void broadcastActiveUsers(String editorCode) {
        List<WebSocketSession> clients = sessionManager.getAllClientsInSession(editorCode);
        List<String>           names   = sessionManager.getActiveUserNames(editorCode);

        Operations op = new Operations();
        op.type    = "ACTIVE_USERS";
        op.payload = new Gson().toJson(names);
        String json = op.toJson();

        for (WebSocketSession client : clients) sendTo(client, json);
        System.out.println("[Server] Active users in " + editorCode + ": " + names);
    }

    // -----------------------------------------------------------------------
    // Low-level send utilities
    // -----------------------------------------------------------------------

    public void sendTo(WebSocketSession session, String json) {
        try {
            synchronized (session) {
                if (session.isOpen()) session.sendMessage(new TextMessage(json));
            }
        } catch (Exception e) {
            System.err.println("[Server] Send failed to " + session.getId() + ": " + e.getMessage());
        }
    }

    private void sendError(WebSocketSession session, String message) {
        Operations err = new Operations();
        err.type    = "ERROR";
        err.payload = message;
        sendTo(session, err.toJson());
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }
}
