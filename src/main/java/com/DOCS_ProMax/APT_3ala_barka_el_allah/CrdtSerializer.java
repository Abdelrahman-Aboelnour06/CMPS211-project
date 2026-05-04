package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;


public class CrdtSerializer {

    private static final Gson GSON = new GsonBuilder().create();


    private static class NodeDto {
        int userID;
        long clock;
        int parentUser;
        long parentClock;
        char value;
        boolean bold;
        boolean italic;
        boolean deleted;
    }


    public static CharCRDT fromJson(String json, int userId) {
        if (json == null || json.isBlank()) return new CharCRDT(userId);
        Type listType = new TypeToken<List<NodeDto>>() {
        }.getType();
        List<NodeDto> dtos = GSON.fromJson(json, listType);

        CharCRDT crdt = new CharCRDT(userId);
        CharID lastID = crdt.rootID;

        for (NodeDto dto : dtos) {
            CharID incomingID = new CharID(dto.userID, dto.clock);


            CharNode node = crdt.RemotelyInsertion(incomingID, lastID, dto.value);

            if (node != null) {
                node.setBold(dto.bold);
                node.setItalic(dto.italic);
                if (dto.deleted) node.SetDeleted(true);

                lastID = incomingID; // Chain the next letter to this one
            }
        }
        return crdt;
    }

    public static String toJson(CharCRDT crdt) {
        List<NodeDto> dtos = new ArrayList<>();
        for (CharNode n : crdt.getAllNodesIncludingDeleted()) {
            NodeDto dto = new NodeDto();
            dto.userID = n.getID().getUserID();
            dto.clock = n.getID().getClock();
            dto.parentUser = n.getParentID() != null ? n.getParentID().getUserID() : -1;
            dto.parentClock = n.getParentID() != null ? n.getParentID().getClock() : -1;
            dto.value = n.getValue();
            dto.bold = n.isBold();
            dto.italic = n.isItalic();
            dto.deleted = n.isDeleted();
            dtos.add(dto);
        }
        return GSON.toJson(dtos);
    }




    private static class BlockDto {
        int idUser; long idClock;
        int pUser; long pClock;
        String charJson;
        boolean deleted;
    }


    public static String toDocumentJson(BlockCRDT doc) {
        List<BlockDto> bDtos = new ArrayList<>();
        for (BlockNode bn : doc.getAllNodesIncludingDeleted()) {
            BlockDto b = new BlockDto();
            b.idUser = bn.getId().getUserID();
            b.idClock = bn.getId().getClock();
            b.pUser = bn.getParentID() != null ? bn.getParentID().getUserID() : -1;
            b.pClock = bn.getParentID() != null ? bn.getParentID().getClock() : -1;
            b.charJson = toJson(bn.getContent());
            b.deleted = bn.isDeleted();
            bDtos.add(b);
        }
        return GSON.toJson(bDtos);
    }

    public static void loadDocumentJson(String json, BlockCRDT doc) {
        if (json == null || json.isBlank()) return;
        try {
            Type listType = new TypeToken<List<BlockDto>>(){}.getType();
            List<BlockDto> dtos = GSON.fromJson(json, listType);


            doc.getAllNodesIncludingDeleted().forEach(bn -> bn.setDeleted(true));

            for (BlockDto b : dtos) {
                BlockID id = new BlockID(b.idUser, b.idClock);
                BlockID pID = (b.pUser == -1) ? null : new BlockID(b.pUser, b.pClock);
                CharCRDT content = fromJson(b.charJson, doc.getUserid());
                BlockNode node = doc.insertBlockWithID(id, pID, content);
                if (b.deleted) node.setDeleted(true);
            }
        } catch (Exception e) {

            CharCRDT legacy = fromJson(json, doc.getUserid());
            doc.getAllNodesIncludingDeleted().forEach(bn -> bn.setDeleted(true));
            doc.insertTopLevelBlock(legacy);
        }
    }
}