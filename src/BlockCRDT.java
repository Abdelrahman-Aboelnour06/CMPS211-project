import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.plaf.basic.BasicInternalFrameTitlePane.MaximizeAction;



public class BlockCRDT {
    
    private static final int MAX_LINES = 10;
    private static final int MIN_LINES = 2;

    BlockNode root;
    private int userid;
    private Clock clock;
    private Map<BlockID, BlockNode> nodeMap;

    public BlockCRDT(int userid, Clock clock) {
        this.userid = userid;
        this.clock = clock;
        this.nodeMap = new HashMap<>();
        this.root = new BlockNode(null, null, null);
        nodeMap.put(root.getId(), root);
    }
    private BlockID generateID() {
        return new BlockID(userid, clock.tick());
    }

    public BlockNode createNode(BlockID parentID, CharCRDT content) {
        BlockID ID = generateID();
        BlockNode newNode = new BlockNode(ID, parentID, content);
        nodeMap.put(ID, newNode);
        return newNode;
    }

    private BlockNode getNode(BlockID id) {
        return nodeMap.get(id);
    }

    public void deleteNode(BlockID id) {
        BlockNode node = getNode(id);
        if (node != null) {
            node.setDeleted(true);
        }
    }

    public void insertBlock(BlockID parentID, CharCRDT content) {
        BlockNode parentNode = getNode(parentID);

        if (parentNode != null) {
            BlockNode newNode = createNode(parentID, content);
            parentNode.addChild(newNode);
        }

    }

    private void depthFirstTraversal(BlockNode node, List<BlockNode> result){
        if (node.getId() != null && !node.isDeleted()) {
            result.add(node);
        }
        for (BlockNode child : node.getChildren()) {
            depthFirstTraversal(child, result);
        }

    }

    public List<BlockNode> getOrderedNodes() {
        List<BlockNode> result = new ArrayList<>();
        depthFirstTraversal(root, result);
        return result;
    }
    

    public void checkAndSplit_Merge(BlockID targetblockID) {
        BlockNode targetNode =getNode(targetblockID);

        if (isblockEmpty(targetNode)) {
            return;
        }

        if (isSplitNeeded(targetNode)) {
            splitBlock(targetNode);
        } else if (isMergeNeeded(targetNode)) {
            mergeBlock(targetNode);
        }
       
    }

    private boolean isSplitNeeded(BlockNode node) {
        return node.getLineCount() > MAX_LINES;
    }

    private boolean isMergeNeeded(BlockNode node) {
        return node.getLineCount() < MIN_LINES;
    }

    private boolean isblockEmpty(BlockNode node) {
        return node == null || node.isDeleted() || node.getContent() == null;
    }

    private void splitBlock(BlockNode sourceNode) {
        BlockNode targetNode = createSiblingBlock(sourceNode);

        moveTextAfterLine(sourceNode, targetNode, MAX_LINES/2);
    }


    private CharID moveCharToNewNode(CharNode sourceNode, BlockNode newNode, CharID lastCharID) {
        CharNode newChar = newNode.addChar(lastCharID, sourceNode.getValue());
        lastCharID = newChar.getID();
        sourceNode.SetDeleted(true);
        return lastCharID;
    }

    private BlockNode createSiblingBlock(BlockNode sourceNode) {
        BlockNode newNode = createNode(sourceNode.getId(), new CharCRDT(this.userid));
        sourceNode.addChild(newNode);
        return newNode;
    }

    private void moveTextAfterLine(BlockNode sourceNode, BlockNode targetNode, int splitLineCount) {
        int newlineCount = 0;
        CharID lastCharID = null;

        for (CharNode charNode : sourceNode.getChars()) {
            if (newlineCount >= splitLineCount) {
                lastCharID = moveCharToNewNode(charNode, targetNode, lastCharID);
                continue; 
            }

            if (charNode.getValue() == '\n') {
                newlineCount++;
            }
        }
    }

    public void mergeBlock(BlockNode targetNode) {
        BlockNode siblingNode = findSiblingForMerge(targetNode);
        if (siblingNode != null) {
            moveAllText(siblingNode, targetNode);
            deleteNode(siblingNode.getId());
        }
    }
    private BlockNode findSiblingForMerge(BlockNode targetNode) {

        BlockNode parent = getNode(targetNode.getParentID());
        if (parent == null) {
            return null; 
        }

        for (BlockNode sibling : parent.getChildren()) {
            if (sibling != targetNode && !sibling.isDeleted()) {
                return sibling;
            }
        }
        return null;
    }
   
    private void moveAllText(BlockNode sourceNode, BlockNode targetNode) {
        CharID lastCharID = getlastcharID(targetNode);
        

        for (CharNode charNode : sourceNode.getChars()) {
            CharNode newChar = targetNode.addChar(lastCharID, charNode.getValue());
            lastCharID = newChar.getID(); 
            
            charNode.SetDeleted(true);
        }
    }

    private CharID getlastcharID(BlockNode node) {
        
        List<CharNode> chars = node.getChars();
        if (!chars.isEmpty()) {
            return chars.get(chars.size() - 1).getID();
        }
    
        return null;
    }
}
