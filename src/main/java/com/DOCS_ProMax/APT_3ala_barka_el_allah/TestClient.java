package com.DOCS_ProMax.APT_3ala_barka_el_allah;

/**
 * Manual smoke-test client.
 */
public class TestClient {

    public static void main(String[] args) throws Exception {

        Clock      sharedClock = new Clock();
        BlockCRDT  localDoc    = new BlockCRDT(1, sharedClock);
        BlockNode  block       = localDoc.insertTopLevelBlock(new CharCRDT(1, sharedClock));

        // Connect to the running Spring Boot server
        Client client = new Client("ws://localhost:8080/collab", localDoc, sharedClock, block.getId());

        // Track what the server sends back using smart flags
        final boolean[] gotCreated = {false};
        final String[] receivedCode = {null};

        client.setMessageListener(op -> {
            // Only flip the flag if it's the specific message we are looking for
            if ("SESSION_CREATED".equals(op.type)) {
                gotCreated[0] = true;
            }
            // Only grab the code if we haven't grabbed it yet
            if (op.sessionCode != null && receivedCode[0] == null) {
                receivedCode[0] = op.sessionCode;
            }
        });

        client.connectBlocking();   // blocks until the WebSocket handshake completes
        System.out.println("[TestClient] WebSocket open: " + client.isOpen());

        // Ask the server to create a session
        client.createSession("Alice");

        // Give the server up to 3 seconds to respond
        long deadline = System.currentTimeMillis() + 3_000;
        // Wait until our specific flag flips
        while (!gotCreated[0] && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        // ---- Assertions ----
        boolean connected = client.isOpen();
        boolean hasCode = receivedCode[0] != null && receivedCode[0].length() == 6;

        System.out.println("[TestClient] isOpen           : " + connected);
        System.out.println("[TestClient] SESSION_CREATED  : " + gotCreated[0]);
        System.out.println("[TestClient] session code (6) : " + receivedCode[0]);

        if (connected && gotCreated[0] && hasCode) {
            System.out.println("[TestClient] ✓ All checks passed.");
        } else {
            System.err.println("[TestClient] ✗ Some checks FAILED — see above.");
        }

        client.closeBlocking();
    }
}