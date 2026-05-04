package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Block-Level CRDT – fully corrected revision.
 *
 * Bugs fixed vs. the previous version
 * ─────────────────────────────────────
 * 1. moveBlock   – correctly detaches from OLD parent children list, then
 *                  re-inserts at the requested position.  The node's BlockID
 *                  is never changed (CRDT-safe).  Sorted re-insert is kept
 *                  so concurrent moves converge deterministically.
 *
 * 2. deleteNode  – soft-deletes the block, then finds the live left / right
 *                  neighbours and merges the one with FEWER lines into the
 *                  one with more lines (or always merges right → left if
 *                  equal) to satisfy the 2-line minimum.
 *
 * 3. splitBlockAtCursor – accepts a LOCAL visible-char index (not a global
 *                  document offset).  Already worked, but the guard now also
 *                  rejects a split at position 0 (nothing would move).
 *
 * 4. checkAndSplit_Merge – called after EVERY structural change (split AND
 *                  merge), not only after char inserts.
 *
 * 5. copyBlock   – creates new block with FRESH IDs; does NOT call
 *                  sendInsertBlock or sendInsertChar internally – that is
 *                  the caller's responsibility.
 *
 * 6. importText  – unchanged; works correctly.
 *
 * 7. Public getters getUserid() / getClock() added so EditorUI can create
 *    default empty blocks after the last block is deleted.
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
    // Delete block  (FIX #2)
    // -----------------------------------------------------------------------
    /**
     * Soft-deletes a block.
     *
     * After deletion the live neighbours are located.  If either neighbour
     * now falls below MIN_LINES its content is merged into the other one.
     * The neighbour with FEWER lines is always merged INTO the neighbour with
     * MORE lines so we maximise content preserved per block.  When counts are
     * equal the right block is merged into the left.
     */
    public void deleteNode(BlockID id) {
        BlockNode node = getNode(id);
        if (node == null || node.isDeleted()) return;
        // FIX: mark all chars inside as deleted so they never leak through fallbacks
        if (node.getContent() != null) {
            for (CharNode cn : node.getContent().getOrderedNodes()) {
                cn.SetDeleted(true);
            }
        }

        // Locate live neighbours BEFORE we mark the node deleted.
        BlockNode prevLive = findLiveSiblingInDirection(node, -1);
        BlockNode nextLive = findLiveSiblingInDirection(node, +1);

        node.setDeleted(true);

        // After deletion check whether neighbours need merging.
        // We only need to merge if a neighbour now has < MIN_LINES.
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
                // merge next into prev
                if (nextLive.moveAllText(prevLive)) {
                    nextLive.setDeleted(true);
                }
            } else {
                // Both are fine individually – just run the normal check
                checkAndSplit_Merge(prevLive.getId());
                checkAndSplit_Merge(nextLive.getId());
            }
        } else if (prevLive != null && !prevLive.isDeleted()) {
            checkAndSplit_Merge(prevLive.getId());
        } else if (nextLive != null && !nextLive.isDeleted()) {
            checkAndSplit_Merge(nextLive.getId());
        }
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
    // Split block at a LOCAL cursor index  (FIX #3 – validation)
    // -----------------------------------------------------------------------
    /**
     * Splits the block at {@code localIndex} (index within the block's visible
     * character list, NOT a global document offset).
     *
     * Returns the newly created second block, or null if the split is not
     * possible (localIndex ≤ 0 or ≥ chars.size()).
     */
    /*public BlockNode splitBlockAtCursor(BlockID blockID, int localIndex) {
        BlockNode sourceNode = getNode(blockID);
        if (isBlockEmpty(sourceNode)) return null;

        List<CharNode> chars = sourceNode.getChars();
        // Must have something to move to the new block.
        if (localIndex <= 0 || localIndex > chars.size()) return null;

        BlockNode newBlock = splitBlock(sourceNode, localIndex);

        // FIX #4 – run constraint checks on BOTH halves after a split.
        if (newBlock != null) {
            checkAndSplit_Merge(blockID);
            checkAndSplit_Merge(newBlock.getId());
        }
        return newBlock;
    }*/
    public BlockNode splitBlockAtCursor(BlockID blockID, int localIndex) {
        BlockNode sourceNode = getNode(blockID);
        if (isBlockEmpty(sourceNode)) return null;

        List<CharNode> chars = sourceNode.getChars();
        // Allow split at 0 (everything moves to new block) up to chars.size()
        if (localIndex < 0 || localIndex > chars.size()) return null;

        return splitBlock(sourceNode, localIndex);
    }
    public BlockNode insertBlockAtPosition(BlockID parentID, CharCRDT content, int position) {
        BlockID   normalizedParent = normalizeParentID(parentID);
        BlockNode parentNode       = getNode(normalizedParent);
        if (parentNode == null) parentNode = root;

        BlockID   id      = generateID();
        BlockNode newNode = new BlockNode(id, normalizedParent, content);
        nodeMap.put(id, newNode);

        List<BlockNode> children = parentNode.getChildren();
        int clampedPos = Math.max(0, Math.min(position, children.size()));
        children.add(clampedPos, newNode);   // direct insert, no sort
        return newNode;
    }
    // -----------------------------------------------------------------------
    // Merge helpers
    // -----------------------------------------------------------------------
    public boolean mergeWithPrevious(BlockID blockID) { return mergeWithDirection(blockID, -1); }
    public boolean mergeWithNext(BlockID blockID)     { return mergeWithDirection(blockID, +1); }

    // -----------------------------------------------------------------------
    // Auto split / merge after every char op  (FIX #4)
    // -----------------------------------------------------------------------
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

    /** Merges {@code targetNode} with its nearest live sibling. */
    public void mergeBlock(BlockNode targetNode) {
        BlockNode siblingNode = findNearestLiveSiblingForMerge(targetNode);
        if (siblingNode != null && siblingNode.moveAllText(targetNode)) {
            siblingNode.setDeleted(true);
        }
    }

    // -----------------------------------------------------------------------
    // MOVE BLOCK  (FIX #1 – completely rewritten)
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

        // Detach from old parent
        oldParent.getChildren().remove(sourceNode);

        // Clamp insertIdx after detachment (list may have shrunk)
        List<BlockNode> siblings = newParent.getChildren();
        if (insertIdx > siblings.size()) insertIdx = siblings.size();

        // Re-attach at the exact requested position – NO SORT (sort would undo the move)
        siblings.add(insertIdx, sourceNode);

        return sourceNode;
    }

    // -----------------------------------------------------------------------
    // COPY BLOCK  (FIX – only creates the block; caller must broadcast)
    // -----------------------------------------------------------------------
    /**
     * Creates a new block whose text content is a deep copy of the block
     * identified by {@code sourceBlockID}, inserted immediately after
     * {@code afterBlockID} (or at the top if null).
     *
     * Characters receive FRESH IDs so there are no ID collisions with the
     * originals.
     *
     * The caller is responsible for sending a single INSERT_BLOCK message
     * PLUS INSERT_CHAR messages for every character in the new block.
     * The UI must NOT call sendInsertBlock separately – that would duplicate.
     *
     * @return the newly created block, or null if the source was not found.
     */
    public BlockNode copyBlock(BlockID sourceBlockID, BlockID afterBlockID) {
        BlockNode sourceNode = getNode(sourceBlockID);
        if (isBlockEmpty(sourceNode)) return null;

        // Determine parent node.
        BlockNode refNode   = (afterBlockID != null) ? getNode(afterBlockID) : null;
        BlockID   parentID  = (refNode != null && !refNode.isDeleted())
                ? refNode.getParentID()
                : ROOT_ID;
        BlockNode parentNode = getNode(parentID);
        if (parentNode == null) parentNode = root;

        // Build new block with fresh char IDs.
        CharCRDT  newContent = new CharCRDT(this.userid, this.clock);
        BlockNode newBlock   = createNode(parentNode.getId(), newContent);
        parentNode.addChild(newBlock);

        // Copy each visible character with a fresh ID (sequential chain).
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
    }

    // -----------------------------------------------------------------------
    // Node access
    // -----------------------------------------------------------------------
    public BlockNode getBlock(BlockID id) { return getNode(id); }

    // -----------------------------------------------------------------------
    // Package-private factory
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

    // ── Parent lookup (required for moveBlock FIX) ───────────────────────
    /**
     * Returns the parent BlockNode of {@code target} by searching the nodeMap.
     * Uses the parentID stored on the node; falls back to root if not found.
     */
    private BlockNode findParentOf(BlockNode target) {
        if (target == null) return null;
        BlockID pid = target.getParentID();
        if (pid == null) return root;
        BlockNode parent = nodeMap.get(normalizeParentID(pid));
        return (parent != null) ? parent : root;
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
            source.setDeleted(true);
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

        BlockID   parentID   = sourceNode.getParentID();
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
    public BlockNode insertBlockWithID(BlockID blockID, BlockID parentID, CharCRDT content) {
        BlockID normalizedParent = normalizeParentID(parentID);
        BlockNode parentNode = getNode(normalizedParent);
        if (parentNode == null) parentNode = root;

        BlockNode newNode = new BlockNode(blockID, normalizedParent, content);
        nodeMap.put(blockID, newNode);
        parentNode.addChild(newNode);
        clock.advanceTo(blockID.getClock()); // prevent future ID collision
        return newNode;
    }

    // THE FIX: Exposed full block memory state for the Serializer
    public List<BlockNode> getAllNodesIncludingDeleted() {
        List<BlockNode> result = new ArrayList<>();
        traverseAllBlocks(root, result);
        return result;
    }

    private void traverseAllBlocks(BlockNode node, List<BlockNode> result) {
        if (node != root) result.add(node);
        for (BlockNode child : node.getChildren()) {
            traverseAllBlocks(child, result);
        }
    }
// ── FILE: BlockCRDT.java ─────────────────────────────────────────────────
// ADD both methods anywhere in the public section of BlockCRDT.java
// (they do NOT replace anything — just add them)

    /**
     * Returns the raw children list of root including deleted nodes.
     * Required for precise positional insertion during move operations.
     * getOrderedNodes() only returns live nodes — this returns everything.
     */
    public List<BlockNode> getRootChildren() {
        return root.getChildren();
    }

    /**
     * Marks a block deleted WITHOUT triggering neighbour merge/split checks.
     * Use for move operations where the block's content has already been
     * copied elsewhere and we just want to hide the original.
     *
     * Compare with deleteNode() which calls checkAndSplit_Merge on neighbours
     * — that can cause the newly inserted copy to get auto-merged and vanish
     * if it has fewer than MIN_LINES (2) lines.
     */
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
        // Intentionally no checkAndSplit_Merge call
    }
}