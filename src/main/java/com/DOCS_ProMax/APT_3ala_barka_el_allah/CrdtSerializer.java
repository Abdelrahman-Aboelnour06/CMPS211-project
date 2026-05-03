package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Serialises / deserialises the visible text (plus formatting) of a CharCRDT
 * to/from a compact JSON string for database storage.
 *
 * Member 1 – Database Persistence & Version History
 *
 * We intentionally store only the *visible* ordered nodes so the snapshot is
 * human-readable and compact.  A full structural snapshot (the internal tree)
 * would require custom Gson adapters for every circular reference; this simpler
 * approach is sufficient for the requirements described in the task document.
 *
 * Format:
 * [
 *   { "userID": 1, "clock": 5, "parentUser": -1, "parentClock": -1,
 *     "value": "H", "bold": false, "italic": false, "deleted": false },
 *   ...
 * ]
 */
public class CrdtSerializer {

    private static final Gson GSON = new GsonBuilder().create();

    // DTO used only for serialisation – not a domain object
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

    // -----------------------------------------------------------------------
    // Serialise
    // -----------------------------------------------------------------------

    /**
     * Converts all ordered nodes (including deleted ones for full fidelity) in
     * {@code crdt} into a JSON string.
     */
    public static String toJson(CharCRDT crdt) {
        List<NodeDto> dtos = new ArrayList<>();
        for (CharNode n : crdt.getOrderedNodes()) {
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

    // -----------------------------------------------------------------------
    // Deserialise
    // -----------------------------------------------------------------------

    /**
     * Reconstructs a {@link CharCRDT} from a JSON string that was previously
     * produced by {@link #toJson(CharCRDT)}.
     *
     * @param json   The stored JSON blob.
     * @param userId The userID to assign to the reconstructed CRDT's local clock.
     */
    // -----------------------------------------------------------------------
    // Deserialise
    // -----------------------------------------------------------------------
    /**
     * Reconstructs a {@link CharCRDT} from a JSON string that was previously
     * produced by {@link #toJson(CharCRDT)}.
     *
     * @param json    The stored JSON blob.
     * @param userId  The userID to assign to the reconstructed CRDT's local clock.
     */
    public static CharCRDT fromJson(String json, int userId) {
        if (json == null || json.isBlank()) return new CharCRDT(userId);

        Type listType = new TypeToken<List<NodeDto>>() {}.getType();
        List<NodeDto> dtos = GSON.fromJson(json, listType);

        CharCRDT crdt = new CharCRDT(userId);

        for (NodeDto dto : dtos) {
            CharID incomingID = new CharID(dto.userID, dto.clock);
            CharID parentID   = new CharID(dto.parentUser, dto.parentClock);

            CharNode node = crdt.RemotelyInsertion(incomingID, parentID, dto.value);

            if (node != null) {
                node.setBold(dto.bold);
                node.setItalic(dto.italic);
                if (dto.deleted) node.SetDeleted(true);
            }
        }

        return crdt;
    }
}