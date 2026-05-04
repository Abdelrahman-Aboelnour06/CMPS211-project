package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Iterator;

public class CharCRDT {
    private CharNode root;
    private int userid;
    private Clock clock;
    private Map<CharID, CharNode> nodeMap;
    public final CharID rootID = new CharID(-1, -1);
    private final List<PendingOp> pendingOps = new ArrayList<>();

    public CharCRDT(int userid) {
        this.userid = userid;
        this.clock = new Clock();
        this.root = new CharNode(rootID, null, '\0');
        this.nodeMap = new HashMap<>();
        this.nodeMap.put(rootID, root);
    }

    private static class PendingOp {
        final CharID incomingID;
        final CharID parentID;
        final char value;
        boolean isBold;
        boolean isItalic;

        PendingOp(CharID incomingID, CharID parentID, char value) {
            this.incomingID = incomingID;
            this.parentID = parentID;
            this.value = value;
        }
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

    public List<CharNode> getAllNodesIncludingDeleted() {
        List<CharNode> result = new ArrayList<>();
        traverseAll(root, result);
        return result;
    }

    private void traverseAll(CharNode node, List<CharNode> result) {
        if (node != root) result.add(node);
        for (CharNode child : node.getChildren()) {
            traverseAll(child, result);
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
            clock.advanceTo(incomingID.getClock());
            retryPending();
            return incomingNode;
        } else {
            System.out.println("Error: Parent " + parentID + " not found for incoming character '" + value + ", waiting for parent to arrive");
            pendingOps.add(new PendingOp(incomingID, parentID, value));
            return null;
        }
    }
    private boolean isRetrying = false;

    private void retryPending() {
        if (isRetrying) return; // Prevent StackOverflow!
        isRetrying = true;

        boolean progress = true;
        while (progress) {
            progress = false;
            Iterator<PendingOp> it = pendingOps.iterator();
            while (it.hasNext()) {
                PendingOp op = it.next();
                if (nodeMap.containsKey(op.parentID)) {
                    it.remove(); // remove from pending BEFORE inserting
                    RemotelyInsertion(op.incomingID, op.parentID, op.value);
                    progress = true; // we resolved one, loop again
                }
            }
        }

        isRetrying = false;
    }
}