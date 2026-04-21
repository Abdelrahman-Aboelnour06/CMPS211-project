package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import java.util.List;
import java.util.ArrayList;
import java.util.List;

public class Document {
    final private CharCRDT crdtInstance;

    public Document(int CurrentUserID) {
        this.crdtInstance = new CharCRDT(CurrentUserID);
    }

    public Document(CharCRDT existingCRDT) {
        this.crdtInstance = existingCRDT;
    }

    public String RenderDocument(){
        List<CharNode> NodesList = crdtInstance.getOrderedNodes();

        StringBuilder textBuilder = new StringBuilder();

        for (CharNode node : NodesList) {
            textBuilder.append(node.getValue());
        }

        return textBuilder.toString();
    }

    // Hands the UI the actual nodes (and filters out the deleted ones!)
    public List<CharNode> GetVisibleNodes() {
        List<CharNode> allNodes = crdtInstance.getOrderedNodes();
        List<CharNode> visibleNodes = new ArrayList<>();

        for (CharNode node : allNodes) {
            if (!node.isDeleted()) {
                visibleNodes.add(node);
            }
        }
        return visibleNodes;
    }

    /*public void LocalInsert(char value, int index){
        List<CharNode> NodesList = crdtInstance.getOrderedNodes();
        CharID parentID;
        if (index==0){
            parentID=crdtInstance.rootID;
        }
        else{
            CharNode parentNode=NodesList.get(index-1);
            parentID=parentNode.getID();
        }

        crdtInstance.insertNode(parentID,value);
    }

    public void LocalDelete(int index) {
        List<CharNode> NodesList = crdtInstance.getOrderedNodes();

        // Safety check: make sure the index is actually inside the list
        if (index >= 0 && index < NodesList.size()) {
            CharNode DeletedNode = NodesList.get(index);
            DeletedNode.SetDeleted(true);
        } else {
            System.out.println("Warning: Attempted to delete invalid index: " + index);
        }
    }*/

    // REPLACE LocalInsert — now returns the inserted CharNode
    public CharNode LocalInsert(char value, int index) {
        List<CharNode> NodesList = crdtInstance.getOrderedNodes();
        CharID parentID;
        if (index == 0) {
            parentID = crdtInstance.rootID;
        } else {
            parentID = NodesList.get(index - 1).getID();
        }
        return crdtInstance.insertNode(parentID, value);   // <-- returns CharNode
    }

    // REPLACE LocalDelete — now returns the deleted CharNode
    public CharNode LocalDelete(int index) {
        List<CharNode> NodesList = crdtInstance.getOrderedNodes();
        if (index >= 0 && index < NodesList.size()) {
            CharNode deleted = NodesList.get(index);
            deleted.SetDeleted(true);
            return deleted;   // <-- returns CharNode
        }
        System.out.println("Warning: Attempted to delete invalid index: " + index);
        return null;
    }

    // Flips the bold or italic switch for a specific range of highlighted text
    public void FormatSelection(int startIndex, int endIndex, boolean isBoldAction) {
        List<CharNode> visibleNodes = GetVisibleNodes();

        // Safety check to make sure the user actually highlighted something valid
        if (startIndex < 0 || endIndex > visibleNodes.size() || startIndex >= endIndex) {
            return;
        }

        // Loop through only the highlighted nodes
        for (int i = startIndex; i < endIndex; i++) {
            CharNode node = visibleNodes.get(i);

            if (isBoldAction) {
                // If it was already bold, un-bold it. Otherwise, make it bold!
                node.setBold(!node.isBold());
            } else {
                // If we aren't doing bold, we must be doing italic
                node.setItalic(!node.isItalic());
            }
        }
    }

    // Copies the formatting from the previous letter to the newly typed letter
    public void InheritFormatting(int newlyInsertedIndex) {
        List<CharNode> visibleNodes = GetVisibleNodes();

        // Make sure there is actually a letter behind the one we just typed!
        if (newlyInsertedIndex > 0 && newlyInsertedIndex < visibleNodes.size()) {
            CharNode prevNode = visibleNodes.get(newlyInsertedIndex - 1);
            CharNode newNode = visibleNodes.get(newlyInsertedIndex);

            // Copy the formatting
            newNode.setBold(prevNode.isBold());
            newNode.setItalic(prevNode.isItalic());
        }
    }

}