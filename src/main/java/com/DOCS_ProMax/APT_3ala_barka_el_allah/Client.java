package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket client that connects to the collaboration server.
 *
 * KEY FIX in this revision
 * ────────────────────────
 * INSERT_BLOCK remote handler previously called insertTopLevelBlock() which
 * generated a BRAND NEW BlockID, so the remote peer's block had a different
 * identity from the sender's block.  That caused:
 *   - The remote block to be silently duplicated or placed in the wrong slot.
 *   - INSERT_CHAR ops for that block to target an ID that didn't exist on the
 *     remote peer, so all subsequent characters were lost.
 *
 * The handler now calls insertBlockWithID(blockID, parentID, content) which
 * reuses the exact same BlockID that was sent over the wire, making both
 * peers converge on an identical block tree.
 *
 * SPLIT_BLOCK remote handler had the same problem: it called
 * splitBlockAtCursor() which internally creates a sibling with a fresh ID.
 * That new ID is different on every peer, so the two halves diverge.
 * The handler now calls insertBlockWithID for the NEW (second) half using
 * the IDs carried in the message (targetBlockUser / targetBlockClock), and
 * moveTextFromIndex to migrate characters from the source block.
 */
public class Client extends WebSocketClient {

    private final BlockCRDT localDoc;
    private final Clock     sharedClock;
    private BlockID activeBlockID;

    private String sessionCode;
    private String editorCode;
    private String viewerCode;
    private String username;
    private String role;
    private String originalEditorCode;

    private MessageListener messageListener;

    // Reconnection support
    private final String serverUri;
    private final ScheduledExecutorService reconnectScheduler =
            Executors.newSingleThreadScheduledExecutor();
    private static final int RECONNECT_INTERVAL_SECONDS = 30;

    public interface MessageListener {
        void onMessage(Operations op);
    }

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------
    public Client(String serverUri, BlockCRDT localDoc, Clock sharedClock, BlockID activeBlockID)
            throws URISyntaxException {
        super(new URI(serverUri));
        this.serverUri     = serverUri;
        this.localDoc      = localDoc;
        this.sharedClock   = sharedClock;
        this.activeBlockID = activeBlockID;
    }

    // -----------------------------------------------------------------------
    // WebSocketClient callbacks
    // -----------------------------------------------------------------------

    @Override
    public void onOpen(ServerHandshake handshake) {
        System.out.println("[Client] Connected to server (HTTP status "
                + handshake.getHttpStatus() + ")");
    }

    @Override
    public void onMessage(String rawJson) {
        System.out.println("[Client] RAW MESSAGE: " + rawJson);
        Operations op = Operations.fromJson(rawJson);
        if (op == null) return;

        switch (op.type) {

            case "SESSION_CREATED" -> {
                this.sessionCode = op.sessionCode;
                this.editorCode  = op.editorCode;
                this.viewerCode  = op.viewerCode;
                this.role        = "editor";
                System.out.println("[Client] Session created – editor=" + editorCode
                        + " viewer=" + viewerCode);
            }

            case "SESSION_JOINED" -> {
                this.sessionCode = op.sessionCode;
                this.editorCode  = op.editorCode;
                this.viewerCode  = op.viewerCode;
                this.role        = op.role != null ? op.role : "editor";
                System.out.println("[Client] Joined session " + sessionCode
                        + " as " + this.role);
            }

            case "RECONNECTED" -> {
                this.sessionCode = op.sessionCode;
                this.role        = op.role;
                System.out.println("[Client] Reconnected to " + sessionCode);
            }

            case "ACTIVE_USERS" ->
                    System.out.println("[Client] Active users: " + op.payload);

            // ------------------------------------------------------------------
            // Character operations
            // ------------------------------------------------------------------

            case "INSERT_CHAR" -> {
                CharID incomingID = new CharID(op.charUser, op.charClock);
                CharID parentID   = new CharID(op.parentUser, op.parentClock);
                sharedClock.advanceTo(op.charClock);

                // Search ALL blocks to find the one whose CRDT contains the parent node.
                boolean inserted = false;
                for (BlockNode block : localDoc.getOrderedNodes()) {
                    if (block.getContent() != null
                            && block.getContent().getNode(parentID) != null) {
                        CharNode insertedNode =
                                block.getContent().RemotelyInsertion(incomingID, parentID, op.value);
                        if (insertedNode != null) {
                            insertedNode.setBold(op.isBold);
                            insertedNode.setItalic(op.isItalic);
                        }
                        inserted = true;
                        break;
                    }
                }
                // Fallback: try the root-ID sentinel (parentID == rootID of some block).
                if (!inserted) {
                    for (BlockNode block : localDoc.getOrderedNodes()) {
                        if (block.getContent() != null
                                && block.getContent().rootID.equals(parentID)) {
                            CharNode insertedNode =
                                    block.getContent().RemotelyInsertion(incomingID, parentID, op.value);
                            if (insertedNode != null) {
                                insertedNode.setBold(op.isBold);
                                insertedNode.setItalic(op.isItalic);
                            }
                            break;
                        }
                    }
                }
            }

            case "DELETE_CHAR" -> {
                CharID targetID = new CharID(op.charUser, op.charClock);
                sharedClock.advanceTo(op.charClock);

                for (BlockNode block : localDoc.getOrderedNodes()) {
                    if (block.getContent() != null) {
                        CharNode node = block.getContent().getNode(targetID);
                        if (node != null) {
                            node.SetDeleted(true);
                            break;
                        }
                    }
                }
            }

            case "UNDELETE_CHAR" -> {
                CharID targetID = new CharID(op.charUser, op.charClock);
                for (BlockNode block : localDoc.getOrderedNodes()) {
                    if (block.getContent() != null) {
                        CharNode node = block.getContent().getNode(targetID);
                        if (node != null) {
                            node.SetDeleted(false);
                            break;
                        }
                    }
                }
            }

            case "FORMAT_CHAR" -> {
                CharID targetID = new CharID(op.charUser, op.charClock);
                for (BlockNode block : localDoc.getOrderedNodes()) {
                    if (block.getContent() != null) {
                        CharNode node = block.getContent().getNode(targetID);
                        if (node != null) {
                            node.setBold(op.isBold);
                            node.setItalic(op.isItalic);
                            break;
                        }
                    }
                }
            }

            // ------------------------------------------------------------------
            // Block operations
            // ------------------------------------------------------------------

            /*
             * FIX: INSERT_BLOCK
             * ─────────────────
             * We MUST recreate the block with the EXACT same BlockID that the
             * sender used.  insertTopLevelBlock() generates a NEW id and breaks
             * convergence.  insertBlockWithID() reuses the wire id.
             *
             * parentBlockUser / parentBlockClock carry the parent's id so the
             * block lands in the right place in the tree.  For top-level blocks
             * the sender sets these to -1/-1 (ROOT_ID).
             */
            case "INSERT_BLOCK" -> {
                BlockID blockID  = new BlockID(op.blockUser, op.blockClock);
                BlockID parentID = new BlockID(op.parentBlockUser, op.parentBlockClock);
                sharedClock.advanceTo(op.blockClock);

                CharCRDT newCRDT;
                if (op.blockSnapshot != null && !op.blockSnapshot.isBlank()) {
                    // Restore from snapshot (used during import replay and undo/redo).
                    newCRDT = CrdtSerializer.fromJson(op.blockSnapshot, op.blockUser);
                } else {
                    newCRDT = new CharCRDT(op.blockUser, sharedClock);
                }

                BlockNode newBlock = localDoc.insertBlockWithID(blockID, parentID, newCRDT);
                System.out.println("[Client] Remote INSERT_BLOCK applied: " + newBlock.getId());
            }

            case "DELETE_BLOCK" -> {
                BlockID targetID = new BlockID(op.blockUser, op.blockClock);
                localDoc.deleteNode(targetID);
                System.out.println("[Client] Remote DELETE_BLOCK applied: " + targetID);
            }

            /*
             * FIX: SPLIT_BLOCK
             * ────────────────
             * The local splitBlockAtCursor() creates the second block with a
             * freshly generated ID – which is different on every peer.
             *
             * Instead we:
             *  1. Look up the source block.
             *  2. Create the NEW block with the exact ID the sender chose
             *     (carried in targetBlockUser / targetBlockClock).
             *  3. Move characters from the source block starting at splitAtIndex
             *     into the new block.
             *
             * This guarantees both peers end up with identically-IDed blocks
             * whose content is identical.
             */
            case "SPLIT_BLOCK" -> {
                BlockID sourceID = new BlockID(op.blockUser, op.blockClock);
                sharedClock.advanceTo(op.blockClock);

                BlockNode sourceBlock = localDoc.getBlock(sourceID);
                if (sourceBlock == null || sourceBlock.isDeleted()
                        || sourceBlock.getContent() == null) {
                    System.err.println("[Client] SPLIT_BLOCK: source block not found " + sourceID);
                    break;
                }

                // The sender puts the new block's ID in targetBlockUser/targetBlockClock.
                // If those fields are zero (old message format) fall back to local split.
                if (op.targetBlockUser == 0 && op.targetBlockClock == 0) {
                    // Legacy fallback – will diverge between peers but won't crash.
                    localDoc.splitBlockAtCursor(sourceID, (int) op.splitAtIndex);
                    System.out.println("[Client] SPLIT_BLOCK (legacy) at index: " + op.splitAtIndex);
                    break;
                }

                BlockID newBlockID = new BlockID(op.targetBlockUser, op.targetBlockClock);
                sharedClock.advanceTo(op.targetBlockClock);

                // Create the new (second) block with the exact ID from the wire.
                CharCRDT newCRDT = new CharCRDT(op.targetBlockUser, sharedClock);
                BlockNode newBlock = localDoc.insertBlockWithID(
                        newBlockID, sourceBlock.getParentID(), newCRDT);

                // Move characters from splitAtIndex onward into the new block.
                sourceBlock.moveTextFromIndex(newBlock, (int) op.splitAtIndex);

                System.out.println("[Client] Remote SPLIT_BLOCK applied: source=" + sourceID
                        + " new=" + newBlockID + " at index=" + op.splitAtIndex);
            }

            case "MERGE_BLOCK" -> {
                BlockID blockID = new BlockID(op.blockUser, op.blockClock);
                localDoc.mergeWithNext(blockID);
                System.out.println("[Client] Remote MERGE_BLOCK applied");
            }

            case "MOVE_BLOCK" -> {
                BlockID sourceID = new BlockID(op.blockUser, op.blockClock);
                BlockID afterID  = (op.targetBlockUser != 0 || op.targetBlockClock != 0)
                        ? new BlockID(op.targetBlockUser, op.targetBlockClock)
                        : null;
                BlockNode moved = localDoc.moveBlock(sourceID, afterID);
                if (moved != null) {
                    System.out.println("[Client] Remote MOVE_BLOCK applied: " + moved.getId());
                    if (activeBlockID != null && activeBlockID.equals(sourceID)) {
                        activeBlockID = moved.getId();
                    }
                } else {
                    System.err.println("[Client] Remote MOVE_BLOCK failed for source " + sourceID);
                }
            }

            case "COPY_BLOCK" -> {
                BlockID sourceID = new BlockID(op.blockUser, op.blockClock);
                BlockID afterID  = (op.targetBlockUser != 0 || op.targetBlockClock != 0)
                        ? new BlockID(op.targetBlockUser, op.targetBlockClock)
                        : null;
                BlockNode copied = localDoc.copyBlock(sourceID, afterID);
                if (copied != null) {
                    System.out.println("[Client] Remote COPY_BLOCK applied: " + copied.getId());
                } else {
                    System.err.println("[Client] Remote COPY_BLOCK failed for source " + sourceID);
                }
            }
// REPLACE the MOVE_BLOCK_EXEC case in Client.java onMessage()
            case "MOVE_BLOCK_EXEC" -> {
                sharedClock.advanceTo(op.blockClock);

                // 1. Soft-delete the original block if this is a move (not a paste)
                if (op.targetBlockUser != -1 || op.targetBlockClock != -1) {
                    BlockID oldBlockID = new BlockID(op.targetBlockUser, op.targetBlockClock);
                    BlockNode oldBlock = localDoc.getBlock(oldBlockID);
                    if (oldBlock != null && !oldBlock.isDeleted()) {
                        if (oldBlock.getContent() != null) {
                            for (CharNode cn : oldBlock.getContent().getOrderedNodes()) {
                                cn.SetDeleted(true);
                            }
                        }
                        oldBlock.setDeleted(true);
                    }
                }

                // 2. Rebuild content from snapshot
                BlockID newBlockID = new BlockID(op.blockUser, op.blockClock);
                CharCRDT newCRDT;
                if (op.blockSnapshot != null && !op.blockSnapshot.isBlank()) {
                    newCRDT = CrdtSerializer.fromJson(op.blockSnapshot, op.blockUser);
                } else {
                    newCRDT = new CharCRDT(op.blockUser, sharedClock);
                }

                // 3. Insert after anchor (ghost-safe, no raw index needed)
                BlockID anchorID = (op.anchorBlockUser == -1 && op.anchorBlockClock == -1)
                        ? null
                        : new BlockID(op.anchorBlockUser, op.anchorBlockClock);

                BlockNode newBlock = localDoc.insertBlockAfterAnchor(anchorID, newCRDT, newBlockID);

                if (newBlock != null) {
                    System.out.println("[Client] MOVE_BLOCK_EXEC: new=" + newBlockID
                            + " anchor=(" + op.anchorBlockUser + "," + op.anchorBlockClock + ")");
                } else {
                    System.err.println("[Client] MOVE_BLOCK_EXEC failed: " + newBlockID);
                }
            }   // ------------------------------------------------------------------
            // Document persistence responses
            // ------------------------------------------------------------------

            case "DOC_SAVED"   -> System.out.println("[Client] Document saved: " + op.payload);
            case "DOC_DELETED" -> System.out.println("[Client] Document deleted: " + op.payload);
            case "DOC_RENAMED" -> System.out.println("[Client] Document renamed to: " + op.payload);
            case "DOCS_LIST"   -> System.out.println("[Client] Documents: " + op.payload);
            case "VERSIONS_LIST" -> System.out.println("[Client] Versions: " + op.payload);

            case "DOC_LOADED" ->
                    System.out.println("[Client] DOC_LOADED received — UI should re-render");

            // ------------------------------------------------------------------
            // Cursor
            // ------------------------------------------------------------------

            case "CURSOR" ->
                    System.out.println("[Client] " + op.username
                            + " cursor at " + op.cursorIndex);

            // ------------------------------------------------------------------
            // Comments
            // ------------------------------------------------------------------

            case "COMMENT_ADDED", "COMMENT_DELETED",
                 "COMMENTS_LIST", "RESOLVE_COMMENT", "COMMENT_RESOLVED" ->
                    System.out.println("[Client] Comment op: " + op.type);

            // ------------------------------------------------------------------
            // Error
            // ------------------------------------------------------------------

            case "ERROR" ->
                    System.err.println("[Client] Server error: " + op.payload);

            default ->
                    System.out.println("[Client] Unknown message type: " + op.type);
        }

        if (messageListener != null) messageListener.onMessage(op);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println("[Client] Connection closed. Code=" + code + " Reason=" + reason);

        if (remote && username != null) {
            reconnectScheduler.scheduleWithFixedDelay(() -> {
                if (!isOpen()) {
                    System.out.println("[Client] Attempting reconnect...");
                    try {
                        reconnect();
                        if (isOpen()) {
                            sendReconnect();
                            reconnectScheduler.shutdown();
                        }
                    } catch (Exception e) {
                        System.err.println("[Client] Reconnect attempt failed: " + e.getMessage());
                    }
                }
            }, RECONNECT_INTERVAL_SECONDS, RECONNECT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Override
    public void onError(Exception ex) {
        System.err.println("[Client] Error: " + ex.getMessage());
    }

    // -----------------------------------------------------------------------
    // Session helpers
    // -----------------------------------------------------------------------

    public void createSession(String username) {
        this.username = username;
        Operations op = new Operations();
        op.type     = "CREATE_SESSION";
        op.username = username;
        send(op.toJson());
    }

    public void joinSession(String username, String code) {
        this.username    = username;
        this.sessionCode = code;
        Operations op = new Operations();
        op.type        = "JOIN_SESSION";
        op.username    = username;
        op.sessionCode = code;
        send(op.toJson());
    }

    public void sendReconnect() {
        Operations op = new Operations();
        op.type     = "RECONNECT";
        op.username = username;
        send(op.toJson());
    }

    public void requestActiveUsers() {
        Operations op  = new Operations();
        op.type        = "GET_ACTIVE_USERS";
        op.sessionCode = sessionCode;
        send(op.toJson());
    }

    // -----------------------------------------------------------------------
    // Character operation senders
    // -----------------------------------------------------------------------

    public void sendInsertChar(CharNode inserted) {
        Operations op  = new Operations();
        op.type        = "INSERT_CHAR";
        op.sessionCode = sessionCode;
        op.username    = username;
        op.charUser    = inserted.getID().getUserID();
        op.charClock   = inserted.getID().getClock();
        op.parentUser  = inserted.getParentID().getUserID();
        op.parentClock = inserted.getParentID().getClock();
        op.value       = inserted.getValue();
        op.isBold      = inserted.isBold();
        op.isItalic    = inserted.isItalic();
        send(op.toJson());
    }

    public void sendDeleteChar(CharNode deleted) {
        Operations op  = new Operations();
        op.type        = "DELETE_CHAR";
        op.sessionCode = sessionCode;
        op.username    = username;
        op.charUser    = deleted.getID().getUserID();
        op.charClock   = deleted.getID().getClock();
        send(op.toJson());
    }

    public void sendFormat(CharNode node) {
        Operations op  = new Operations();
        op.type        = "FORMAT_CHAR";
        op.sessionCode = sessionCode;
        op.username    = username;
        op.charUser    = node.getID().getUserID();
        op.charClock   = node.getID().getClock();
        op.isBold      = node.isBold();
        op.isItalic    = node.isItalic();
        send(op.toJson());
    }

    public void sendCursor(int index) {
        Operations op  = new Operations();
        op.type        = "CURSOR";
        op.sessionCode = sessionCode;
        op.username    = username;
        op.cursorIndex = index;
        send(op.toJson());
    }

    // -----------------------------------------------------------------------
    // Block operation senders
    // -----------------------------------------------------------------------

    public void sendInsertBlock(BlockNode block) {
        if (!isOpen()) return;
        Operations op = new Operations();
        op.type             = "INSERT_BLOCK";
        op.sessionCode      = sessionCode;
        op.username         = username;
        op.blockUser        = block.getId().getUserID();
        op.blockClock       = block.getId().getClock();
        op.parentBlockUser  = block.getParentID() != null ? block.getParentID().getUserID()  : -1;
        op.parentBlockClock = block.getParentID() != null ? block.getParentID().getClock()   : -1;
        if (block.getContent() != null) {
            op.blockSnapshot = CrdtSerializer.toJson(block.getContent());
        }
        send(op.toJson());
    }

    public void sendDeleteBlock(BlockID blockID) {
        BlockNode block  = localDoc.getBlock(blockID);
        String snapshot  = (block != null && block.getContent() != null)
                ? CrdtSerializer.toJson(block.getContent()) : null;
        Operations op    = new Operations();
        op.type          = "DELETE_BLOCK";
        op.sessionCode   = sessionCode;
        op.username      = username;
        op.blockUser     = blockID.getUserID();
        op.blockClock    = blockID.getClock();
        op.blockSnapshot = snapshot;
        send(op.toJson());
    }

    /**
     * FIX: sendSplitBlock now also sends the NEW block's ID
     * (targetBlockUser / targetBlockClock) so every remote peer can recreate
     * the exact same block instead of generating a fresh divergent ID.
     *
     * @param blockID      the block being split (source / first half)
     * @param newBlock     the newly created second block (returned by splitBlockAtCursor)
     * @param cursorIndex  the local index at which the split happened
     */
    public void sendSplitBlock(BlockID blockID, BlockNode newBlock, int cursorIndex) {
        if (!isOpen()) return;
        Operations op           = new Operations();
        op.type                 = "SPLIT_BLOCK";
        op.sessionCode          = sessionCode;
        op.username             = username;
        op.blockUser            = blockID.getUserID();
        op.blockClock           = blockID.getClock();
        op.splitAtIndex         = cursorIndex;
        // Carry the new block's ID so peers can reuse it.
        op.targetBlockUser      = newBlock.getId().getUserID();
        op.targetBlockClock     = newBlock.getId().getClock();
        send(op.toJson());
    }

    /** Legacy overload kept for backward-compat; use the 3-arg version instead. */
    public void sendSplitBlock(BlockID blockID, int cursorIndex) {
        if (!isOpen()) return;
        Operations op      = new Operations();
        op.type            = "SPLIT_BLOCK";
        op.sessionCode     = sessionCode;
        op.username        = username;
        op.blockUser       = blockID.getUserID();
        op.blockClock      = blockID.getClock();
        op.splitAtIndex    = cursorIndex;
        // targetBlockUser/targetBlockClock left as 0 – remote will use legacy fallback.
        send(op.toJson());
    }

    public void sendMergeBlock(BlockID blockID) {
        if (!isOpen()) return;
        Operations op  = new Operations();
        op.type        = "MERGE_BLOCK";
        op.sessionCode = sessionCode;
        op.username    = username;
        op.blockUser   = blockID.getUserID();
        op.blockClock  = blockID.getClock();
        send(op.toJson());
    }

    public void sendMoveBlock(BlockID blockID, BlockID afterBlockID) {
        if (!isOpen()) return;
        Operations op       = new Operations();
        op.type             = "MOVE_BLOCK";
        op.sessionCode      = sessionCode;
        op.username         = username;
        op.blockUser        = blockID.getUserID();
        op.blockClock       = blockID.getClock();
        op.targetBlockUser  = afterBlockID != null ? afterBlockID.getUserID() : 0;
        op.targetBlockClock = afterBlockID != null ? afterBlockID.getClock()  : 0;
        send(op.toJson());
    }

    public void sendCopyBlock(BlockID sourceBlockID, BlockID afterBlockID) {
        if (!isOpen()) return;
        Operations op       = new Operations();
        op.type             = "COPY_BLOCK";
        op.sessionCode      = sessionCode;
        op.username         = username;
        op.blockUser        = sourceBlockID.getUserID();
        op.blockClock       = sourceBlockID.getClock();
        op.targetBlockUser  = afterBlockID != null ? afterBlockID.getUserID() : 0;
        op.targetBlockClock = afterBlockID != null ? afterBlockID.getClock()  : 0;
        send(op.toJson());
    }

    // -----------------------------------------------------------------------
    // Undo / Redo
    // -----------------------------------------------------------------------

    public void sendUndo() {
        Operations op  = new Operations();
        op.type        = "UNDO";
        op.sessionCode = sessionCode;
        op.username    = username;
        send(op.toJson());
    }

    public void sendRedo() {
        Operations op  = new Operations();
        op.type        = "REDO";
        op.sessionCode = sessionCode;
        op.username    = username;
        send(op.toJson());
    }

    // -----------------------------------------------------------------------
    // Document persistence senders
    // -----------------------------------------------------------------------

    public void sendSaveDoc(String crdtJson) {
        Operations op         = new Operations();
        op.type               = "SAVE_DOC";
        op.sessionCode        = sessionCode;
        op.username           = username;
        op.ownerUsername      = username;
        op.payload            = crdtJson;
        op.originalEditorCode = originalEditorCode;
        send(op.toJson());
    }

    public void sendSaveDoc(String crdtJson, String docName) {
        Operations op         = new Operations();
        op.type               = "SAVE_DOC";
        op.sessionCode        = sessionCode;
        op.username           = username;
        op.ownerUsername      = username;
        op.payload            = crdtJson;
        op.documentName       = docName;
        op.originalEditorCode = originalEditorCode;
        send(op.toJson());
    }

    // -----------------------------------------------------------------------
    // Comment senders
    // -----------------------------------------------------------------------

    public void sendAddComment(String text, CharNode startNode, CharNode endNode) {
        if (!isOpen()) return;
        Operations op   = new Operations();
        op.type         = "ADD_COMMENT";
        op.sessionCode  = sessionCode;
        op.username     = username;
        op.commentText  = text;
        op.charUser     = startNode.getID().getUserID();
        op.charClock    = startNode.getID().getClock();
        op.endCharUser  = endNode.getID().getUserID();
        op.endCharClock = endNode.getID().getClock();
        send(op.toJson());
    }

    public void sendDeleteComment(String commentId) {
        if (!isOpen()) return;
        Operations op  = new Operations();
        op.type        = "DELETE_COMMENT";
        op.sessionCode = sessionCode;
        op.username    = username;
        op.commentId   = commentId;
        send(op.toJson());
    }

    public void sendGetComments() {
        if (!isOpen()) return;
        Operations op  = new Operations();
        op.type        = "GET_COMMENTS";
        op.sessionCode = sessionCode;
        send(op.toJson());
    }

    public void sendResolveComment(String commentId) {
        if (!isOpen()) return;
        Operations op  = new Operations();
        op.type        = "RESOLVE_COMMENT";
        op.sessionCode = sessionCode;
        op.username    = username;
        op.commentId   = commentId;
        send(op.toJson());
    }
    // ADD THIS METHOD: Intercepts raw send() calls to prevent crashes
    public void sendSafely(String json) {
        if (isOpen()) {
            send(json);
        } else {
            System.err.println("[Client] Network transmission aborted: WebSocket is disconnected.");
        }
    }
// ── FILE: Client.java ────────────────────────────────────────────────────
// REPLACE sendMoveBlockExec() entirely

    // REPLACE sendMoveBlockExec() in Client.java
    public void sendMoveBlockExec(BlockNode newBlock, BlockID anchorBlockID,
                                  BlockID deletedBlockID) {
        if (!isOpen()) return;
        Operations op       = new Operations();
        op.type             = "MOVE_BLOCK_EXEC";
        op.sessionCode      = sessionCode;
        op.username         = username;
        op.blockUser        = newBlock.getId().getUserID();
        op.blockClock       = newBlock.getId().getClock();
        op.parentBlockUser  = newBlock.getParentID() != null
                ? newBlock.getParentID().getUserID()  : -1;
        op.parentBlockClock = newBlock.getParentID() != null
                ? newBlock.getParentID().getClock()   : -1;
        // Anchor: block that the moved block goes AFTER (null = insert at top)
        op.anchorBlockUser  = (anchorBlockID != null) ? anchorBlockID.getUserID()  : -1;
        op.anchorBlockClock = (anchorBlockID != null) ? anchorBlockID.getClock()   : -1;
        // Deleted block (-1/-1 means paste, no deletion)
        op.targetBlockUser  = (deletedBlockID != null)
                ? deletedBlockID.getUserID()  : -1;
        op.targetBlockClock = (deletedBlockID != null)
                ? deletedBlockID.getClock()   : -1;
        if (newBlock.getContent() != null) {
            op.blockSnapshot = CrdtSerializer.toJson(newBlock.getContent());
        }
        send(op.toJson());
    }
    // ── FILE: Client.java ────────────────────────────────────────────────────
// ADD these two methods

    public void sendBeginGroup() {
        if (!isOpen()) return;
        Operations op  = new Operations();
        op.type        = "BEGIN_GROUP";
        op.sessionCode = sessionCode;
        op.username    = username;
        send(op.toJson());
    }

    public void sendEndGroup() {
        if (!isOpen()) return;
        Operations op  = new Operations();
        op.type        = "END_GROUP";
        op.sessionCode = sessionCode;
        op.username    = username;
        send(op.toJson());
    }

    // -----------------------------------------------------------------------
    // Getters / setters
    // -----------------------------------------------------------------------

    public String  getSessionCode()  { return sessionCode; }
    public String  getEditorCode()   { return editorCode; }
    public String  getViewerCode()   { return viewerCode; }
    public String  getUsername()     { return username; }
    public String  getRole()         { return role; }
    public boolean isEditor()        { return "editor".equals(role); }

    public void setActiveBlockID(BlockID id)           { this.activeBlockID = id; }
    public void setMessageListener(MessageListener ml) { this.messageListener = ml; }
    public void setOriginalEditorCode(String code)     { this.originalEditorCode = code; }
    public String getOriginalEditorCode()              { return originalEditorCode; }

    public CharCRDT getActiveCharCRDT() {
        BlockNode block = localDoc.getBlock(activeBlockID);
        return block != null ? block.getContent() : null;
    }

    public BlockCRDT getLocalDoc()    { return localDoc; }
    public BlockID   getActiveBlockID() { return activeBlockID; }

}