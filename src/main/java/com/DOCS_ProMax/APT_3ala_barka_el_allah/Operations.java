package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import com.google.gson.Gson;

public class Operations {


    public String type;
    public String sessionCode;
    public String username;

    // Session codes
    public String editorCode;
    public String originalEditorCode;
    public String viewerCode;
    public String role;

    // lel char
    public int    charUser;
    public long   charClock;
    public int    parentUser;
    public long   parentClock;
    public char   value;

    // lel block
    public int    blockUser;
    public long   blockClock;
    public int    parentBlockUser;
    public long   parentBlockClock;
    public int    endCharUser;
    public long   endCharClock;

    public int    targetBlockUser;
    public long   targetBlockClock;

    // Splitting and merging
    public long   splitAtIndex;


    public boolean isBold;
    public boolean isItalic;

    // cursor
    public int cursorIndex;

    // DB
    public int    versionIndex;
    public String ownerUsername;
    public String documentName;


    public String payload;

    // Comment
    public String commentId;
    public String commentText;


    public String blockSnapshot;
    // a7a
    public int  insertPosition;
    public boolean isMoveOp;


    public int  anchorBlockUser;
    public long anchorBlockClock;


    public String toJson() {
        return new Gson().toJson(this);
    }

    public static Operations fromJson(String json) {
        return new Gson().fromJson(json, Operations.class);
    }
}