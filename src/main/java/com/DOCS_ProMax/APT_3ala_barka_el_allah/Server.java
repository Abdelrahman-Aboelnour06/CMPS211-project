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
 * Full Phase 3 implementation including block operations networking,
 * session-level undo/redo (other users' changes), and all spec requirements.
 */
@Component
public class Server extends TextWebSocketHandler {

    private final SessionManager   sessionManager  = new SessionManager();
    private final UndoRedoManager  undoRedoManager = new UndoRedoManager();

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
        sessionManager.removeClient(session);
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
                    resp.sessionCode = s.getEditorCode();
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

                        for (String pastOp : s.getOperationLog()) sendTo(session, pastOp);

                        broadcastActiveUsers(s.getEditorCode());
                        System.out.println("[Server] " + op.username
                                + " joined " + s.getEditorCode()
                                + " as " + resp.role);
                    }
                }

                // ==============================================================
                // RECONNECTION
                // ==============================================================

                case "RECONNECT" -> {
                    if (isBlank(op.username)) { sendError(session, "Username is required"); return; }

                    SessionManager.DisconnectedClient dc =
                            sessionManager.getDisconnectedClient(op.username.trim());

                    if (dc == null) {
                        sendError(session, "No reconnection record found for " + op.username);
                        return;
                    }

                    SessionManager.Session s =
                            sessionManager.joinSession(dc.sessionEditorCode, session, op.username.trim());

                    if (s == null) {
                        sendError(session, "Original session no longer exists");
                        sessionManager.clearDisconnectedClient(op.username.trim());
                        return;
                    }

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
                // CHARACTER CRDT OPERATIONS
                // ==============================================================

                case "INSERT_CHAR", "DELETE_CHAR" -> {
                    if (!sessionManager.isEditor(session)) {
                        sendError(session, "Viewers cannot edit the document"); return;
                    }
                    // Auto-delete any comment attached to a deleted char
                    if ("DELETE_CHAR".equals(op.type) && documentRepository != null) {
                        String code = sessionManager.getSessionCode(session);
                        Optional<DocumentEntity> found = documentRepository.findByEditorCode(code);
                        found.ifPresent(d -> {
                            boolean changed = d.getComments()
                                    .removeIf(c -> c.startCharUser == op.charUser
                                            && c.startCharClock == op.charClock);
                            if (changed) {
                                documentRepository.save(d);
                                Operations notify = new Operations();
                                notify.type    = "COMMENT_DELETED";
                                notify.payload = "auto";
                                broadcastToAll(code, notify.toJson());
                            }
                        });
                    }
                    relayAndLog(session, message.getPayload(), op, true);
                }

                case "UNDELETE_CHAR" -> {
                    if (!sessionManager.isEditor(session)) {
                        sendError(session, "Viewers cannot edit the document"); return;
                    }
                    relayAndLog(session, message.getPayload(), op, false);
                }

                case "FORMAT_CHAR" -> {
                    if (!sessionManager.isEditor(session)) {
                        sendError(session, "Viewers cannot format the document"); return;
                    }
                    relayAndLog(session, message.getPayload(), op, true);
                }

                // ==============================================================
                // BLOCK CRDT OPERATIONS  ← NEW
                // ==============================================================

                case "INSERT_BLOCK", "DELETE_BLOCK", "SPLIT_BLOCK", "MERGE_BLOCK",
                     "MOVE_BLOCK", "COPY_BLOCK" -> {
                    if (!sessionManager.isEditor(session)) {
                        sendError(session, "Viewers cannot modify blocks"); return;
                    }
                    relayAndLog(session, message.getPayload(), op, true);
                    System.out.println("[Server] Block op " + op.type
                            + " relayed in session " + sessionManager.getSessionCode(session));
                }

                // ==============================================================
                // UNDO / REDO  (own changes AND other users' changes)
                // ==============================================================

                case "UNDO" -> {
                    if (!sessionManager.isEditor(session)) {
                        sendError(session, "Viewers cannot undo"); return;
                    }
                    String editorCode = sessionManager.getSessionCode(session);

                    List<Operations> inverses = undoRedoManager.undoGroup(op.username);
                    if (inverses == null || inverses.isEmpty()) {
                        inverses = undoRedoManager.undoFromSessionGroup(editorCode, op.username);
                    }
                    if (inverses == null || inverses.isEmpty()) {
                        sendError(session, "Nothing to undo"); return;
                    }
                    for (Operations inv : inverses) {
                        inv.sessionCode = editorCode;
                        broadcastToAll(editorCode, inv.toJson());
                    }
                }

                case "REDO" -> {
                    if (!sessionManager.isEditor(session)) {
                        sendError(session, "Viewers cannot redo"); return;
                    }
                    String editorCode = sessionManager.getSessionCode(session);

                    List<Operations> reapplied = undoRedoManager.redoGroup(op.username);
                    if (reapplied == null || reapplied.isEmpty()) {
                        reapplied = undoRedoManager.redoFromSessionGroup(editorCode, op.username);
                    }
                    if (reapplied == null ||reapplied.isEmpty()) {
                        sendError(session, "Nothing to redo"); return;
                    }
                    for (Operations r : reapplied) {
                        r.sessionCode = editorCode;
                        broadcastToAll(editorCode, r.toJson());
                    }
                }


                // ==============================================================
                // CURSOR
                // ==============================================================

                case "CURSOR" -> {
                    String code = sessionManager.getSessionCode(session);
                    List<WebSocketSession> others = sessionManager.getOtherClients(session);
                    String payload = message.getPayload();
                    sessionManager.bufferMissedOp(code, payload);
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
                // DATABASE PERSISTENCE
                // ==============================================================

                case "SAVE_DOC" -> {
                    if (documentRepository == null) { sendError(session, "Database not configured"); return; }
                    if (!sessionManager.isEditor(session)) { sendError(session, "Only editors can save"); return; }

                    String code = sessionManager.getSessionCode(session);
                    if (code == null) { sendError(session, "Not in a session"); return; }

                    String saveCode = (!isBlank(op.originalEditorCode))
                            ? op.originalEditorCode : code;

                    String docName = (!isBlank(op.documentName)) ? op.documentName : "Untitled";
                    saveDocument(saveCode, op.ownerUsername, op.payload, docName);

                    Operations resp = new Operations();
                    resp.type    = "DOC_SAVED";
                    resp.payload = "Document saved successfully";
                    sendTo(session, resp.toJson());
                }

                case "LOAD_DOC" -> {
                    if (documentRepository == null) { sendError(session, "Database not configured"); return; }
                    if (isBlank(op.sessionCode))  { sendError(session, "Session code required"); return; }

                    Optional<DocumentEntity> found =
                            documentRepository.findByEditorCodeOrViewerCode(
                                    op.sessionCode.trim().toUpperCase());

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

                    StringBuilder sb = new StringBuilder("[");
                    for (int i = 0; i < docs.size(); i++) {
                        DocumentEntity d = docs.get(i);
                        if (i > 0) sb.append(",");
                        sb.append("{")
                                .append("\"id\":\"").append(d.getId()).append("\",")
                                .append("\"documentName\":\"")
                                .append(d.getDocumentName() != null ? d.getDocumentName() : "Untitled")
                                .append("\",")
                                .append("\"editorCode\":\"").append(d.getEditorCode()).append("\",")
                                .append("\"viewerCode\":\"").append(d.getViewerCode()).append("\"")
                                .append("}");
                    }
                    sb.append("]");

                    Operations resp = new Operations();
                    resp.type    = "DOCS_LIST";
                    resp.payload = sb.toString();
                    sendTo(session, resp.toJson());
                }

                case "DELETE_DOC" -> {
                    if (documentRepository == null) { sendError(session, "Database not configured"); return; }
                    if (isBlank(op.sessionCode)) { sendError(session, "Session code required"); return; }
                    if (isBlank(op.username))    { sendError(session, "Username required"); return; }

                    Optional<DocumentEntity> found =
                            documentRepository.findByEditorCode(op.sessionCode.trim().toUpperCase());

                    if (found.isEmpty()) { sendError(session, "Document not found"); return; }
                    if (!op.username.trim().equals(found.get().getOwnerUsername())) {
                        sendError(session, "Only the document owner can delete"); return;
                    }

                    documentRepository.delete(found.get());

                    Operations resp = new Operations();
                    resp.type    = "DOC_DELETED";
                    resp.payload = "Document deleted";
                    sendTo(session, resp.toJson());
                }

                case "OPEN_DOC" -> {
                    if (documentRepository == null) { sendError(session, "Database not configured"); return; }
                    if (isBlank(op.sessionCode))    { sendError(session, "Session code required"); return; }
                    if (isBlank(op.username))        { sendError(session, "Username required"); return; }

                    Optional<DocumentEntity> found =
                            documentRepository.findByEditorCode(op.sessionCode.trim().toUpperCase());
                    if (found.isEmpty()) { sendError(session, "Document not found"); return; }

                    DocumentEntity doc = found.get();
                    String eCode = doc.getEditorCode();
                    String vCode = doc.getViewerCode();

                    SessionManager.Session s = sessionManager.getSession(eCode);
                    if (s == null) {
                        s = sessionManager.restoreSession(eCode, vCode, op.username.trim(), session);
                    } else {
                        sessionManager.joinSession(eCode, session, op.username.trim());
                    }

                    Operations resp = new Operations();
                    resp.type        = "SESSION_CREATED";
                    resp.sessionCode = eCode;
                    resp.editorCode  = eCode;
                    resp.viewerCode  = vCode;
                    resp.payload     = doc.getCrdtJson();
                    sendTo(session, resp.toJson());
                    broadcastActiveUsers(eCode);

                    System.out.println("[Server] Document reopened: " + eCode + " by " + op.username);
                }

                case "RENAME_DOC" -> {
                    if (documentRepository == null) { sendError(session, "Database not configured"); return; }
                    if (isBlank(op.sessionCode) || isBlank(op.payload)) {
                        sendError(session, "Missing fields"); return;
                    }
                    if (isBlank(op.username)) { sendError(session, "Username required"); return; }

                    Optional<DocumentEntity> found =
                            documentRepository.findByEditorCode(op.sessionCode.trim().toUpperCase());
                    if (found.isEmpty()) { sendError(session, "Document not found"); return; }
                    if (!op.username.trim().equals(found.get().getOwnerUsername())) {
                        sendError(session, "Only the owner can rename"); return;
                    }

                    found.get().setDocumentName(op.payload.trim());
                    documentRepository.save(found.get());

                    Operations resp = new Operations();
                    resp.type    = "DOC_RENAMED";
                    resp.payload = op.payload.trim();
                    sendTo(session, resp.toJson());
                    System.out.println("[Server] Document renamed to: " + op.payload.trim());
                }

                // ==============================================================
                // VERSION HISTORY
                // ==============================================================


                case "GET_VERSIONS" -> {
                    if (documentRepository == null) {
                        sendError(session, "Database not configured"); return;
                    }
                    if (isBlank(op.sessionCode)) {
                        sendError(session, "Session code required"); return;
                    }

                    Optional<DocumentEntity> found =
                            documentRepository.findByEditorCodeOrViewerCode(
                                    op.sessionCode.trim().toUpperCase());

                    if (found.isEmpty()) {
                        sendError(session, "Document not found — save the document first");
                        return;
                    }

                    DocumentEntity doc = found.get();

                    // Build a unified list: [snapshot0, snapshot1, ..., liveContent]
                    // We send a wrapper that carries BOTH the display list AND
                    // a parallel index list so ROLLBACK_VERSION knows the real index.
                    // Simplest approach: just send all snapshots + live as one array,
                    // and ROLLBACK_VERSION will receive the actual JSON content directly
                    // (not an index) so there is no index mismatch.
                    java.util.List<String> allVersions =
                            new java.util.ArrayList<>(doc.getVersions());
                    String live = doc.getCrdtJson();
                    if (live != null && !live.isBlank()) {
                        if (allVersions.isEmpty() ||
                                !allVersions.get(allVersions.size() - 1).equals(live)) {
                            allVersions.add(live);
                        }
                    }

                    Operations resp = new Operations();
                    resp.type    = "VERSIONS_LIST";
                    resp.payload = gson.toJson(allVersions);
                    sendTo(session, resp.toJson());
                }
                case "ROLLBACK_VERSION" -> {
                    if (documentRepository == null) {
                        sendError(session, "Database not configured"); return;
                    }
                    if (!sessionManager.isEditor(session)) {
                        sendError(session, "Only editors can rollback"); return;
                    }
                    if (isBlank(op.sessionCode)) {
                        sendError(session, "Session code required"); return;
                    }

                    Optional<DocumentEntity> found =
                            documentRepository.findByEditorCode(
                                    op.sessionCode.trim().toUpperCase());

                    if (found.isEmpty()) {
                        sendError(session, "Document not found"); return;
                    }

                    DocumentEntity doc = found.get();

                    // Build the same unified list as GET_VERSIONS so indices match exactly
                    java.util.List<String> allVersions =
                            new java.util.ArrayList<>(doc.getVersions());
                    String live = doc.getCrdtJson();
                    if (live != null && !live.isBlank()) {
                        if (allVersions.isEmpty() ||
                                !allVersions.get(allVersions.size() - 1).equals(live)) {
                            allVersions.add(live);
                        }
                    }

                    if (op.versionIndex < 0 || op.versionIndex >= allVersions.size()) {
                        sendError(session, "Invalid version index: " + op.versionIndex
                                + " (total=" + allVersions.size() + ")");
                        return;
                    }

                    String rolledBack = allVersions.get(op.versionIndex);

                    // Snapshot current content before overwriting
                    doc.snapshotCurrentVersion();
                    doc.setCrdtJson(rolledBack);
                    documentRepository.save(doc);

                    String code = sessionManager.getSessionCode(session);
                    Operations notify = new Operations();
                    notify.type    = "DOC_LOADED";
                    notify.payload = rolledBack;
                    broadcastToAll(code, notify.toJson());

                    System.out.println("[Server] Rolled back to version "
                            + op.versionIndex + " in session " + code);
                }
                // ==============================================================
                // COMMENTS
                // ==============================================================

                case "ADD_COMMENT" -> {
                    if (!sessionManager.isEditor(session)) {
                        sendError(session, "Viewers cannot comment"); return;
                    }
                    String code = sessionManager.getSessionCode(session);
                    if (code == null) { sendError(session, "Not in a session"); return; }

                    Comment c = new Comment(
                            java.util.UUID.randomUUID().toString(),
                            op.username, op.commentText,
                            op.charUser, op.charClock,
                            op.endCharUser, op.endCharClock
                    );

                    // Always store in session memory (works without a prior save)
                    SessionManager.Session s = sessionManager.getSession(code);
                    if (s != null) s.addSessionComment(c);

                    // Also persist to DB if available and document exists
                    if (documentRepository != null) {
                        documentRepository.findByEditorCode(code).ifPresent(doc -> {
                            doc.getComments().add(c);
                            documentRepository.save(doc);
                        });
                    }

                    Operations resp = new Operations();
                    resp.type    = "COMMENT_ADDED";
                    resp.payload = gson.toJson(c);
                    broadcastToAll(code, resp.toJson());
                }

                case "DELETE_COMMENT" -> {
                    String code = sessionManager.getSessionCode(session);
                    if (code == null) { sendError(session, "Not in a session"); return; }

                    // Remove from session memory
                    SessionManager.Session s = sessionManager.getSession(code);
                    if (s != null) s.removeSessionComment(op.commentId);

                    // Also remove from DB if available
                    if (documentRepository != null) {
                        documentRepository.findByEditorCode(code).ifPresent(doc -> {
                            doc.getComments().removeIf(c -> c.id.equals(op.commentId));
                            documentRepository.save(doc);
                        });
                    }

                    Operations resp = new Operations();
                    resp.type      = "COMMENT_DELETED";
                    resp.commentId = op.commentId;
                    broadcastToAll(code, resp.toJson());
                }

                case "GET_COMMENTS" -> {
                    String code = sessionManager.getSessionCode(session);
                    if (code == null) { sendError(session, "Not in a session"); return; }

                    // Prefer session-memory comments (always up to date, no save needed)
                    SessionManager.Session s = sessionManager.getSession(code);
                    java.util.List<Comment> comments = (s != null)
                            ? s.getSessionComments()
                            : new java.util.ArrayList<>();

                    // If session memory is empty, fall back to DB
                    if (comments.isEmpty() && documentRepository != null) {
                        Optional<DocumentEntity> found = documentRepository.findByEditorCode(code);
                        if (found.isPresent()) comments = found.get().getComments();
                    }

                    Operations resp = new Operations();
                    resp.type    = "COMMENTS_LIST";
                    resp.payload = gson.toJson(comments);
                    sendTo(session, resp.toJson());
                }

                case "RESOLVE_COMMENT" -> {
                    String code = sessionManager.getSessionCode(session);
                    if (code == null) { sendError(session, "Not in a session"); return; }

                    // Resolve in session memory
                    SessionManager.Session s = sessionManager.getSession(code);
                    if (s != null) s.resolveSessionComment(op.commentId);

                    // Also resolve in DB if available
                    if (documentRepository != null) {
                        documentRepository.findByEditorCode(code).ifPresent(doc -> {
                            doc.getComments().stream()
                                    .filter(c -> c.id.equals(op.commentId))
                                    .findFirst()
                                    .ifPresent(c -> c.resolved = true);
                            documentRepository.save(doc);
                        });
                    }

                    Operations resp = new Operations();
                    resp.type      = "COMMENT_RESOLVED";
                    resp.commentId = op.commentId;
                    broadcastToAll(code, resp.toJson());
                }

                case "BEGIN_GROUP" -> {
                    if (!sessionManager.isEditor(session)) return;
                    String editorCode = sessionManager.getSessionCode(session);
                    if (editorCode == null) return;
                    undoRedoManager.beginGroup(op.username, editorCode);
                }

                case "END_GROUP" -> {
                    if (!sessionManager.isEditor(session)) return;
                    String editorCode = sessionManager.getSessionCode(session);
                    if (editorCode == null) return;
                    undoRedoManager.endGroup(op.username, editorCode);
                }
                // ==============================================================
                // UNKNOWN
                // ==============================================================

                default -> {
                    sendError(session, "Unknown message type: " + op.type);
                    System.err.println("[Server] Unknown type '" + op.type
                            + "' from " + session.getId());
                }
            }

        } catch (Exception e) {
            System.err.println("[Server] Failed to handle message: " + e.getMessage());
            e.printStackTrace();
            sendError(session, "Internal server error");
        }
    }

    // -----------------------------------------------------------------------
    // Database helpers
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // Database helpers
    // -----------------------------------------------------------------------
// -----------------------------------------------------------------------
    // Database helpers
    // -----------------------------------------------------------------------
    // -----------------------------------------------------------------------
    // Database helpers  (drop-in replacement for the same method in Server.java)
    // -----------------------------------------------------------------------
// ── FILE: Server.java ────────────────────────────────────────────────────
// REPLACE saveDocument() entirely

    private void saveDocument(String editorCode, String ownerUsername,
                              String crdtJson, String docName) {
        if (documentRepository == null) return;
        if (crdtJson == null || crdtJson.isBlank()) return;

        SessionManager.Session s = sessionManager.getSession(editorCode);
        String viewerCode = s != null ? s.getViewerCode() : editorCode + "_V";

        Optional<DocumentEntity> existing =
                documentRepository.findByEditorCode(editorCode);
        DocumentEntity doc;

        if (existing.isPresent()) {
            doc = existing.get();

            // Content unchanged — only update name if needed
            if (crdtJson.equals(doc.getCrdtJson())) {
                if (!isBlank(docName) && !docName.equals(doc.getDocumentName())) {
                    doc.setDocumentName(docName);
                    documentRepository.save(doc);
                }
                return;
            }

            // Snapshot the OLD content before overwriting
            doc.snapshotCurrentVersion();

        } else {
            // First save — create entity, no previous content to snapshot
            String resolvedOwner = !isBlank(ownerUsername)
                    ? ownerUsername : "unknown";
            doc = new DocumentEntity(resolvedOwner, editorCode, viewerCode, null);
        }

        doc.setCrdtJson(crdtJson);
        if (!isBlank(docName)) doc.setDocumentName(docName);

        documentRepository.save(doc);
        System.out.println("[Server] Saved session=" + editorCode
                + " owner=" + doc.getOwnerUsername()
                + " versions=" + doc.getVersions().size());
    }
    // -----------------------------------------------------------------------
    // Relay helpers
    // -----------------------------------------------------------------------

    /**
     * Logs the operation, pushes to undo stacks, buffers for disconnected
     * clients, relays to all other clients in the session.
     */
    private void relayAndLog(WebSocketSession session, String rawJson,
                             Operations op, boolean trackUndo) {
        String editorCode = sessionManager.getSessionCode(session);
        if (editorCode == null) { sendError(session, "Not in a session"); return; }

        SessionManager.Session s = sessionManager.getSession(editorCode);
        s.logOperation(rawJson);

        // Push to per-user AND session-level undo stacks
        if (trackUndo && op.username != null) {
            undoRedoManager.push(op.username, op);
            undoRedoManager.pushToSession(editorCode, op);
        }

        // Buffer for disconnected clients
        sessionManager.bufferMissedOp(editorCode, rawJson);

        // Relay to peers
        for (WebSocketSession other : sessionManager.getOtherClients(session)) {
            sendTo(other, rawJson);
        }

        // Auto-save every 10 operations
        if (s.shouldAutoSave() && documentRepository != null && op.payload != null) {
            saveDocument(editorCode, op.ownerUsername, op.payload,
                    op.documentName != null ? op.documentName : "Untitled");
        }
    }

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
            System.err.println("[Server] Send failed to "
                    + session.getId() + ": " + e.getMessage());
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