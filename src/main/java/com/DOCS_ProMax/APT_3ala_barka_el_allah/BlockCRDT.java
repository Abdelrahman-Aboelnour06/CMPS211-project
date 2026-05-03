package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Block-Level CRDT.
 *
 * Fixed in this revision:
 *  - moveBlock: correctly detaches node from old parent and reinserts at target
 *    position without altering the BlockID (CRDT-safe).
 *  - deleteNode: after soft-deleting a block it triggers checkAndSplit_Merge on
 *    both the previous and next live sibling so the 2-line minimum is enforced.
 *  - splitBlockAtCursor: accepts a LOCAL index (chars within the block), not a
 *    global document offset.
 *  - checkAndSplit_Merge: now also called after every split.
 *  - getOrderedBlocks: returns only live (non-deleted) blocks in document order.
 *  - copyBlock: creates a new block with fresh IDs; does NOT send redundant inserts.
 *  - importText: splits large text into ≤10-line blocks automatically.
 *  - getUserid / getClock: public getters so EditorUI can create default blocks.
 */
public class BlockCRDT {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------
    private static final int MAX_LINES = 10;
    private static final int MIN_LINES = 2;
    private static final BlockID ROOT_ID = new BlockID(-1, -1);

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------
    private final BlockNode root;
    private int userid;
    private Clock clock;
    private final Map<BlockID, BlockNode> nodeMap;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------
    public BlockCRDT(int userid, Clock clock) {
        this.userid  = userid;
        this.clock   = clock;
        this.nodeMap = new HashMap<>();
        this.root    = new BlockNode(ROOT_ID, null, null);
        nodeMap.put(ROOT_ID, root);
    }

    // -----------------------------------------------------------------------
    // Public getters (needed by EditorUI / Client)
    // -----------------------------------------------------------------------
    public int   getUserid() { return userid; }
    public Clock getClock()  { return clock;  }

    // -----------------------------------------------------------------------
    // Block insert helpers
    // -----------------------------------------------------------------------
    public void insertBlock(BlockID parentID, CharCRDT content) {
        BlockNode parentNode = getNode(parentID);
        if (parentNode != null) {
            BlockNode newNode = createNode(parentID, content);
            parentNode.addChild(newNode);
        }
    }

    /** Appends a new top-level block (child of root) and returns it. */
    public BlockNode insertTopLevelBlock(CharCRDT content) {
        BlockNode newNode = createNode(null, content);
        root.addChild(newNode);
        return newNode;
    }

    // -----------------------------------------------------------------------
    // Delete block
    // -----------------------------------------------------------------------
    /**
     * Soft-deletes a block.  After deletion the immediate live neighbours are
     * checked for the 2-line minimum so any under-sized survivor gets merged.
     */
    public void deleteNode(BlockID id) {
        BlockNode node = getNode(id);
        if (node == null || node.isDeleted()) return;

        // Find live neighbours BEFORE we delete so we can check them after.
        BlockNode prevLive = findLiveSiblingInDirection(node, -1);
        BlockNode nextLive = findLiveSiblingInDirection(node, +1);

        node.setDeleted(true);

        // Check neighbours for the minimum-line constraint.
        if (prevLive != null && !prevLive.isDeleted()) checkAndSplit_Merge(prevLive.getId());
        if (nextLive != null && !nextLive.isDeleted()) checkAndSplit_Merge(nextLive.getId());
    }

    // -----------------------------------------------------------------------
    // Ordered traversal
    // -----------------------------------------------------------------------
    /** Returns all live (non-deleted) blocks in document order (DFS). */
    public List<BlockNode> getOrderedNodes() {
        List<BlockNode> result = new ArrayList<>();
        depthFirstTraversal(root, result);
        return result;
    }

    // -----------------------------------------------------------------------
    // Character operations inside a block
    // -----------------------------------------------------------------------
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

    // -----------------------------------------------------------------------
    // Split block at a LOCAL cursor index
    // -----------------------------------------------------------------------
    /**
     * Splits the block at {@code localIndex} (index within the block's visible
     * character list, NOT a global document offset).  Returns the newly created
     * second block, or null if the split is not possible.
     */
    public BlockNode splitBlockAtCursor(BlockID blockID, int localIndex) {
        BlockNode sourceNode = getNode(blockID);
        if (isBlockEmpty(sourceNode)) return null;

        List<CharNode> chars = sourceNode.getChars();
        if (localIndex < 0 || localIndex > chars.size()) return null;

        BlockNode newBlock = splitBlock(sourceNode, localIndex);

        // After splitting, check both halves for the line constraints.
        if (newBlock != null) {
            checkAndSplit_Merge(blockID);
            checkAndSplit_Merge(newBlock.getId());
        }
        return newBlock;
    }

    // -----------------------------------------------------------------------
    // Merge helpers
    // -----------------------------------------------------------------------
    public boolean mergeWithPrevious(BlockID blockID) { return mergeWithDirection(blockID, -1); }
    public boolean mergeWithNext(BlockID blockID)     { return mergeWithDirection(blockID, +1); }

    // -----------------------------------------------------------------------
    // Auto split / merge after every char op
    // -----------------------------------------------------------------------
    public void checkAndSplit_Merge(BlockID targetBlockID) {
        BlockNode targetNode = getNode(targetBlockID);
        if (isBlockEmpty(targetNode)) return;

        if (isSplitNeeded(targetNode)) {
            BlockNode newBlock = splitBlock(targetNode);
            // After an automatic mid-block split, check the new block too.
            if (newBlock != null) checkAndSplit_Merge(newBlock.getId());
        } else if (isMergeNeeded(targetNode)) {
            mergeBlock(targetNode);
        }
    }

    /** Merges {@code targetNode} with its nearest live sibling. */
    public void mergeBlock(BlockNode targetNode) {
        BlockNode siblingNode = findNearestLiveSiblingForMerge(targetNode);
        if (siblingNode != null && siblingNode.moveAllText(targetNode)) {
            deleteNode(siblingNode.getId());
        }
    }

    // -----------------------------------------------------------------------
    // MOVE BLOCK (fixed)
    // -----------------------------------------------------------------------
    /**
     * Moves the block identified by {@code blockID} so that it appears
     * immediately AFTER the block identified by {@code afterBlockID}.
     * Pass {@code null} for {@code afterBlockID} to move to the very top
     * (first child of root).
     *
     * The BlockID is preserved – this is CRDT-safe because the identity of the
     * node does not change, only its position in the children list.
     *
     * @return the moved node, or null if the move failed.
     */
    public BlockNode moveBlock(BlockID blockID, BlockID afterBlockID) {
        BlockNode sourceNode = getNode(blockID);
        if (sourceNode == null || sourceNode.isDeleted()) return null;

        // Locate the source node's current parent.
        BlockNode oldParent = getNode(sourceNode.getParentID());
        if (oldParent == null) oldParent = root;

        // Determine new parent and insertion position.
        BlockNode newParent;
        int insertIdx;

        if (afterBlockID == null) {
            // Move to very top of root's children.
            newParent  = root;
            insertIdx  = 0;
        } else {
            BlockNode afterNode = getNode(afterBlockID);
            if (afterNode == null) return null;

            // The new parent is the same as afterNode's parent.
            BlockNode afterParent = getNode(afterNode.getParentID());
            newParent = (afterParent != null) ? afterParent : root;

            int idx = newParent.getChildren().indexOf(afterNode);
            if (idx == -1) return null;
            insertIdx = idx + 1;
        }

        // Detach from old parent (do NOT alter the nodeMap – same ID).
        oldParent.getChildren().remove(sourceNode);

        // Re-attach at new position.
        // We bypass addChild (which re-sorts by ID) and insert directly so the
        // user-chosen position is respected.  After insertion we still keep the
        // list sorted by ID so concurrent moves converge deterministically.
        List<BlockNode> siblings = newParent.getChildren();
        // Guard: clamp insertIdx in case detach changed list size.
        if (insertIdx > siblings.size()) insertIdx = siblings.size();
        siblings.add(insertIdx, sourceNode);

        // Re-sort so CRDT ordering is maintained (identical to addChild logic).
        siblings.sort(Comparator.comparing(BlockNode::getId));

        return sourceNode;
    }

    // -----------------------------------------------------------------------
    // COPY BLOCK (fixed – only creates the new block; callers must broadcast)
    // -----------------------------------------------------------------------
    /**
     * Creates a new block whose text content is a deep copy of the block
     * identified by {@code sourceBlockID}, inserted immediately after
     * {@code afterBlockID} (or at the top if null).
     *
     * Characters receive FRESH IDs generated by this user's clock so there is
     * no ID collision with the originals.
     *
     * The caller is responsible for sending a single INSERT_BLOCK message PLUS
     * INSERT_CHAR messages for each character in the new block.  The UI must
     * NOT also call sendInsertBlock separately – that would duplicate the block.
     *
     * @return the newly created block, or null if the source was not found.
     */
    public BlockNode copyBlock(BlockID sourceBlockID, BlockID afterBlockID) {
        BlockNode sourceNode = getNode(sourceBlockID);
        if (isBlockEmpty(sourceNode)) return null;

        // Determine parent.
        BlockNode refNode  = (afterBlockID != null) ? getNode(afterBlockID) : null;
        BlockID   parentID = (refNode != null && !refNode.isDeleted())
                ? refNode.getParentID()
                : ROOT_ID;
        BlockNode parentNode = getNode(parentID);
        if (parentNode == null) parentNode = root;

        // Build new block with fresh char IDs.
        CharCRDT  newContent = new CharCRDT(this.userid, this.clock);
        BlockNode newBlock   = createNode(parentNode.getId(), newContent);
        parentNode.addChild(newBlock);

        // Copy each visible character with a fresh ID (sequential, not by parent chain).
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

    // -----------------------------------------------------------------------
    // IMPORT TEXT (splits into ≤10-line blocks)
    // -----------------------------------------------------------------------
    /**
     * Inserts the given text into the document as one or more top-level blocks,
     * respecting the 10-line-per-block maximum.
     *
     * @param text the plain text (may contain '\n' line separators)
     * @return ordered list of newly created blocks (never empty)
     */
    public List<BlockNode> importText(String text) {
        List<BlockNode> created = new ArrayList<>();
        if (text == null || text.isEmpty()) return created;

        CharCRDT  currentContent = new CharCRDT(this.userid, this.clock);
        BlockNode currentBlock   = createNode(null, currentContent);
        root.addChild(currentBlock);
        created.add(currentBlock);

        CharID lastID      = currentContent.rootID;
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
    }

    // -----------------------------------------------------------------------
    // Node access
    // -----------------------------------------------------------------------
    public BlockNode getBlock(BlockID id) { return getNode(id); }

    // -----------------------------------------------------------------------
    // Package-private factory (used by inner helpers and EditorUI)
    // -----------------------------------------------------------------------
    public BlockNode createNode(BlockID parentID, CharCRDT content) {
        BlockID   normalizedParentID = normalizeParentID(parentID);
        BlockID   id                 = generateID();
        BlockNode newNode            = new BlockNode(id, normalizedParentID, content);
        nodeMap.put(id, newNode);
        return newNode;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Private helpers
    // ═══════════════════════════════════════════════════════════════════════

    private BlockID generateID() { return new BlockID(userid, clock.tick()); }

    private BlockID normalizeParentID(BlockID parentID) {
        return (parentID == null) ? ROOT_ID : parentID;
    }

    private BlockNode getNode(BlockID id) {
        if (id == null) return root;
        return nodeMap.get(normalizeParentID(id));
    }

    // DFS, skips root sentinel and deleted nodes.
    private void depthFirstTraversal(BlockNode node, List<BlockNode> result) {
        if (node != root && !node.isDeleted()) result.add(node);
        for (BlockNode child : node.getChildren()) depthFirstTraversal(child, result);
    }

    // ── Merge direction helpers ──────────────────────────────────────────────

    private boolean mergeWithDirection(BlockID blockID, int direction) {
        if (direction != -1 && direction != 1) return false;

        BlockNode targetNode = getNode(blockID);
        if (isBlockEmpty(targetNode)) return false;

        BlockNode siblingNode = findLiveSiblingInDirection(targetNode, direction);
        if (siblingNode == null) return false;

        BlockNode source      = (direction == -1) ? targetNode : siblingNode;
        BlockNode destination = (direction == -1) ? siblingNode : targetNode;

        if (source.moveAllText(destination)) {
            deleteNode(source.getId());
            return true;
        }
        return false;
    }

    /**
     * Returns the nearest live (non-deleted) sibling of {@code targetNode}
     * in the given direction (+1 = next, -1 = previous).
     */
    private BlockNode findLiveSiblingInDirection(BlockNode targetNode, int direction) {
        if (targetNode == null || (direction != -1 && direction != 1)) return null;

        BlockNode parent = getNode(targetNode.getParentID());
        if (parent == null) parent = root;

        List<BlockNode> siblings = parent.getChildren();
        int idx = siblings.indexOf(targetNode);
        if (idx == -1) return null;

        return findActiveSibling(siblings, idx + direction, direction);
    }

    private BlockNode findNearestLiveSiblingForMerge(BlockNode targetNode) {
        if (targetNode == null) return null;

        BlockNode parent = getNode(targetNode.getParentID());
        if (parent == null) parent = root;

        List<BlockNode> siblings = parent.getChildren();
        int idx = siblings.indexOf(targetNode);
        if (idx == -1) return null;

        // Prefer the previous sibling; fall back to next.
        BlockNode prev = findActiveSibling(siblings, idx - 1, -1);
        return (prev != null) ? prev : findActiveSibling(siblings, idx + 1, +1);
    }

    private BlockNode findActiveSibling(List<BlockNode> siblings, int startIndex, int step) {
        for (int i = startIndex; i >= 0 && i < siblings.size(); i += step) {
            if (!siblings.get(i).isDeleted()) return siblings.get(i);
        }
        return null;
    }

    // ── Split helpers ────────────────────────────────────────────────────────

    /** Automatic mid-block split (triggered by checkAndSplit_Merge). */
    private BlockNode splitBlock(BlockNode sourceNode) {
        BlockNode targetNode = createSiblingBlock(sourceNode);
        if (targetNode == null) return null;
        boolean ok = sourceNode.moveTextFromLine(targetNode, MAX_LINES / 2);
        return ok ? targetNode : null;
    }

    /** Manual split at a LOCAL character index. */
    private BlockNode splitBlock(BlockNode sourceNode, int localIndex) {
        BlockNode targetNode = createSiblingBlock(sourceNode);
        if (targetNode == null) return null;
        boolean ok = sourceNode.moveTextFromIndex(targetNode, localIndex);
        return ok ? targetNode : null;
    }

    private BlockNode createSiblingBlock(BlockNode sourceNode) {
        if (sourceNode == null) return null;

        BlockID   parentID  = sourceNode.getParentID();
        BlockNode parentNode = getNode(parentID);
        if (parentNode == null) parentNode = root;

        BlockNode newNode = createNode(parentID, new CharCRDT(this.userid, this.clock));
        parentNode.addChild(newNode);
        return newNode;
    }

    // ── Constraint checks ────────────────────────────────────────────────────

    private boolean isSplitNeeded(BlockNode node) { return node.getLineCount() > MAX_LINES; }
    private boolean isMergeNeeded(BlockNode node) { return node.getLineCount() < MIN_LINES; }

    private boolean isBlockEmpty(BlockNode node) {
        return node == null || node.isDeleted() || node.getContent() == null;
    }
}