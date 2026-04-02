import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BlockCRDT {

    private static final int MAX_LINES = 10;
    private static final int MIN_LINES = 2;
    private static final BlockID ROOT_ID = new BlockID(-1, -1);

    private BlockNode root;
    private int userid;
    private Clock clock;
    private Map<BlockID, BlockNode> nodeMap;

    public BlockCRDT(int userid, Clock clock) {
        this.userid = userid;
        this.clock = clock;
        this.nodeMap = new HashMap<>();
        this.root = new BlockNode(ROOT_ID, null, null);
        nodeMap.put(ROOT_ID, root);
    }


    public void insertBlock(BlockID parentID, CharCRDT content) {
        BlockNode parentNode = getNode(parentID);

        if (parentNode != null) {
            BlockNode newNode = createNode(parentID, content);
            parentNode.addChild(newNode);
        }
    }

    public BlockNode insertTopLevelBlock(CharCRDT content) {
        BlockNode newNode = createNode(null, content);
        root.addChild(newNode);
        return newNode;
    }

    public void deleteNode(BlockID id) {
        BlockNode node = getNode(id);
        if (node != null) {
            node.setDeleted(true);
        }
    }

    public List<BlockNode> getOrderedNodes() {
        List<BlockNode> result = new ArrayList<>();
        depthFirstTraversal(root, result);
        return result;
    }


    public boolean insertCharInBlock(BlockID blockID, char value, int index) {
        BlockNode blockNode = getNode(blockID);
        if (isblockEmpty(blockNode)) {
            return false;
        }

        List<CharNode> chars = blockNode.getChars();
        if (index < 0 || index > chars.size()) {
            return false;
        }

        CharID parentID;
        if (index == 0) {
            parentID = blockNode.getContent().rootID;
        } else {
            parentID = chars.get(index - 1).getID();
        }

        CharNode insertedChar = blockNode.addChar(parentID, value);
        if (insertedChar == null) {
            return false;
        }

        checkAndSplit_Merge(blockID);
        return true;
    }

    public boolean deleteCharInBlock(BlockID blockID, int index) {
        BlockNode blockNode = getNode(blockID);
        if (isblockEmpty(blockNode)) {
            return false;
        }

        List<CharNode> chars = blockNode.getChars();
        if (index < 0 || index >= chars.size()) {
            return false;
        }

        chars.get(index).SetDeleted(true);


        checkAndSplit_Merge(blockID);
        return true;
    }

    public boolean insertLineInBlock(BlockID blockID, int index) {
        return insertCharInBlock(blockID, '\n', index);
    }

    public BlockNode splitBlockAtCursor(BlockID blockID, int cursorIndex) {
        BlockNode sourceNode = getNode(blockID);
        if (isblockEmpty(sourceNode)) {
            return null;
        }

        List<CharNode> chars = sourceNode.getChars();
        if (cursorIndex < 0 || cursorIndex > chars.size()) {
            return null;
        }

        return splitBlock(sourceNode, cursorIndex);
    }

    public boolean mergeWithPrevious(BlockID blockID) {
        return mergeWithDirection(blockID, -1);
    }

    public boolean mergeWithNext(BlockID blockID) {
        return mergeWithDirection(blockID, 1);
    }


    public void checkAndSplit_Merge(BlockID targetblockID) {
        BlockNode targetNode = getNode(targetblockID);
        if (isblockEmpty(targetNode)) {
            return;
        }

        if (isSplitNeeded(targetNode)) {
            splitBlock(targetNode);
        } else if (isMergeNeeded(targetNode)) {
            mergeBlock(targetNode);
        }
    }

    public void mergeBlock(BlockNode targetNode) {
        BlockNode siblingNode = findNearestSiblingForMerge(targetNode);
        if (siblingNode != null && siblingNode.moveAllText(targetNode)) {
            deleteNode(siblingNode.getId());
        }
    }


    private BlockID generateID() {
        return new BlockID(userid, clock.tick());
    }

    public BlockNode createNode(BlockID parentID, CharCRDT content) {
        BlockID normalizedParentID = normalizeParentID(parentID);
        BlockID ID = generateID();
        BlockNode newNode = new BlockNode(ID, normalizedParentID, content);
        nodeMap.put(ID, newNode);
        return newNode;
    }

    private BlockID normalizeParentID(BlockID parentID) {
        return parentID == null ? ROOT_ID : parentID;
    }

    private BlockNode getNode(BlockID id) {
        return nodeMap.get(normalizeParentID(id));
    }


    private void depthFirstTraversal(BlockNode node, List<BlockNode> result) {
        if (node.getId() != null && !node.isDeleted()) {
            result.add(node);
        }
        for (BlockNode child : node.getChildren()) {
            depthFirstTraversal(child, result);
        }
    }


    private boolean mergeWithDirection(BlockID blockID, int direction) {
        if (direction != -1 && direction != 1) {
            return false;
        }

        BlockNode targetNode = getNode(blockID);
        if (isblockEmpty(targetNode)) {
            return false;
        }

        BlockNode siblingNode = findSiblingByDirection(targetNode, direction);
        if (siblingNode == null) {
            return false;
        }

        BlockNode sourceNode = direction == -1 ? targetNode : siblingNode;
        BlockNode destinationNode = direction == -1 ? siblingNode : targetNode;

        if (sourceNode.moveAllText(destinationNode)) {
            deleteNode(sourceNode.getId());
            return true;
        }

        return false;
    }

    private BlockNode findNearestSiblingForMerge(BlockNode targetNode) {
        if (targetNode == null) {
            return null;
        }

        BlockNode parent = getNode(targetNode.getParentID());
        if (parent == null) {
            return null;
        }

        List<BlockNode> siblings = parent.getChildren();
        int targetIndex = siblings.indexOf(targetNode);

        if (targetIndex == -1) {
            return null;
        }

        BlockNode previousSibling = findActiveSibling(siblings, targetIndex - 1, -1);
        if (previousSibling != null) {
            return previousSibling;
        }

        return findActiveSibling(siblings, targetIndex + 1, 1);
    }

    private BlockNode findSiblingByDirection(BlockNode targetNode, int direction) {
        if (targetNode == null || (direction != -1 && direction != 1)) {
            return null;
        }

        BlockNode parent = getNode(targetNode.getParentID());
        if (parent == null) {
            return null;
        }

        List<BlockNode> siblings = parent.getChildren();
        int targetIndex = siblings.indexOf(targetNode);
        if (targetIndex == -1) {
            return null;
        }

        return findActiveSibling(siblings, targetIndex + direction, direction);
    }

    private BlockNode findActiveSibling(List<BlockNode> siblings, int startIndex, int step) {
        for (int i = startIndex; i >= 0 && i < siblings.size(); i += step) {
            BlockNode candidate = siblings.get(i);
            if (!candidate.isDeleted()) {
                return candidate;
            }
        }

        return null;
    }

    public BlockNode getBlock(BlockID id) {
        return getNode(id);
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

    private boolean splitBlock(BlockNode sourceNode) {
        BlockNode targetNode = createSiblingBlock(sourceNode);
        if (targetNode != null) {
            return sourceNode.moveTextFromLine(targetNode, MAX_LINES/2);
        }
        return false;
    }

    private BlockNode splitBlock(BlockNode sourceNode, int cursorIndex) {
        BlockNode targetNode = createSiblingBlock(sourceNode);
        if (targetNode == null) {
            return null;
        }

        if (!sourceNode.moveTextFromIndex(targetNode, cursorIndex)) {
            return null;
        }

        return targetNode;
    }

    private BlockNode createSiblingBlock(BlockNode sourceNode) {
        if (sourceNode == null) {
            return null;
        }

        BlockID parentID = sourceNode.getParentID();
        BlockNode parentNode = getNode(parentID);
        if (parentNode == null) {
            return null;
        }

        BlockNode newNode = createNode(parentID, new CharCRDT(this.userid));
        parentNode.addChild(newNode);
        return newNode;
    }
}
