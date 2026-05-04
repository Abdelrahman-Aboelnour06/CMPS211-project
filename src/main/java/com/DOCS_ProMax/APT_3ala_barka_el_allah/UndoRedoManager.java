// ── FILE: UndoRedoManager.java ───────────────────────────────────────────
// REPLACE the entire file

package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import java.util.*;

public class UndoRedoManager {

    private static final int MAX_STACK_SIZE = 50;

    private final Map<String, Deque<List<Operations>>> undoStacks = new HashMap<>();
    private final Map<String, Deque<List<Operations>>> redoStacks = new HashMap<>();
    private final Map<String, Deque<List<Operations>>> sessionUndoStacks = new HashMap<>();
    private final Map<String, Deque<List<Operations>>> sessionRedoStacks = new HashMap<>();


    private final Map<String, List<Operations>> openGroups = new HashMap<>();
    private final Map<String, List<Operations>> openSessionGroups = new HashMap<>();

    public void beginGroup(String username, String sessionCode) {
        openGroups.put(username, new ArrayList<>());
        openSessionGroups.put(sessionCode + ":" + username, new ArrayList<>());
    }

    public void endGroup(String username, String sessionCode) {
        List<Operations> group = openGroups.remove(username);
        String key = sessionCode + ":" + username;
        List<Operations> sessionGroup = openSessionGroups.remove(key);

        if (group != null && !group.isEmpty()) {
            pushGroup(getOrCreate(undoStacks, username), group);
            getOrCreate(redoStacks, username).clear();
        }
        if (sessionGroup != null && !sessionGroup.isEmpty()) {
            pushGroup(getOrCreate(sessionUndoStacks, sessionCode), sessionGroup);
            getOrCreate(sessionRedoStacks, sessionCode).clear();
        }
    }

    public void push(String username, Operations op) {
        List<Operations> group = openGroups.get(username);
        if (group != null) { group.add(op); return; }

        Deque<List<Operations>> undoStack = getOrCreate(undoStacks, username);
        Deque<List<Operations>> redoStack = getOrCreate(redoStacks, username);
        pushGroup(undoStack, Collections.singletonList(op));
        redoStack.clear();
    }

    public void pushToSession(String sessionCode, Operations op) {
        for (Map.Entry<String, List<Operations>> e : openSessionGroups.entrySet()) {
            if (e.getKey().startsWith(sessionCode + ":")) {
                e.getValue().add(op);
                return;
            }
        }
        Deque<List<Operations>> stack = getOrCreate(sessionUndoStacks, sessionCode);
        pushGroup(stack, Collections.singletonList(op));
        getOrCreate(sessionRedoStacks, sessionCode).clear();
    }


    public List<Operations> undoGroup(String username) {
        Deque<List<Operations>> undoStack = getOrCreate(undoStacks, username);
        Deque<List<Operations>> redoStack = getOrCreate(redoStacks, username);
        if (undoStack.isEmpty()) return null;

        List<Operations> group = undoStack.pop();
        pushGroup(redoStack, group);

        List<Operations> inverses = new ArrayList<>();
        for (int i = group.size() - 1; i >= 0; i--) {
            Operations inv = buildInverse(group.get(i), username);
            if (inv != null) inverses.add(inv);
        }
        return inverses.isEmpty() ? null : inverses;
    }

    public List<Operations> redoGroup(String username) {
        Deque<List<Operations>> undoStack = getOrCreate(undoStacks, username);
        Deque<List<Operations>> redoStack = getOrCreate(redoStacks, username);
        if (redoStack.isEmpty()) return null;

        List<Operations> group = redoStack.pop();
        pushGroup(undoStack, group);

        List<Operations> reapplied = new ArrayList<>();
        for (int i = group.size() - 1; i >= 0; i--) {
            Operations r = buildRedo(group.get(i), username);
            if (r != null) reapplied.add(r);
        }
        return reapplied.isEmpty() ? null : reapplied;
    }

    public List<Operations> undoFromSessionGroup(String sessionCode, String username) {
        Deque<List<Operations>> undoStack = getOrCreate(sessionUndoStacks, sessionCode);
        Deque<List<Operations>> redoStack = getOrCreate(sessionRedoStacks, sessionCode);
        if (undoStack.isEmpty()) return null;

        List<Operations> group = undoStack.pop();
        pushGroup(redoStack, group);

        List<Operations> inverses = new ArrayList<>();
        for (int i = group.size() - 1; i >= 0; i--) {
            Operations inv = buildInverse(group.get(i), username);
            if (inv != null) inverses.add(inv);
        }
        return inverses.isEmpty() ? null : inverses;
    }

    public List<Operations> redoFromSessionGroup(String sessionCode, String username) {
        Deque<List<Operations>> undoStack = getOrCreate(sessionUndoStacks, sessionCode);
        Deque<List<Operations>> redoStack = getOrCreate(sessionRedoStacks, sessionCode);
        if (redoStack.isEmpty()) return null;

        List<Operations> group = redoStack.pop();
        pushGroup(undoStack, group);

        List<Operations> reapplied = new ArrayList<>();
        for (int i = group.size() - 1; i >= 0; i--) {
            Operations r = buildRedo(group.get(i), username);
            if (r != null) reapplied.add(r);
        }
        return reapplied.isEmpty() ? null : reapplied;
    }

    public Operations undo(String username) {
        List<Operations> group = undoGroup(username);
        return (group != null && !group.isEmpty()) ? group.get(0) : null;
    }

    public Operations redo(String username) {
        List<Operations> group = redoGroup(username);
        return (group != null && !group.isEmpty()) ? group.get(0) : null;
    }

    public Operations undoFromSession(String sessionCode, String username) {
        List<Operations> group = undoFromSessionGroup(sessionCode, username);
        return (group != null && !group.isEmpty()) ? group.get(0) : null;
    }

    public Operations redoFromSession(String sessionCode, String username) {
        List<Operations> group = redoFromSessionGroup(sessionCode, username);
        return (group != null && !group.isEmpty()) ? group.get(0) : null;
    }

    public boolean canUndo(String username) {
        return !getOrCreate(undoStacks, username).isEmpty();
    }
    public boolean canRedo(String username) {
        return !getOrCreate(redoStacks, username).isEmpty();
    }
    public boolean canUndoSession(String s) {
        return !getOrCreate(sessionUndoStacks, s).isEmpty();
    }
    public boolean canRedoSession(String s) {
        return !getOrCreate(sessionRedoStacks, s).isEmpty();
    }


    private void pushGroup(Deque<List<Operations>> stack, List<Operations> group) {
        stack.push(new ArrayList<>(group));
        while (stack.size() > MAX_STACK_SIZE) {
            ((ArrayDeque<List<Operations>>) stack).pollLast();
        }
    }

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
                inv.type     = "FORMAT_CHAR";
                inv.charUser  = original.charUser;
                inv.charClock = original.charClock;
                inv.isBold    = !original.isBold;
                inv.isItalic  = !original.isItalic;
            }
            case "INSERT_BLOCK" -> {
                inv.type       = "DELETE_BLOCK";
                inv.blockUser  = original.blockUser;
                inv.blockClock = original.blockClock;
            }
            case "DELETE_BLOCK" -> {
                inv.type             = "INSERT_BLOCK";
                inv.blockUser        = original.blockUser;
                inv.blockClock       = original.blockClock;
                inv.parentBlockUser  = original.parentBlockUser;
                inv.parentBlockClock = original.parentBlockClock;
                inv.blockSnapshot    = original.blockSnapshot;
            }
            default -> { return copyWithUsername(original, username); }
        }
        return inv;
    }

    private Operations buildRedo(Operations original, String username) {
        if ("INSERT_CHAR".equals(original.type)) {
            Operations r = new Operations();
            r.type       = "UNDELETE_CHAR";
            r.sessionCode = original.sessionCode;
            r.username   = username;
            r.charUser   = original.charUser;
            r.charClock  = original.charClock;
            return r;
        }
        return copyWithUsername(original, username);
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
        copy.blockSnapshot    = src.blockSnapshot;
        return copy;
    }

    private Deque<List<Operations>> getOrCreate(
            Map<String, Deque<List<Operations>>> map, String key) {
        return map.computeIfAbsent(key, k -> new ArrayDeque<>());
    }
}