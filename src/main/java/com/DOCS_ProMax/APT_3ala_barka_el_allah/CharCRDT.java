package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CharCRDT {
    private CharNode root;
    private int userid;
    private Clock clock;
    private Map<CharID, CharNode> nodeMap;
    public final CharID rootID = new CharID(-1, -1);

    public CharCRDT(int userid) {
        this.userid = userid;
        this.clock = new Clock();
        this.root = new CharNode(rootID, null, '\0');
        this.nodeMap = new HashMap<>();
        this.nodeMap.put(rootID, root);
    }

    public CharCRDT(int userid, Clock clock) {
        this.userid = userid;
        this.clock = clock;
        this.root = new CharNode(rootID, null, '\0');
        this.nodeMap = new HashMap<>();
        this.nodeMap.put(rootID, root);
    }

    public CharID generateID() {
        return new CharID(userid, clock.tick());
    }


    public CharNode getNode(CharID id) {
        return nodeMap.get(id);
    }

    private void depthFirstTraversal(CharNode node, List<CharNode> result) {
        if (node != root && !node.isDeleted()) {
            result.add(node);
        }
        for (CharNode child : node.getChildren()) {
            depthFirstTraversal(child, result);
        }
    }

    public List<CharNode> getOrderedNodes() {
        List<CharNode> result = new ArrayList<>();
        depthFirstTraversal(root, result);
        return result;
    }

    public CharNode insertNode(CharID parentID, char value) {
        CharNode parent = nodeMap.get(parentID);
        if (parent != null) {
            CharID newID = generateID();
            CharNode newNode = new CharNode(newID, parentID, value);
            parent.addChild(newNode);
            nodeMap.put(newID, newNode);
            return newNode;
        }
        return null;
    }

    public CharNode RemotelyInsertion(CharID incomingID, CharID parentID, char value) {
        if (nodeMap.containsKey(incomingID)) {
            return null;
        }

        CharNode parent = nodeMap.get(parentID);

        if (parent != null) {
            CharNode incomingNode = new CharNode(incomingID, parentID, value);
            parent.addChild(incomingNode);
            nodeMap.put(incomingID, incomingNode);
            // Advance our clock so future local IDs are always greater than any received remote ID
            clock.advanceTo(incomingID.getClock());
            return incomingNode;
        } else {
            System.out.println("Error: Parent " + parentID + " not found for incoming character '" + value + "'");
            return null;
        }
    }
}