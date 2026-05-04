package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


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


    private final String serverUri;
    private final ScheduledExecutorService reconnectScheduler =
            Executors.newSingleThreadScheduledExecutor();
    private static final int RECONNECT_INTERVAL_SECONDS = 30;

    public interface MessageListener {
        void onMessage(Operations op);
    }


    public Client(String serverUri, BlockCRDT localDoc, Clock sharedClock, BlockID activeBlockID)
            throws URISyntaxException {
        super(new URI(serverUri));
        this.serverUri     = serverUri;
        this.localDoc      = localDoc;
        this.sharedClock   = sharedClock;
        this.activeBlockID = activeBlockID;
    }


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


            case "INSERT_CHAR" -> {
                CharID incomingID = new CharID(op.charUser, op.charClock);
                CharID parentID   = new CharID(op.parentUser, op.parentClock);
                sharedClock.advanceTo(op.charClock);


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

          //Block operations lel insert w delete w el splitting isa
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


            case "SPLIT_BLOCK" -> {
                BlockID sourceID = new BlockID(op.blockUser, op.blockClock);
                sharedClock.advanceTo(op.blockClock);

                BlockNode sourceBlock = localDoc.getBlock(sourceID);
                if (sourceBlock == null || sourceBlock.isDeleted()
                        || sourceBlock.getContent() == null) {
                    System.err.println("[Client] SPLIT_BLOCK: source block not found " + sourceID);
                    break;
                }


                if (op.targetBlockUser == 0 && op.targetBlockClock == 0) {

                    localDoc.splitBlockAtCursor(sourceID, (int) op.splitAtIndex);
                    System.out.println("[Client] SPLIT_BLOCK (legacy) at index: " + op.splitAtIndex);
                    break;
                }

                BlockID newBlockID = new BlockID(op.targetBlockUser, op.targetBlockClock);
                sharedClock.advanceTo(op.targetBlockClock);


                CharCRDT newCRDT = new CharCRDT(op.targetBlockUser, sharedClock);
                BlockNode newBlock = localDoc.insertBlockWithID(
                        newBlockID, sourceBlock.getParentID(), newCRDT);


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
            case "MOVE_BLOCK_EXEC" -> {
                sharedClock.advanceTo(op.blockClock);


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


                BlockID newBlockID = new BlockID(op.blockUser, op.blockClock);
                CharCRDT newCRDT;
                if (op.blockSnapshot != null && !op.blockSnapshot.isBlank()) {
                    newCRDT = CrdtSerializer.fromJson(op.blockSnapshot, op.blockUser);
                } else {
                    newCRDT = new CharCRDT(op.blockUser, sharedClock);
                }


                int insertPos;
                if (op.anchorBlockUser == -1 && op.anchorBlockClock == -1) {
                    // No anchor = insert at very top
                    insertPos = 0;
                } else {
                    BlockID anchorID = new BlockID(op.anchorBlockUser, op.anchorBlockClock);
                    BlockNode anchorBlock = localDoc.getBlock(anchorID);
                    if (anchorBlock == null) {
                        insertPos = localDoc.getRootChildren().size();
                    } else {
                        List<BlockNode> rawChildren = localDoc.getRootChildren();
                        int anchorIdx = rawChildren.indexOf(anchorBlock);
                        insertPos = (anchorIdx == -1) ? rawChildren.size() : anchorIdx + 1;
                    }
                }


                BlockNode newBlock = localDoc.insertBlockAtPosition(
                        null, newCRDT, insertPos, newBlockID);

                if (newBlock != null) {
                    System.out.println("[Client] MOVE_BLOCK_EXEC: new=" + newBlockID
                            + " anchor=(" + op.anchorBlockUser + "," + op.anchorBlockClock + ")"
                            + " pos=" + insertPos);
                } else {
                    System.err.println("[Client] MOVE_BLOCK_EXEC failed: " + newBlockID);
                }
            }


            case "DOC_SAVED"   -> System.out.println("[Client] Document saved: " + op.payload);
            case "DOC_DELETED" -> System.out.println("[Client] Document deleted: " + op.payload);
            case "DOC_RENAMED" -> System.out.println("[Client] Document renamed to: " + op.payload);
            case "DOCS_LIST"   -> System.out.println("[Client] Documents: " + op.payload);
            case "VERSIONS_LIST" -> System.out.println("[Client] Versions: " + op.payload);

            case "DOC_LOADED" ->
                    System.out.println("[Client] DOC_LOADED received — UI should re-render");


            case "CURSOR" ->
                    System.out.println("[Client] " + op.username
                            + " cursor at " + op.cursorIndex);

          //comenting

            case "COMMENT_ADDED", "COMMENT_DELETED",
                 "COMMENTS_LIST", "RESOLVE_COMMENT", "COMMENT_RESOLVED" ->
                    System.out.println("[Client] Comment op: " + op.type);



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


    public void sendSplitBlock(BlockID blockID, int cursorIndex) {
        if (!isOpen()) return;
        Operations op      = new Operations();
        op.type            = "SPLIT_BLOCK";
        op.sessionCode     = sessionCode;
        op.username        = username;
        op.blockUser       = blockID.getUserID();
        op.blockClock      = blockID.getClock();
        op.splitAtIndex    = cursorIndex;

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

   //undoing and redo
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

    public void sendSafely(String json) {
        if (isOpen()) {
            send(json);
        } else {
            System.err.println("[Client] Network transmission aborted: WebSocket is disconnected.");
        }
    }


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

        op.anchorBlockUser  = (anchorBlockID != null) ? anchorBlockID.getUserID()  : -1;
        op.anchorBlockClock = (anchorBlockID != null) ? anchorBlockID.getClock()   : -1;

        op.targetBlockUser  = (deletedBlockID != null)
                ? deletedBlockID.getUserID()  : -1;
        op.targetBlockClock = (deletedBlockID != null)
                ? deletedBlockID.getClock()   : -1;
        if (newBlock.getContent() != null) {
            op.blockSnapshot = CrdtSerializer.toJson(newBlock.getContent());
        }
        send(op.toJson());
    }


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

    //getters w setters
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