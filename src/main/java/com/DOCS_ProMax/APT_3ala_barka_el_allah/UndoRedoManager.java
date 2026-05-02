package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-user undo / redo stack manager.
 *
 * Member 2 – Undo / Redo System
 *
 * Rules:
 *  - Each user has an independent undo stack (max 10) and redo stack (max 10).
 *  - When a new edit is pushed, the user's redo stack is cleared.
 *  - undo() returns the inverse of the last operation so the caller can apply
 *    and broadcast it.
 *  - redo() replays the last undone operation.
 *
 * Inverse logic:
 *  - INSERT_CHAR  →  DELETE_CHAR  (targeting the same CharID)
 *  - DELETE_CHAR  →  INSERT_CHAR  (re-inserting with the original parent and value)
 *  - FORMAT_CHAR  →  FORMAT_CHAR  (toggling back to the previous bold/italic state)
 */
public class UndoRedoManager {

    private static final int MAX_STACK_SIZE = 10;

    // username → stack of operations the user can undo
    private final Map<String, Deque<Operations>> undoStacks = new HashMap<>();
    // username → stack of operations the user can redo
    private final Map<String, Deque<Operations>> redoStacks = new HashMap<>();

    // -----------------------------------------------------------------------
    // Push a new operation (called after every local edit)
    // -----------------------------------------------------------------------

    /**
     * Records {@code op} so it can later be undone.
     * Clears the user's redo stack because a new edit invalidates the redo history.
     */
    public void push(String username, Operations op) {
        Deque<Operations> undoStack = getOrCreate(undoStacks, username);
        Deque<Operations> redoStack = getOrCreate(redoStacks, username);

        undoStack.push(op);
        if (undoStack.size() > MAX_STACK_SIZE) {
            // Remove the oldest (bottom) entry
            ((ArrayDeque<Operations>) undoStack).pollLast();
        }
        redoStack.clear();
    }

    // -----------------------------------------------------------------------
    // Undo
    // -----------------------------------------------------------------------

    /**
     * Pops the last operation for {@code username}, creates its inverse,
     * pushes the original onto the redo stack, and returns the inverse
     * so the caller can apply + broadcast it.
     *
     * @return the inverse operation to apply, or {@code null} if the stack is empty.
     */
    public Operations undo(String username) {
        Deque<Operations> undoStack = getOrCreate(undoStacks, username);
        Deque<Operations> redoStack = getOrCreate(redoStacks, username);

        if (undoStack.isEmpty()) return null;

        Operations original = undoStack.pop();
        redoStack.push(original);
        if (redoStack.size() > MAX_STACK_SIZE) {
            ((ArrayDeque<Operations>) redoStack).pollLast();
        }

        return buildInverse(original, username);
    }

    // -----------------------------------------------------------------------
    // Redo
    // -----------------------------------------------------------------------

    /**
     * Pops the last undone operation for {@code username}, pushes it back onto
     * the undo stack, and returns it so the caller can re-apply + broadcast it.
     *
     * @return the operation to re-apply, or {@code null} if the stack is empty.
     */
    public Operations redo(String username) {
        /*Deque<Operations> undoStack = getOrCreate(undoStacks, username);
        Deque<Operations> redoStack = getOrCreate(redoStacks, username);

        if (redoStack.isEmpty()) return null;

        Operations op = redoStack.pop();
        undoStack.push(op);
        if (undoStack.size() > MAX_STACK_SIZE) {
            ((ArrayDeque<Operations>) undoStack).pollLast();
        }

        // For redo we simply re-apply the ORIGINAL operation (not an inverse)
        return copyWithUsername(op, username);*/
        Deque<Operations> undoStack = getOrCreate(undoStacks, username);
        Deque<Operations> redoStack = getOrCreate(redoStacks, username);

        if (redoStack.isEmpty()) return null;

        Operations original = redoStack.pop();
        undoStack.push(original);

        if (undoStack.size() > MAX_STACK_SIZE) {
            ((ArrayDeque<Operations>) undoStack).pollLast();
        }

        Operations redo = new Operations();
        redo.sessionCode = original.sessionCode;
        redo.username = username;

        if ("INSERT_CHAR".equals(original.type)) {
            redo.type = "UNDELETE_CHAR";
            redo.charUser = original.charUser;
            redo.charClock = original.charClock;
            return redo;
        }

        return copyWithUsername(original, username);
    }

    // -----------------------------------------------------------------------
    // Stack size queries (useful for testing / UI)
    // -----------------------------------------------------------------------

    public boolean canUndo(String username) {
        return !getOrCreate(undoStacks, username).isEmpty();
    }

    public boolean canRedo(String username) {
        return !getOrCreate(redoStacks, username).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Builds the exact inverse of {@code original}:
     * <ul>
     *   <li>INSERT_CHAR → DELETE_CHAR targeting the same CharID</li>
     *   <li>DELETE_CHAR → INSERT_CHAR re-inserting the same char at the same parent</li>
     *   <li>FORMAT_CHAR → FORMAT_CHAR toggling both flags back</li>
     * </ul>
     */
    private Operations buildInverse(Operations original, String username) {
        Operations inv = new Operations();
        inv.sessionCode = original.sessionCode;
        inv.username    = username;

        switch (original.type) {
            case "INSERT_CHAR" -> {
                // Undo an insert = delete the inserted char
                inv.type       = "DELETE_CHAR";
                inv.charUser   = original.charUser;
                inv.charClock  = original.charClock;
            }
            case "DELETE_CHAR" -> {
                // Undo a delete = re-insert the char at its original parent
                inv.type        = "INSERT_CHAR";
                inv.charUser    = original.charUser;
                inv.charClock   = original.charClock;
                inv.parentUser  = original.parentUser;
                inv.parentClock = original.parentClock;
                inv.value       = original.value;
                inv.isBold      = original.isBold;
                inv.isItalic    = original.isItalic;
            }
            case "FORMAT_CHAR" -> {
                // Undo a format = toggle both flags back to their previous state
                inv.type       = "FORMAT_CHAR";
                inv.charUser   = original.charUser;
                inv.charClock  = original.charClock;
                inv.isBold     = !original.isBold;
                inv.isItalic   = !original.isItalic;
            }
            default -> {
                // For any other type we just echo the original
                return copyWithUsername(original, username);
            }
        }
        return inv;
    }

    private Operations copyWithUsername(Operations src, String username) {
        Operations copy    = new Operations();
        copy.type          = src.type;
        copy.sessionCode   = src.sessionCode;
        copy.username      = username;
        copy.charUser      = src.charUser;
        copy.charClock     = src.charClock;
        copy.parentUser    = src.parentUser;
        copy.parentClock   = src.parentClock;
        copy.value         = src.value;
        copy.isBold        = src.isBold;
        copy.isItalic      = src.isItalic;
        return copy;
    }

    private Deque<Operations> getOrCreate(Map<String, Deque<Operations>> map, String username) {
        return map.computeIfAbsent(username, k -> new ArrayDeque<>());
    }
}
