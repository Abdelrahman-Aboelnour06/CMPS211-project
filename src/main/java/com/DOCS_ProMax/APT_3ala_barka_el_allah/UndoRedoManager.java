package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-user AND per-session undo/redo stack manager.
 *
 * Member 2 – Undo/Redo System
 *
 * Rules:
 *  - Each user has an independent undo stack (max 10) and redo stack (max 10).
 *  - A shared session-level stack allows undoing OTHER users' changes.
 *  - When a new edit is pushed, the user's redo stack is cleared.
 *  - undo() returns the inverse of the last operation so the caller can apply
 *    and broadcast it.
 *  - redo() replays the last undone operation.
 */
public class UndoRedoManager {

    private static final int MAX_STACK_SIZE = 10;

    // Per-user undo stacks
    private final Map<String, Deque<Operations>> undoStacks = new HashMap<>();
    // Per-user redo stacks
    private final Map<String, Deque<Operations>> redoStacks = new HashMap<>();

    // Session-level shared undo stack (for undoing other users' changes)
    private final Map<String, Deque<Operations>> sessionUndoStacks = new HashMap<>();
    private final Map<String, Deque<Operations>> sessionRedoStacks = new HashMap<>();

    // -----------------------------------------------------------------------
    // Push a new operation (called after every local edit)
    // -----------------------------------------------------------------------

    /**
     * Records op so it can later be undone by that user.
     * Also pushes to the shared session stack.
     */
    public void push(String username, Operations op) {
        // Per-user stack
        Deque<Operations> undoStack = getOrCreate(undoStacks, username);
        Deque<Operations> redoStack = getOrCreate(redoStacks, username);

        undoStack.push(op);
        if (undoStack.size() > MAX_STACK_SIZE) {
            ((ArrayDeque<Operations>) undoStack).pollLast();
        }
        redoStack.clear();
    }

    /**
     * Pushes to the shared session-level stack so any user can undo it.
     */
    public void pushToSession(String sessionCode, Operations op) {
        Deque<Operations> stack = getOrCreate(sessionUndoStacks, sessionCode);
        stack.push(op);
        if (stack.size() > MAX_STACK_SIZE) {
            ((ArrayDeque<Operations>) stack).pollLast();
        }
        // Clear session redo when new op arrives
        getOrCreate(sessionRedoStacks, sessionCode).clear();
    }

    // -----------------------------------------------------------------------
    // Undo (user's own changes)
    // -----------------------------------------------------------------------

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
    // Redo (user's own changes)
    // -----------------------------------------------------------------------

    public Operations redo(String username) {
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
    // Undo from shared session stack (undoes any user's change)
    // -----------------------------------------------------------------------

    public Operations undoFromSession(String sessionCode, String requesterUsername) {
        Deque<Operations> undoStack = getOrCreate(sessionUndoStacks, sessionCode);
        Deque<Operations> redoStack = getOrCreate(sessionRedoStacks, sessionCode);

        if (undoStack.isEmpty()) return null;

        Operations original = undoStack.pop();
        redoStack.push(original);
        if (redoStack.size() > MAX_STACK_SIZE) {
            ((ArrayDeque<Operations>) redoStack).pollLast();
        }

        return buildInverse(original, requesterUsername);
    }

    // -----------------------------------------------------------------------
    // Redo from shared session stack
    // -----------------------------------------------------------------------

    public Operations redoFromSession(String sessionCode, String requesterUsername) {
        Deque<Operations> undoStack = getOrCreate(sessionUndoStacks, sessionCode);
        Deque<Operations> redoStack = getOrCreate(sessionRedoStacks, sessionCode);

        if (redoStack.isEmpty()) return null;

        Operations original = redoStack.pop();
        undoStack.push(original);
        if (undoStack.size() > MAX_STACK_SIZE) {
            ((ArrayDeque<Operations>) undoStack).pollLast();
        }

        if ("INSERT_CHAR".equals(original.type)) {
            Operations redo = new Operations();
            redo.type = "UNDELETE_CHAR";
            redo.sessionCode = original.sessionCode;
            redo.username = requesterUsername;
            redo.charUser = original.charUser;
            redo.charClock = original.charClock;
            return redo;
        }

        return copyWithUsername(original, requesterUsername);
    }

    // -----------------------------------------------------------------------
    // Stack size queries
    // -----------------------------------------------------------------------

    public boolean canUndo(String username) {
        return !getOrCreate(undoStacks, username).isEmpty();
    }

    public boolean canRedo(String username) {
        return !getOrCreate(redoStacks, username).isEmpty();
    }

    public boolean canUndoSession(String sessionCode) {
        return !getOrCreate(sessionUndoStacks, sessionCode).isEmpty();
    }

    public boolean canRedoSession(String sessionCode) {
        return !getOrCreate(sessionRedoStacks, sessionCode).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private Operations buildInverse(Operations original, String username) {
        Operations inv = new Operations();
        inv.sessionCode = original.sessionCode;
        inv.username    = username;

        switch (original.type) {
            case "INSERT_CHAR" -> {
                inv.type       = "DELETE_CHAR";
                inv.charUser   = original.charUser;
                inv.charClock  = original.charClock;
            }
            case "DELETE_CHAR" -> {
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
                inv.type       = "FORMAT_CHAR";
                inv.charUser   = original.charUser;
                inv.charClock  = original.charClock;
                inv.isBold     = !original.isBold;
                inv.isItalic   = !original.isItalic;
            }
            case "INSERT_BLOCK" -> {
                inv.type       = "DELETE_BLOCK";
                inv.blockUser  = original.blockUser;
                inv.blockClock = original.blockClock;
            }
            case "DELETE_BLOCK" -> {
                inv.type            = "INSERT_BLOCK";
                inv.blockUser       = original.blockUser;
                inv.blockClock      = original.blockClock;
                inv.parentBlockUser = original.parentBlockUser;
                inv.parentBlockClock = original.parentBlockClock;
            }
            default -> {
                return copyWithUsername(original, username);
            }
        }
        return inv;
    }

    private Operations copyWithUsername(Operations src, String username) {
        Operations copy       = new Operations();
        copy.type             = src.type;
        copy.sessionCode      = src.sessionCode;
        copy.username         = username;
        copy.charUser         = src.charUser;
        copy.charClock        = src.charClock;
        copy.parentUser       = src.parentUser;
        copy.parentClock      = src.parentClock;
        copy.value            = src.value;
        copy.isBold           = src.isBold;
        copy.isItalic         = src.isItalic;
        copy.blockUser        = src.blockUser;
        copy.blockClock       = src.blockClock;
        copy.parentBlockUser  = src.parentBlockUser;
        copy.parentBlockClock = src.parentBlockClock;
        return copy;
    }

    private Deque<Operations> getOrCreate(Map<String, Deque<Operations>> map, String key) {
        return map.computeIfAbsent(key, k -> new ArrayDeque<>());
    }
}