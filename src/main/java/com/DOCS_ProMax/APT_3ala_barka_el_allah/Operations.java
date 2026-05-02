package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import com.google.gson.Gson;

/**
 * Wire-format message used between Client and Server over WebSocket.
 *
 * Updated by Member 2 to add:
 *  - {@code editorCode} and {@code viewerCode} fields (sent back on session creation)
 *  - {@code role} field ("editor" | "viewer") so the server knows who can edit
 *
 * Updated by Member 1 to add:
 *  - {@code versionIndex} field for rollback requests
 *  - {@code ownerUsername} field for LIST_DOCS
 */
public class Operations {

    // -----------------------------------------------------------------------
    // Core fields
    // -----------------------------------------------------------------------

    public String type;
    public String sessionCode;    // primary join code (may be editor OR viewer code)
    public String username;

    // -----------------------------------------------------------------------
    // Member 2 additions
    // -----------------------------------------------------------------------

    /** The editor (read-write) access code – sent back in SESSION_CREATED. */
    public String editorCode;

    public String originalEditorCode;

    /** The viewer (read-only) access code – sent back in SESSION_CREATED. */
    public String viewerCode;

    /**
     * Role of the connecting user: "editor" or "viewer".
     * Set by the server after a JOIN_SESSION and stored in SessionManager.
     */
    public String role;

    // -----------------------------------------------------------------------
    // Char operation fields
    // -----------------------------------------------------------------------

    public int    charUser;
    public long   charClock;
    public int    parentUser;
    public long   parentClock;
    public char   value;

    // -----------------------------------------------------------------------
    // Block operation fields
    // -----------------------------------------------------------------------

    public int    blockUser;
    public long   blockClock;
    public int    parentBlockUser;
    public long   parentBlockClock;
    public int  endCharUser;
    public long endCharClock;

    // -----------------------------------------------------------------------
    // Formatting fields
    // -----------------------------------------------------------------------

    public boolean isBold;
    public boolean isItalic;

    // -----------------------------------------------------------------------
    // Cursor field
    // -----------------------------------------------------------------------

    public int cursorIndex;

    // -----------------------------------------------------------------------
    // Member 1 additions
    // -----------------------------------------------------------------------

    /**
     * For ROLLBACK_VERSION: the zero-based index into the versions list.
     * For LIST_DOCS / SAVE_DOC / DELETE_DOC: the ownerUsername.
     */
    public int    versionIndex;
    public String ownerUsername;

    // -----------------------------------------------------------------------
    // Generic payload
    // -----------------------------------------------------------------------

    /** Generic JSON payload (active users list, full doc state, etc.). */
    public String payload;

    //Comments

    public String commentId;
    public String commentText;

    // -----------------------------------------------------------------------
    // Serialisation
    // -----------------------------------------------------------------------

    public String toJson() {
        return new Gson().toJson(this);
    }

    public static Operations fromJson(String json) {
        return new Gson().fromJson(json, Operations.class);
    }
}
