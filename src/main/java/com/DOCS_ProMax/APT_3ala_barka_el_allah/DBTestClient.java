package com.DOCS_ProMax.APT_3ala_barka_el_allah;

/**
 * An automated script to test Member 1's MongoDB database integration.
 * Make sure the Spring Boot server is running before executing this!
 */
public class DBTestClient {

    public static void main(String[] args) throws Exception {

        // 1. Setup a dummy CRDT with the word "HI"
        Clock clock = new Clock();
        BlockCRDT doc = new BlockCRDT(1, clock);
        BlockNode block = doc.insertTopLevelBlock(new CharCRDT(1, clock));
        CharCRDT charCrdt = block.getContent();

        charCrdt.insertNode(charCrdt.rootID, 'H');
        charCrdt.insertNode(charCrdt.getOrderedNodes().get(0).getID(), 'I');

        Client client = new Client("ws://localhost:8080/collab", doc, clock, block.getId());

        // Tracking flags to wait for server responses
        final String[] sessionCode = {null};
        final boolean[] docSaved = {false};
        final boolean[] docLoaded = {false};
        final boolean[] docListed = {false};

        client.setMessageListener(op -> {
            if ("SESSION_CREATED".equals(op.type)) {
                sessionCode[0] = op.editorCode;
            } else if ("DOC_SAVED".equals(op.type)) {
                System.out.println("[DBTest] ✅ Received DOC_SAVED confirmation!");
                docSaved[0] = true;
            } else if ("DOC_LOADED".equals(op.type)) {
                System.out.println("[DBTest] ✅ Received DOC_LOADED! Fetched CRDT JSON from MongoDB.");
                docLoaded[0] = true;
            } else if ("DOCS_LIST".equals(op.type)) {
                System.out.println("[DBTest] ✅ Received DOCS_LIST! Found in DB: " + op.payload);
                docListed[0] = true;
            }
        });

        // 2. Connect
        client.connectBlocking();
        System.out.println("[DBTest] Connected to WebSocket.");

        // 3. Create Session
        System.out.println("[DBTest] Creating session for 'DB_Tester'...");
        client.createSession("DB_Tester");

        long deadline = System.currentTimeMillis() + 3000;
        while (sessionCode[0] == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        System.out.println("[DBTest] Session created. Editor Code: " + sessionCode[0]);

        // 4. Test SAVE_DOC
        System.out.println("[DBTest] Sending SAVE_DOC to MongoDB...");
        String crdtJson = CrdtSerializer.toJson(charCrdt);
        client.sendSaveDoc(crdtJson);

        deadline = System.currentTimeMillis() + 3000;
        while (!docSaved[0] && System.currentTimeMillis() < deadline) Thread.sleep(50);

        // 5. Test LOAD_DOC
        System.out.println("[DBTest] Sending LOAD_DOC to fetch it back...");
        Operations loadOp = new Operations();
        loadOp.type = "LOAD_DOC";
        loadOp.sessionCode = sessionCode[0];
        client.send(loadOp.toJson());

        deadline = System.currentTimeMillis() + 3000;
        while (!docLoaded[0] && System.currentTimeMillis() < deadline) Thread.sleep(50);

        // 6. Test LIST_DOCS
        System.out.println("[DBTest] Sending LIST_DOCS to verify ownership...");
        Operations listOp = new Operations();
        listOp.type = "LIST_DOCS";
        listOp.ownerUsername = "DB_Tester";
        client.send(listOp.toJson());

        deadline = System.currentTimeMillis() + 3000;
        while (!docListed[0] && System.currentTimeMillis() < deadline) Thread.sleep(50);

        // Final Verification
        if (docSaved[0] && docLoaded[0] && docListed[0]) {
            System.out.println("\n🎉 ALL DATABASE TESTS PASSED! MongoDB is working perfectly.");
            System.out.println("👉 Go refresh MongoDB Compass, and 'collab_editor' will now be there!");
        } else {
            System.err.println("\n❌ SOME TESTS FAILED. Check the server console for errors.");
        }

        client.closeBlocking();
    }
}