package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * WebSocket client that connects to the collaboration server.
 *
 * Responsibilities:
 *  - Send local CRDT operations (INSERT_CHAR, DELETE_CHAR, CURSOR) to the server.
 *  - Receive remote operations from the server and apply them to the local CRDT.
 *
 * Usage:
 *   Client client = new Client("ws://localhost:8080/collab", localBlockCRDT, sharedClock, blockID);
 *   client.connect();
 *   client.createSession("Alice");   // or client.joinSession("Alice", "ABC123");
 */
public class Client extends WebSocketClient {

    private final BlockCRDT localDoc;
    private final Clock sharedClock;
    private BlockID activeBlockID;   // the block this client is currently editing

    // Filled in once the server confirms session creation / joining
    private String sessionCode;
    private String username;

    // Optional callback so the UI layer can react to incoming messages
    private MessageListener messageListener;

    public interface MessageListener {
        void onMessage(Operations op);
    }

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    public Client(String serverUri, BlockCRDT localDoc, Clock sharedClock, BlockID activeBlockID)
            throws URISyntaxException {
        super(new URI(serverUri));
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
        Operations op = Operations.fromJson(rawJson);
        if (op == null) return;

        switch (op.type) {

            case "SESSION_CREATED" -> {
                this.sessionCode = op.sessionCode;
                System.out.println("[Client] Session created: " + sessionCode);
            }

            case "SESSION_JOINED" -> {
                this.sessionCode = op.sessionCode;
                System.out.println("[Client] Joined session: " + sessionCode);
            }

            case "ACTIVE_USERS" -> {
                System.out.println("[Client] Active users: " + op.payload);
            }

            case "INSERT_CHAR" -> {
                // Reconstruct IDs from the wire
                CharID incomingID = new CharID(op.charUser,   op.charClock);
                CharID parentID   = new CharID(op.parentUser, op.parentClock);

                // Advance our logical clock so we never generate a duplicate ID
                sharedClock.advanceTo(op.charClock);

                // Apply to the local CRDT block
                BlockNode block = localDoc.getBlock(activeBlockID);
                if (block != null && block.getContent() != null) {
                    block.getContent().RemotelyInsertion(incomingID, parentID, op.value);
                }
            }

            case "DELETE_CHAR" -> {
                CharID targetID = new CharID(op.charUser, op.charClock);
                sharedClock.advanceTo(op.charClock);

                BlockNode block = localDoc.getBlock(activeBlockID);
                if (block != null && block.getContent() != null) {
                    CharNode node = block.getContent().getNode(targetID);
                    if (node != null) {
                        node.SetDeleted(true);
                    }
                }
            }

            case "CURSOR" -> {
                // Cursor positions are informational only – forward to UI if listener is set
                System.out.println("[Client] " + op.username + " cursor at index " + op.cursorIndex);
            }

            case "ERROR" -> {
                System.err.println("[Client] Server error: " + op.payload);
            }

            default -> {
                System.out.println("[Client] Unknown message type: " + op.type);
            }
        }

        // Notify the UI layer
        if (messageListener != null) {
            messageListener.onMessage(op);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println("[Client] Connection closed. Code=" + code + " Reason=" + reason);
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
        op.type        = "CREATE_SESSION";
        op.username    = username;
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

    // -----------------------------------------------------------------------
    // Operation senders  (called by the UI / local editor after a local edit)
    // -----------------------------------------------------------------------

    /**
     * Broadcast a local character insertion to all peers.
     *
     * @param inserted  The CharNode that was just inserted into the local CRDT.
     */
    public void sendInsertChar(CharNode inserted) {
        Operations op = new Operations();
        op.type        = "INSERT_CHAR";
        op.sessionCode = sessionCode;
        op.username    = username;

        op.charUser    = inserted.getID().getUserID();
        op.charClock   = inserted.getID().getClock();
        op.parentUser  = inserted.getParentID().getUserID();
        op.parentClock = inserted.getParentID().getClock();
        op.value       = inserted.getValue();

        send(op.toJson());
    }

    /**
     * Broadcast a local character deletion to all peers.
     *
     * @param deleted  The CharNode that was just soft-deleted in the local CRDT.
     */
    public void sendDeleteChar(CharNode deleted) {
        Operations op = new Operations();
        op.type        = "DELETE_CHAR";
        op.sessionCode = sessionCode;
        op.username    = username;

        op.charUser    = deleted.getID().getUserID();
        op.charClock   = deleted.getID().getClock();

        send(op.toJson());
    }

    /**
     * Broadcast the local cursor position so other users can see where you are.
     *
     * @param index  Visual index of the cursor in the rendered document.
     */
    public void sendCursor(int index) {
        Operations op = new Operations();
        op.type        = "CURSOR";
        op.sessionCode = sessionCode;
        op.username    = username;
        op.cursorIndex = index;
        send(op.toJson());
    }

    public void requestActiveUsers() {
        Operations op = new Operations();
        op.type = "GET_ACTIVE_USERS";
        op.sessionCode = sessionCode;
        send(op.toJson());
    }

    // -----------------------------------------------------------------------
    // Getters / setters
    // -----------------------------------------------------------------------

    public String getSessionCode()  { return sessionCode; }
    public String getUsername()     { return username; }

    public void setActiveBlockID(BlockID id)           { this.activeBlockID = id; }
    public void setMessageListener(MessageListener ml) { this.messageListener = ml; }
}