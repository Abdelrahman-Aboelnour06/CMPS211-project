package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;


@Repository
public interface DocumentRepository extends MongoRepository<DocumentEntity, String> {


    Optional<DocumentEntity> findByEditorCode(String editorCode);


    Optional<DocumentEntity> findByViewerCode(String viewerCode);


    default Optional<DocumentEntity> findByEditorCodeOrViewerCode(String code) {
        Optional<DocumentEntity> byEditor = findByEditorCode(code);
        if (byEditor.isPresent()) return byEditor;
        return findByViewerCode(code);
    }


    List<DocumentEntity> findAllByOwnerUsername(String ownerUsername);
}
