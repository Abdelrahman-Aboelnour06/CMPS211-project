package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class BlockNode {

    private final BlockID id;
    private final BlockID parentID;
    private final CharCRDT charCRDT;
    private boolean isDeleted;
    private final List<BlockNode> children;

    public BlockNode(BlockID id, BlockID parentID, CharCRDT content) {
        this.id = id;
        this.parentID = parentID;
        this.charCRDT = content;
        this.isDeleted = false;
        this.children = new ArrayList<>();
    }

    public BlockID getId()          { return id; }
    public boolean isDeleted()      { return isDeleted; }
    public void setDeleted(boolean deleted) { this.isDeleted = deleted; }
    public CharCRDT getContent()    { return charCRDT; }
    public List<BlockNode> getChildren() { return children; }
    public BlockID getParentID()    { return parentID; }

    public int getLineCount() {
        if (isEmpty() || isDeleted()) return 0;
        return getTotalLineCount();
    }

    public CharNode addChar(CharID parentID, char value) {
        return charCRDT.insertNode(parentID, value);
    }

    public List<CharNode> getChars() {
        return charCRDT.getOrderedNodes();
    }

    // FIX: null check for empty target block
    public boolean moveTextFromIndex(BlockNode targetNode, int startIndex) {
        if (targetNode == null || startIndex < 0) return false;

        List<CharNode> chars = getChars();
        if (startIndex > chars.size()) return false;

        CharID lastCharID = targetNode.getLastCharID();
        if (lastCharID == null) lastCharID = targetNode.getContent().rootID;

        for (int i = startIndex; i < chars.size(); i++) {
            CharNode current = chars.get(i);
            CharNode newChar = targetNode.addChar(lastCharID, current.getValue());
            if (newChar == null) return false;
            newChar.setBold(current.isBold());
            newChar.setItalic(current.isItalic());
            lastCharID = newChar.getID();
            current.SetDeleted(true);
        }
        return true;
    }

    // FIX: null check for empty target block
    public boolean moveTextFromLine(BlockNode targetNode, int splitLineCount) {
        int currentLines = 0;

        CharID lastCharID = targetNode.getLastCharID();
        if (lastCharID == null) lastCharID = targetNode.getContent().rootID;

        for (CharNode charNode : getChars()) {
            if (currentLines >= splitLineCount) {
                CharNode newChar = targetNode.addChar(lastCharID, charNode.getValue());
                if (newChar == null) return false;
                newChar.setBold(charNode.isBold());
                newChar.setItalic(charNode.isItalic());
                lastCharID = newChar.getID();
                charNode.SetDeleted(true);
            }
            if (charNode.getValue() == '\n') currentLines++;
        }
        return true;
    }

    // This was missing — required by BlockCRDT.mergeBlock / deleteNode
    public boolean moveAllText(BlockNode targetNode) {
        return this.moveTextFromLine(targetNode, 0);
    }

    public CharID getLastCharID() {
        List<CharNode> chars = getChars();
        if (!chars.isEmpty()) return chars.get(chars.size() - 1).getID();
        return null;
    }

    public void deleteBlock() { setDeleted(true); }

    private int getTotalLineCount() {
        int lines = 1;
        for (CharNode charNode : charCRDT.getOrderedNodes()) {
            if (charNode.getValue() == '\n') lines++;
        }
        return lines;
    }

    private boolean isEmpty() {
        return charCRDT.getOrderedNodes().isEmpty();
    }

    public void addChild(BlockNode child) {
        children.add(child);
        children.sort(Comparator.comparing(BlockNode::getId));
    }
}