import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class CharNode {
    private final CharID id;
    private final CharID parentID;
    private final char value;
    private boolean del;
    private final List<CharNode> children;

    private boolean isBold;
    private boolean isItalic;

    public CharNode(CharID id, CharID parentID, char value) {
        this.id = id;
        this.parentID = parentID;
        this.value = value;
        this.del = false;
        this.children = new ArrayList<>();

        // Nodes start out as normal, unformatted text by default
        this.isBold = false;
        this.isItalic = false;

    }

    // --- NEW GETTERS AND SETTERS FOR FORMATTING ---
    public boolean isBold() { return isBold; }
    public void setBold(boolean bold) { this.isBold = bold; }

    public boolean isItalic() { return isItalic; }
    public void setItalic(boolean italic) { this.isItalic = italic; }

    public CharID getID() {
        return id;
    }
    public CharID getParentID() {
        return parentID;
    }
    public char getValue() {
        return value;
    }

    public boolean isDeleted() {
        return del;
    }

    public List<CharNode> getChildren() {
        return children;
    }

    public void SetDeleted(boolean del){
        this.del = del;
    }

    public void addChild(CharNode child) {
        this.children.add(child);
        this.children.sort(Sort);
    }

    private static final Comparator<CharNode> Sort = (a, b) -> {
        int cmp = Long.compare(b.getID().getClock(), a.getID().getClock());
        if (cmp != 0) return cmp;

        return Integer.compare(a.getID().getUserID(), b.getID().getUserID());
    };



}
