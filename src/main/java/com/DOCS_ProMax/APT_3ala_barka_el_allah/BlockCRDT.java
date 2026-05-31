package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;

public class BlockCRDT {


    private static final int MAX_LINES = 10;
    private static final int MIN_LINES = 2;
    private static final BlockID ROOT_ID = new BlockID(-1, -1);

    private final BlockNode root;
    private int userid;
    private Clock clock;
    private final Map<BlockID, BlockNode> nodeMap;

    public BlockCRDT(int userid, Clock clock) {
        this.userid  = userid;
        this.clock   = clock;
        this.nodeMap = new HashMap<>();
        this.root    = new BlockNode(ROOT_ID, null, null);
        nodeMap.put(ROOT_ID, root);
    }

    public int   getUserid() { return userid; }
    public Clock getClock()  { return clock;  }


    public void insertBlock(BlockID parentID, CharCRDT content) {
        BlockNode parentNode = getNode(parentID);
        if (parentNode != null) {
            BlockNode newNode = createNode(parentID, content);
            parentNode.getChildren().add(newNode);
        }
    }


    public BlockNode insertTopLevelBlock(CharCRDT content) {
        BlockNode newNode = createNode(null, content);
        root.getChildren().add(newNode);
        return newNode;
    }


    public void deleteNode(BlockID id) {
        BlockNode node = getNode(id);
        if (node == null || node.isDeleted()) return;

        if (node.getContent() != null) {
            for (CharNode cn : node.getContent().getOrderedNodes()) {
                cn.SetDeleted(true);
            }
        }


        BlockNode prevLive = findLiveSiblingInDirection(node, -1);
        BlockNode nextLive = findLiveSiblingInDirection(node, +1);

        node.setDeleted(true);


        if (prevLive != null && nextLive != null
                && !prevLive.isDeleted() && !nextLive.isDeleted()) {
            int prevLines = prevLive.getLineCount();
            int nextLines = nextLive.getLineCount();
            // Merge the block with fewer lines into the one with more.
            if (prevLines <= nextLines && prevLines < MIN_LINES) {
                // merge prev into next
                if (prevLive.moveAllText(nextLive)) {
                    prevLive.setDeleted(true);
                }
            } else if (nextLines < prevLines && nextLines < MIN_LINES) {

                if (nextLive.moveAllText(prevLive)) {
                    nextLive.setDeleted(true);
                }
            } else {

                checkAndSplit_Merge(prevLive.getId());
                checkAndSplit_Merge(nextLive.getId());
            }
        } else if (prevLive != null && !prevLive.isDeleted()) {
            checkAndSplit_Merge(prevLive.getId());
        } else if (nextLive != null && !nextLive.isDeleted()) {
            checkAndSplit_Merge(nextLive.getId());
        }
    }


    public List<BlockNode> getOrderedNodes() {
        List<BlockNode> result = new ArrayList<>();
        depthFirstTraversal(root, result);
        return result;
    }


    public boolean insertCharInBlock(BlockID blockID, char value, int index) {
        BlockNode blockNode = getNode(blockID);
        if (isBlockEmpty(blockNode)) return false;

        List<CharNode> chars = blockNode.getChars();
        if (index < 0 || index > chars.size()) return false;

        CharID parentID = (index == 0)
                ? blockNode.getContent().rootID
                : chars.get(index - 1).getID();

        CharNode insertedChar = blockNode.addChar(parentID, value);
        if (insertedChar == null) return false;

        checkAndSplit_Merge(blockID);
        return true;
    }

    public boolean deleteCharInBlock(BlockID blockID, int index) {
        BlockNode blockNode = getNode(blockID);
        if (isBlockEmpty(blockNode)) return false;

        List<CharNode> chars = blockNode.getChars();
        if (index < 0 || index >= chars.size()) return false;

        chars.get(index).SetDeleted(true);
        checkAndSplit_Merge(blockID);
        return true;
    }

    public boolean insertLineInBlock(BlockID blockID, int index) {
        return insertCharInBlock(blockID, '\n', index);
    }


    public BlockNode splitBlockAtCursor(BlockID blockID, int localIndex) {
        BlockNode sourceNode = getNode(blockID);
        if (isBlockEmpty(sourceNode)) return null;

        List<CharNode> chars = sourceNode.getChars();

        if (localIndex < 0 || localIndex > chars.size()) return null;

        return splitBlock(sourceNode, localIndex);
    }
    public BlockNode insertBlockAtPosition(BlockID parentID, CharCRDT content,int position) {
        return insertBlockAtPosition(parentID, content, position, null);
    }

    public BlockNode insertBlockAtPosition(BlockID parentID, CharCRDT content,
                                           int position, BlockID explicitID) {
        BlockID   normalizedParent = normalizeParentID(parentID);
        BlockNode parentNode       = getNode(normalizedParent);
        if (parentNode == null) parentNode = root;

        BlockID   id      = (explicitID != null) ? explicitID : generateID();
        BlockNode newNode = new BlockNode(id, normalizedParent, content);
        nodeMap.put(id, newNode);

        if (explicitID != null) {
            clock.advanceTo(explicitID.getClock());
        }

        List<BlockNode> children = parentNode.getChildren();
        int clampedPos = Math.max(0, Math.min(position, children.size()));
        children.add(clampedPos, newNode);
        return newNode;
    }

    public boolean mergeWithPrevious(BlockID blockID) { return mergeWithDirection(blockID, -1); }
    public boolean mergeWithNext(BlockID blockID)     { return mergeWithDirection(blockID, +1); }


    public void checkAndSplit_Merge(BlockID targetBlockID) {
        BlockNode targetNode = getNode(targetBlockID);
        if (isBlockEmpty(targetNode)) return;

        if (isSplitNeeded(targetNode)) {
            BlockNode newBlock = splitBlock(targetNode);
            // Recursively check the new block as well.
            if (newBlock != null) checkAndSplit_Merge(newBlock.getId());
        } else if (isMergeNeeded(targetNode)) {
            mergeBlock(targetNode);
        }
    }

    public void mergeBlock(BlockNode targetNode) {
        BlockNode siblingNode = findNearestLiveSiblingForMerge(targetNode);
        if (siblingNode != null && siblingNode.moveAllText(targetNode)) {
            siblingNode.setDeleted(true);
        }
    }


    public BlockNode moveBlock(BlockID blockID, BlockID afterBlockID) {
        BlockNode sourceNode = getNode(blockID);
        if (sourceNode == null | sourceNode.isDeleted()) return null;

        BlockNode oldParent = findParentOf(sourceNode);
        if (oldParent == null) oldParent = root;

        BlockNode newParent;
        int insertIdx;

        if (afterBlockID == null) {
            newParent  = root;
            insertIdx  = 0;
        } else {
            BlockNode afterNode = getNode(afterBlockID);
            if (afterNode == null | afterNode.isDeleted()) return null;

            BlockNode afterParent = findParentOf(afterNode);
            newParent = (afterParent != null) ? afterParent : root;

            List<BlockNode> afterSiblings = newParent.getChildren();
            int idx = afterSiblings.indexOf(afterNode);
            if (idx == -1) return null;
            insertIdx = idx + 1;
        }


        oldParent.getChildren().remove(sourceNode);


        List<BlockNode> siblings = newParent.getChildren();
        if (insertIdx > siblings.size()) insertIdx = siblings.size();


        siblings.add(insertIdx, sourceNode);

        return sourceNode;
    }



    public BlockNode copyBlock(BlockID sourceBlockID, BlockID afterBlockID) {
        BlockNode sourceNode = getNode(sourceBlockID);
        if (isBlockEmpty(sourceNode)) return null;


        BlockNode refNode   = (afterBlockID != null) ? getNode(afterBlockID) : null;
        BlockID   parentID  = (refNode != null && !refNode.isDeleted())
                ? refNode.getParentID()
                : ROOT_ID;
        BlockNode parentNode = getNode(parentID);
        if (parentNode == null) parentNode = root;


        CharCRDT  newContent = new CharCRDT(this.userid, this.clock);
        BlockNode newBlock   = createNode(parentNode.getId(), newContent);
        parentNode.getChildren().add(newBlock);


        CharID lastParentID = newContent.rootID;
        for (CharNode cn : sourceNode.getChars()) {
            CharNode inserted = newContent.insertNode(lastParentID, cn.getValue());
            if (inserted != null) {
                inserted.setBold(cn.isBold());
                inserted.setItalic(cn.isItalic());
                lastParentID = inserted.getID();
            }
        }

        return newBlock;
    }

    /*public List<BlockNode> importText(String text) {
        List<BlockNode> created = new ArrayList<>();
        if (text == null || text.isEmpty()) return created;

        CharCRDT  currentContent = new CharCRDT(this.userid, this.clock);
        BlockNode currentBlock   = createNode(null, currentContent);
        root.addChild(currentBlock);
        created.add(currentBlock);

        CharID lastID       = currentContent.rootID;
        int    linesInBlock = 1;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);

            if (ch == '\n') {
                linesInBlock++;
                if (linesInBlock > MAX_LINES) {
                    // Start a fresh block; don't insert the newline itself.
                    currentContent = new CharCRDT(this.userid, this.clock);
                    currentBlock   = createNode(null, currentContent);
                    root.addChild(currentBlock);
                    created.add(currentBlock);
                    lastID       = currentContent.rootID;
                    linesInBlock = 1;
                    continue;
                }
            }

            CharNode inserted = currentContent.insertNode(lastID, ch);
            if (inserted != null) lastID = inserted.getID();
        }

        return created;
    }*/

    // REPLACE THIS METHOD IN BlockCRDT.java
    public List<BlockNode> importText(String text) {
        List<BlockNode> created = new ArrayList<>();
        if (text == null || text.isEmpty()) return created;
        CharCRDT  currentContent = new CharCRDT(this.userid, this.clock);
        BlockNode currentBlock   = createNode(null, currentContent);
        root.addChild(currentBlock);
        created.add(currentBlock);
        CharID lastID       = currentContent.rootID;
        int    linesInBlock = 1;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);

            // THE FIX: Insert the character into the CRDT FIRST so newlines aren't lost
            CharNode inserted = currentContent.insertNode(lastID, ch);
            if (inserted != null) lastID = inserted.getID();

            // THEN check if we need to wrap the next text into a new block
            if (ch == '\n') {
                linesInBlock++;
                if (linesInBlock > MAX_LINES) {
                    currentContent = new CharCRDT(this.userid, this.clock);
                    currentBlock   = createNode(null, currentContent);
                    root.addChild(currentBlock);
                    created.add(currentBlock);
                    lastID       = currentContent.rootID;
                    linesInBlock = 1;
                }
            }
        }
        return created;
    }


    public BlockNode insertBlockAfterAnchor(BlockID anchorID, CharCRDT content) {
        return insertBlockAfterAnchor(anchorID, content, null);
    }

    public BlockNode insertBlockAfterAnchor(BlockID anchorID, CharCRDT content,
                                            BlockID explicitID) {
        List<BlockNode> raw = root.getChildren();

        int insertPos;
        if (anchorID == null) {

            insertPos = 0;
        } else {
            BlockNode anchorNode = nodeMap.get(normalizeParentID(anchorID));
            if (anchorNode == null) {
                insertPos = raw.size();
            } else {
                int rawIdx = raw.indexOf(anchorNode);
                insertPos = (rawIdx == -1) ? raw.size() : rawIdx + 1;
            }
        }

        BlockID   id      = (explicitID != null) ? explicitID : generateID();
        BlockID   normParent = ROOT_ID;
        BlockNode newNode = new BlockNode(id, normParent, content);
        nodeMap.put(id, newNode);

        if (explicitID != null) clock.advanceTo(explicitID.getClock());

        raw.add(insertPos, newNode);
        return newNode;
    }

    public BlockNode getBlock(BlockID id) { return getNode(id); }

    public BlockNode createNode(BlockID parentID, CharCRDT content) {
        BlockID   normalizedParentID = normalizeParentID(parentID);
        BlockID   id                 = generateID();
        BlockNode newNode            = new BlockNode(id, normalizedParentID, content);
        nodeMap.put(id, newNode);
        return newNode;
    }



    private BlockID generateID() { return new BlockID(userid, clock.tick()); }

    private BlockID normalizeParentID(BlockID parentID) {
        return (parentID == null) ? ROOT_ID : parentID;
    }

    private BlockNode getNode(BlockID id) {
        if (id == null) return root;
        return nodeMap.get(normalizeParentID(id));
    }

    // DFS, skips root sentinel and deleted nodes.
    // In BlockCRDT.java

    /*private void depthFirstTraversal(BlockNode startNode, List<BlockNode> result) {
        Deque<BlockNode> stack = new ArrayDeque<>();
        for (BlockNode child : startNode.getChildren()) stack.push(child);

        while (!stack.isEmpty()) {
            BlockNode node = stack.pop();
            if (!node.isDeleted()) result.add(node);
            // Push children in reverse to preserve order
            List<BlockNode> children = node.getChildren();
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(children.get(i));
            }
        }
    }

    private void traverseAllBlocks(BlockNode startNode, List<BlockNode> result) {
        Deque<BlockNode> stack = new ArrayDeque<>();
        for (BlockNode child : startNode.getChildren()) stack.push(child);

        while (!stack.isEmpty()) {
            BlockNode node = stack.pop();
            result.add(node);
            List<BlockNode> children = node.getChildren();
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(children.get(i));
            }
        }
    }*/

    // REPLACE THESE TWO METHODS ENTIRELY IN BlockCRDT.java
    private void depthFirstTraversal(BlockNode startNode, List<BlockNode> result) {
        Deque<BlockNode> stack = new ArrayDeque<>();

        // THE FIX: Push children in reverse order so the first block is processed first!
        List<BlockNode> rootChildren = startNode.getChildren();
        for (int i = rootChildren.size() - 1; i >= 0; i--) {
            stack.push(rootChildren.get(i));
        }

        while (!stack.isEmpty()) {
            BlockNode node = stack.pop();
            if (!node.isDeleted()) result.add(node);

            List<BlockNode> children = node.getChildren();
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(children.get(i));
            }
        }
    }

    private void traverseAllBlocks(BlockNode startNode, List<BlockNode> result) {
        Deque<BlockNode> stack = new ArrayDeque<>();

        // THE FIX: Push children in reverse order so the first block is processed first!
        List<BlockNode> rootChildren = startNode.getChildren();
        for (int i = rootChildren.size() - 1; i >= 0; i--) {
            stack.push(rootChildren.get(i));
        }

        while (!stack.isEmpty()) {
            BlockNode node = stack.pop();
            result.add(node);

            List<BlockNode> children = node.getChildren();
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(children.get(i));
            }
        }
    }

    private BlockNode findParentOf(BlockNode target) {
        if (target == null) return null;
        BlockID pid = target.getParentID();
        if (pid == null) return root;
        BlockNode parent = nodeMap.get(normalizeParentID(pid));
        return (parent != null) ? parent : root;
    }


    private boolean mergeWithDirection(BlockID blockID, int direction) {
        if (direction != -1 && direction != 1) return false;

        BlockNode targetNode = getNode(blockID);
        if (isBlockEmpty(targetNode)) return false;

        BlockNode siblingNode = findLiveSiblingInDirection(targetNode, direction);
        if (siblingNode == null) return false;

        BlockNode source      = (direction == -1) ? targetNode : siblingNode;
        BlockNode destination = (direction == -1) ? siblingNode : targetNode;

        if (source.moveAllText(destination)) {
            source.setDeleted(true);
            return true;
        }
        return false;
    }


    private BlockNode findLiveSiblingInDirection(BlockNode targetNode, int direction) {
        if (targetNode == null || (direction != -1 && direction != 1)) return null;

        BlockNode parent = findParentOf(targetNode);
        if (parent == null) parent = root;

        List<BlockNode> siblings = parent.getChildren();
        int idx = siblings.indexOf(targetNode);
        if (idx == -1) return null;

        return findActiveSibling(siblings, idx + direction, direction);
    }

    private BlockNode findNearestLiveSiblingForMerge(BlockNode targetNode) {
        if (targetNode == null) return null;

        BlockNode parent = findParentOf(targetNode);
        if (parent == null) parent = root;

        List<BlockNode> siblings = parent.getChildren();
        int idx = siblings.indexOf(targetNode);
        if (idx == -1) return null;


        BlockNode prev = findActiveSibling(siblings, idx - 1, -1);
        return (prev != null) ? prev : findActiveSibling(siblings, idx + 1, +1);
    }

    private BlockNode findActiveSibling(List<BlockNode> siblings, int startIndex, int step) {
        for (int i = startIndex; i >= 0 && i < siblings.size(); i += step) {
            if (!siblings.get(i).isDeleted()) return siblings.get(i);
        }
        return null;
    }


    private BlockNode splitBlock(BlockNode sourceNode) {
        BlockNode targetNode = createSiblingBlock(sourceNode);
        if (targetNode == null) return null;
        boolean ok = sourceNode.moveTextFromLine(targetNode, MAX_LINES / 2);
        return ok ? targetNode : null;
    }


    private BlockNode splitBlock(BlockNode sourceNode, int localIndex) {
        BlockNode targetNode = createSiblingBlock(sourceNode);
        if (targetNode == null) return null;
        boolean ok = sourceNode.moveTextFromIndex(targetNode, localIndex);
        return ok ? targetNode : null;
    }

    private BlockNode createSiblingBlock(BlockNode sourceNode) {
        if (sourceNode == null) return null;

        BlockID   parentID   = sourceNode.getParentID();
        BlockNode parentNode = getNode(parentID);
        if (parentNode == null) parentNode = root;

        // Insert the new sibling IMMEDIATELY AFTER sourceNode
        BlockNode newNode = createNode(parentID, new CharCRDT(this.userid, this.clock));
        List<BlockNode> siblings = parentNode.getChildren();
        int sourceIdx = siblings.indexOf(sourceNode);
        int insertAt  = (sourceIdx == -1) ? siblings.size() : sourceIdx + 1;
        siblings.add(insertAt, newNode);
        return newNode;
    }

    private boolean isSplitNeeded(BlockNode node) { return node.getLineCount() > MAX_LINES; }
    private boolean isMergeNeeded(BlockNode node) { return node.getLineCount() < MIN_LINES; }

    private boolean isBlockEmpty(BlockNode node) {
        return node == null || node.isDeleted() || node.getContent() == null;
    }
    public BlockNode insertBlockWithID(BlockID blockID, BlockID parentID, CharCRDT content) {
        BlockID normalizedParent = normalizeParentID(parentID);
        BlockNode parentNode = getNode(normalizedParent);
        if (parentNode == null) parentNode = root;

        parentNode.getChildren().removeIf(child -> child.getId().equals(blockID));
        BlockNode newNode = new BlockNode(blockID, normalizedParent, content);
        nodeMap.put(blockID, newNode);
        parentNode.getChildren().add(newNode);
        clock.advanceTo(blockID.getClock()); // prevent future ID collision
        return newNode;
    }

    // THE FIX: Exposed full block memory state for the Serializer
    public List<BlockNode> getAllNodesIncludingDeleted() {
        List<BlockNode> result = new ArrayList<>();
        traverseAllBlocks(root, result);
        return result;
    }

    public List<BlockNode> getRootChildren() {
        return root.getChildren();
    }

        public void softDeleteNode(BlockID id) {
        BlockNode node = getNode(id);
        if (node == null || node.isDeleted()) return;
        // Mark all chars deleted so they never leak through fallbacks
        if (node.getContent() != null) {
            for (CharNode cn : node.getContent().getOrderedNodes()) {
                cn.SetDeleted(true);
            }
        }
        node.setDeleted(true);

    }
}