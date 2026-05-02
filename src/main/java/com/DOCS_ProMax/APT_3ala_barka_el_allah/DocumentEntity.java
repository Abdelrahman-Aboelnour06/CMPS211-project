package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;

import java.util.ArrayList;
import java.util.List;

/**
 * MongoDB document entity that persists a collaborative editing session.
 *
 * Member 1 – Database Persistence & Version History
 */
@Document(collection = "documents")
public class DocumentEntity {

    @Id
    private String id;

    /** Username of the session owner (creator). */
    @Indexed
    private String ownerUsername;

    /** 6-character editor code (full access). */
    @Indexed(unique = true)
    private String editorCode;

    /** 6-character viewer code (read-only access). */
    @Indexed(unique = true)
    private String viewerCode;

    /**
     * The serialised CRDT state (JSON string produced by CrdtSerializer).
     * This is the "current" snapshot of the document.
     */
    private String crdtJson;

    /**
     * Version history – up to 20 past snapshots.
     * Index 0 is the oldest, the last element is the most recent snapshot
     * that was saved BEFORE the current crdtJson was written.
     */
    private List<String> versions = new ArrayList<>();

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    public DocumentEntity() {}

    public DocumentEntity(String ownerUsername, String editorCode,
                          String viewerCode, String crdtJson) {
        this.ownerUsername = ownerUsername;
        this.editorCode    = editorCode;
        this.viewerCode    = viewerCode;
        this.crdtJson      = crdtJson;
    }

    // -----------------------------------------------------------------------
    // Version history helpers
    // -----------------------------------------------------------------------

    private static final int MAX_VERSIONS = 20;

    /**
     * Snapshots the current crdtJson into the versions list before it is overwritten.
     * Trims the list to the most recent MAX_VERSIONS entries.
     */
    public void snapshotCurrentVersion() {
        if (crdtJson != null && !crdtJson.isBlank()) {
            versions.add(crdtJson);
            if (versions.size() > MAX_VERSIONS) {
                versions.remove(0);           // drop the oldest
            }
        }
    }

    // -----------------------------------------------------------------------
    // Getters / Setters
    // -----------------------------------------------------------------------

    public String getId()                         { return id; }
    public void   setId(String id)                { this.id = id; }

    public String getOwnerUsername()              { return ownerUsername; }
    public void   setOwnerUsername(String v)      { this.ownerUsername = v; }

    public String getEditorCode()                 { return editorCode; }
    public void   setEditorCode(String v)         { this.editorCode = v; }

    public String getViewerCode()                 { return viewerCode; }
    public void   setViewerCode(String v)         { this.viewerCode = v; }

    public String getCrdtJson()                   { return crdtJson; }
    public void   setCrdtJson(String v)           { this.crdtJson = v; }

    public List<String> getVersions()             { return versions; }
    public void         setVersions(List<String> v){ this.versions = v; }

    private String documentName = "Untitled";
    public String getDocumentName() { return documentName; }
    public void setDocumentName(String v) { this.documentName = v; }

    private List<Comment> comments = new ArrayList<>();
    public List<Comment> getComments()              { return comments; }
    public void          setComments(List<Comment> c){ this.comments = c; }
}
