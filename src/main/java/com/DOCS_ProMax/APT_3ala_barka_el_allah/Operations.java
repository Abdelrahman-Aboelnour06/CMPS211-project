package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import com.google.gson.Gson;

public class Operations {
    public String type;
    public String sessionCode;
    public String username;

    // Char operation fields
    public int charUser;
    public long charClock;
    public int parentUser;
    public long parentClock;
    public char value;

    // Block operation fields
    public int blockUser;
    public long blockClock;
    public int parentBlockUser;
    public long parentBlockClock;

    // Formatting fields
    public boolean isBold;
    public boolean isItalic;

    // Cursor field
    public int cursorIndex;

    // Generic payload (for sending active users list, full doc state, etc.)
    public String payload;

    public String toJson() {
        return new Gson().toJson(this);
    }

    public static Operations fromJson(String json) {
        return new Gson().fromJson(json, Operations.class);
    }
}