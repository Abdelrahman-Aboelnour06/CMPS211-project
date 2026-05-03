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

                    // Try own stack first, then session stack
                    Operations inverse = undoRedoManager.undo(op.username);
                    if (inverse == null) {
                        inverse = undoRedoManager.undoFromSession(editorCode, op.username);
                    }
                    if (inverse == null) { sendError(session, "Nothing to undo"); return; }

                    inverse.sessionCode = editorCode;
                    broadcastToAll(editorCode, inverse.toJson());
                }

                case "REDO" -> {
                    if (!sessionManager.isEditor(session)) {
                        sendError(session, "Viewers cannot redo"); return;
                    }
                    String editorCode = sessionManager.getSessionCode(session);

                    Operations reapplied = undoRedoManager.redo(op.username);
                    if (reapplied == null) {
                        reapplied = undoRedoManager.redoFromSession(editorCode, op.username);
                    }
                    if (reapplied == null) { sendError(session, "Nothing to redo"); return; }

                    reapplied.sessionCode = editorCode;
                    broadcastToAll(editorCode, reapplied.toJson());
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
                    if (documentRepository == null) { sendError(session, "Database not configured"); return; }
                    if (isBlank(op.sessionCode)) { sendError(session, "Session code required"); return; }

                    Optional<DocumentEntity> found =
                            documentRepository.findByEditorCodeOrViewerCode(
                                    op.sessionCode.trim().toUpperCase());

                    if (found.isEmpty()) { sendError(session, "Document not found"); return; }

                    Operations resp = new Operations();
                    resp.type    = "VERSIONS_LIST";
                    resp.payload = gson.toJson(found.get().getVersions());
                    sendTo(session, resp.toJson());
                }

                case "ROLLBACK_VERSION" -> {
                    if (documentRepository == null) { sendError(session, "Database not configured"); return; }
                    if (!sessionManager.isEditor(session)) {
                        sendError(session, "Only editors can rollback"); return;
                    }
                    if (isBlank(op.sessionCode)) { sendError(session, "Session code required"); return; }

                    Optional<DocumentEntity> found =
                            documentRepository.findByEditorCode(op.sessionCode.trim().toUpperCase());

                    if (found.isEmpty()) { sendError(session, "Document not found"); return; }

                    DocumentEntity doc = found.get();
                    List<String> versions = doc.getVersions();

                    if (op.versionIndex < 0 || op.versionIndex >= versions.size()) {
                        sendError(session, "Invalid version index: " + op.versionIndex); return;
                    }

                    String rolledBack = versions.get(op.versionIndex);
                    doc.setCrdtJson(rolledBack);
                    documentRepository.save(doc);

                    String code = sessionManager.getSessionCode(session);
                    Operations notify = new Operations();
                    notify.type    = "DOC_LOADED";
                    notify.payload = rolledBack;
                    broadcastToAll(code, notify.toJson());
                }

                // ==============================================================
                // COMMENTS
                // ==============================================================

                case "ADD_COMMENT" -> {
                    if (documentRepository == null) { sendError(session, "Database not configured"); return; }
                    if (!sessionManager.isEditor(session)) {
                        sendError(session, "Viewers cannot comment"); return;
                    }
                    String code = sessionManager.getSessionCode(session);
                    if (code == null) { sendError(session, "Not in a session"); return; }

                    Optional<DocumentEntity> found = documentRepository.findByEditorCode(code);
                    if (found.isEmpty()) { sendError(session, "Document not found — save first"); return; }

                    Comment c = new Comment(
                            java.util.UUID.randomUUID().toString(),
                            op.username, op.commentText,
                            op.charUser, op.charClock,
                            op.endCharUser, op.endCharClock
                    );
                    found.get().getComments().add(c);
                    documentRepository.save(found.get());

                    Operations resp = new Operations();
                    resp.type    = "COMMENT_ADDED";
                    resp.payload = gson.toJson(c);
                    broadcastToAll(code, resp.toJson());
                }

                case "DELETE_COMMENT" -> {
                    if (documentRepository == null) { sendError(session, "Database not configured"); return; }
                    String code = sessionManager.getSessionCode(session);
                    if (code == null) { sendError(session, "Not in a session"); return; }

                    Optional<DocumentEntity> found = documentRepository.findByEditorCode(code);
                    if (found.isEmpty()) { sendError(session, "Document not found"); return; }

                    found.get().getComments().removeIf(c -> c.id.equals(op.commentId));
                    documentRepository.save(found.get());

                    Operations resp = new Operations();
                    resp.type      = "COMMENT_DELETED";
                    resp.commentId = op.commentId;
                    broadcastToAll(code, resp.toJson());
                }

                case "GET_COMMENTS" -> {
                    if (documentRepository == null) { sendError(session, "Database not configured"); return; }
                    String code = sessionManager.getSessionCode(session);
                    if (code == null) { sendError(session, "Not in a session"); return; }

                    Optional<DocumentEntity> found = documentRepository.findByEditorCode(code);

                    Operations resp = new Operations();
                    resp.type    = "COMMENTS_LIST";
                    resp.payload = found.isPresent() ? gson.toJson(found.get().getComments()) : "[]";
                    sendTo(session, resp.toJson());
                }

                case "RESOLVE_COMMENT" -> {
                    if (documentRepository == null) { sendError(session, "Database not configured"); return; }
                    String code = sessionManager.getSessionCode(session);
                    if (code == null) { sendError(session, "Not in a session"); return; }

                    Optional<DocumentEntity> found = documentRepository.findByEditorCode(code);
                    if (found.isEmpty()) { sendError(session, "Document not found"); return; }

                    found.get().getComments().stream()
                            .filter(c -> c.id.equals(op.commentId))
                            .findFirst()
                            .ifPresent(c -> c.resolved = true);
                    documentRepository.save(found.get());

                    Operations resp = new Operations();
                    resp.type      = "COMMENT_RESOLVED";
                    resp.commentId = op.commentId;
                    broadcastToAll(code, resp.toJson());
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
    private void saveDocument(String editorCode, String ownerUsername,
                              String crdtJson, String docName) {
        if (documentRepository == null) return;

        SessionManager.Session s = sessionManager.getSession(editorCode);
        String viewerCode = s != null ? s.getViewerCode() : editorCode + "_V";

        Optional<DocumentEntity> existing = documentRepository.findByEditorCode(editorCode);
        DocumentEntity doc;

        if (existing.isPresent()) {
            doc = existing.get();

            // Nothing changed — just update the name if necessary
            if (crdtJson != null && crdtJson.equals(doc.getCrdtJson())) {
                if (!isBlank(docName) && !docName.equals(doc.getDocumentName())) {
                    doc.setDocumentName(docName);
                    documentRepository.save(doc);
                }
                return;
            }
        } else {
            doc = new DocumentEntity(ownerUsername, editorCode, viewerCode, null);
        }

        // ── STEP 1: update the live content ─────────────────────────────────
        doc.setCrdtJson(crdtJson);
        if (!isBlank(docName)) doc.setDocumentName(docName);

        // ── STEP 2: record this content as a version snapshot ────────────────
        //   snapshotCurrentVersion() is now null-safe and deduplicates.
        //   Calling it after setCrdtJson means every distinct save creates
        //   exactly one retrievable snapshot entry.
        doc.snapshotCurrentVersion();

        documentRepository.save(doc);
        System.out.println("[Server] Document saved for session " + editorCode
                + " — versions: " + doc.getVersions().size());
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