package com.DOCS_ProMax.APT_3ala_barka_el_allah;

public class Comment {
    public String id;
    public String authorUsername;
    public String text;
    public long   timestamp;
    public int    startCharUser;
    public long   startCharClock;
    public int    endCharUser;
    public long   endCharClock;
    public boolean resolved = false;

    public Comment() {}

    public Comment(String id, String author, String text,
                   int startCharUser, long startCharClock,
                   int endCharUser,   long endCharClock) {
        this.id             = id;
        this.authorUsername = author;
        this.text           = text;
        this.startCharUser  = startCharUser;
        this.startCharClock = startCharClock;
        this.endCharUser    = endCharUser;
        this.endCharClock   = endCharClock;
        this.timestamp      = System.currentTimeMillis();
        this.resolved       = false;
    }
}