package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;

import java.util.ArrayList;
import java.util.List;


@Document(collection = "documents")
public class DocumentEntity {

    @Id
    private String id;


    @Indexed
    private String ownerUsername;


    @Indexed(unique = true)
    private String editorCode;

    @Indexed(unique = true)
    private String viewerCode;


    private String crdtJson;


    private List<String> versions = new ArrayList<>();


    public DocumentEntity() {}

    public DocumentEntity(String ownerUsername, String editorCode,
                          String viewerCode, String crdtJson) {
        this.ownerUsername = ownerUsername;
        this.editorCode    = editorCode;
        this.viewerCode    = viewerCode;
        this.crdtJson      = crdtJson;
    }

    private static final int MAX_VERSIONS = 20;


    public void snapshotCurrentVersion() {
        if (versions == null) versions = new ArrayList<>();

        if (crdtJson != null && !crdtJson.isBlank()) {
            if (!versions.isEmpty() && versions.get(versions.size() - 1).equals(crdtJson)) {
                return; // deduplicate identical consecutive snapshots
            }
            versions.add(crdtJson);
            if (versions.size() > MAX_VERSIONS) {
                versions.remove(0);
            }
        }
    }
   //setters w getters

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

   
    public List<String> getVersions() {
        if (versions == null) versions = new ArrayList<>();
        return versions;
    }
    public void setVersions(List<String> v)       { this.versions = (v != null) ? v : new ArrayList<>(); }

    private String documentName = "Untitled";
    public String getDocumentName() { return documentName; }
    public void setDocumentName(String v) { this.documentName = v; }

    private List<Comment> comments = new ArrayList<>();
    public List<Comment> getComments()              { return comments; }
    public void          setComments(List<Comment> c){ this.comments = c; }
}