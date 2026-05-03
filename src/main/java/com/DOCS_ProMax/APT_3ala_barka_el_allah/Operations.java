package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import com.google.gson.Gson;

/**
 * Wire-format message used between Client and Server over WebSocket.
 */
public class Operations {

    // Core fields
    public String type;
    public String sessionCode;
    public String username;

    // Session codes
    public String editorCode;
    public String originalEditorCode;
    public String viewerCode;
    public String role;

    // Char operation fields
    public int    charUser;
    public long   charClock;
    public int    parentUser;
    public long   parentClock;
    public char   value;

    // Block operation fields
    public int    blockUser;
    public long   blockClock;
    public int    parentBlockUser;
    public long   parentBlockClock;
    public int    endCharUser;
    public long   endCharClock;

    // Target block for MOVE_BLOCK and COPY_BLOCK
    // (the block after which the moved/copied block is inserted)
    public int    targetBlockUser;
    public long   targetBlockClock;

    // Split/merge fields
    public long   splitAtIndex;   // cursor index for SPLIT_BLOCK

    // Formatting fields
    public boolean isBold;
    public boolean isItalic;

    // Cursor field
    public int cursorIndex;

    // DB fields
    public int    versionIndex;
    public String ownerUsername;
    public String documentName;

    // Generic payload
    public String payload;

    // Comment fields
    public String commentId;
    public String commentText;

    // Serialisation
    public String toJson() {
        return new Gson().toJson(this);
    }

    public static Operations fromJson(String json) {
        return new Gson().fromJson(json, Operations.class);
    }
}
