package com.DOCS_ProMax.APT_3ala_barka_el_allah;


public class DBTestClient {

    public static void main(String[] args) throws Exception {


        Clock clock = new Clock();
        BlockCRDT doc = new BlockCRDT(1, clock);
        BlockNode block = doc.insertTopLevelBlock(new CharCRDT(1, clock));
        CharCRDT charCrdt = block.getContent();

        charCrdt.insertNode(charCrdt.rootID, 'H');
        charCrdt.insertNode(charCrdt.getOrderedNodes().get(0).getID(), 'I');

        Client client = new Client("ws://localhost:8080/collab", doc, clock, block.getId());


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


        client.connectBlocking();
        System.out.println("[DBTest] Connected to WebSocket.");


        System.out.println("[DBTest] Creating session for 'DB_Tester'...");
        client.createSession("DB_Tester");

        long deadline = System.currentTimeMillis() + 3000;
        while (sessionCode[0] == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        System.out.println("[DBTest] Session created. Editor Code: " + sessionCode[0]);

        System.out.println("[DBTest] Sending SAVE_DOC to MongoDB...");
        String crdtJson = CrdtSerializer.toJson(charCrdt);
        client.sendSaveDoc(crdtJson);

        deadline = System.currentTimeMillis() + 3000;
        while (!docSaved[0] && System.currentTimeMillis() < deadline) Thread.sleep(50);


        System.out.println("[DBTest] Sending LOAD_DOC to fetch it back...");
        Operations loadOp = new Operations();
        loadOp.type = "LOAD_DOC";
        loadOp.sessionCode = sessionCode[0];
        client.send(loadOp.toJson());

        deadline = System.currentTimeMillis() + 3000;
        while (!docLoaded[0] && System.currentTimeMillis() < deadline) Thread.sleep(50);


        System.out.println("[DBTest] Sending LIST_DOCS to verify ownership...");
        Operations listOp = new Operations();
        listOp.type = "LIST_DOCS";
        listOp.ownerUsername = "DB_Tester";
        client.send(listOp.toJson());

        deadline = System.currentTimeMillis() + 3000;
        while (!docListed[0] && System.currentTimeMillis() < deadline) Thread.sleep(50);


        if (docSaved[0] && docLoaded[0] && docListed[0]) {
            System.out.println(" ALL DATABASE TESTS PASSED! MongoDB is working perfectly.");
            System.out.println(" Go refresh MongoDB Compass, and 'collab_editor' will now be there!");
        } else {
            System.err.println("\nSOME TESTS FAILED. Check the server console for errors.");
        }

        client.closeBlocking();
    }
}