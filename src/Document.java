import java.util.List;
import java.util.List;

public class Document {
    final private CharCRDT crdtInstance;

    public Document(int CurrentUserID) {
        this.crdtInstance = new CharCRDT(CurrentUserID);
    }

    public String RenderDocument(){
        List<CharNode> NodesList = crdtInstance.getOrderedNodes();

        StringBuilder textBuilder = new StringBuilder();

        for (CharNode node : NodesList) {
            textBuilder.append(node.getValue());
        }

        return textBuilder.toString();
    }

    public void LocalInsert(char value, int index){
        List<CharNode> NodesList = crdtInstance.getOrderedNodes();
        CharID parentID;
        if (index==0){
            parentID=crdtInstance.rootID;
        }
        else{
            CharNode parentNode=NodesList.get(index-1);
            parentID=parentNode.getID();
        }

        crdtInstance.insertNode(parentID,value);
    }

    public void LocalDelete(int index) {
        List<CharNode> NodesList = crdtInstance.getOrderedNodes();

        // Safety check: make sure the index is actually inside the list
        if (index >= 0 && index < NodesList.size()) {
            CharNode DeletedNode = NodesList.get(index);
            DeletedNode.SetDeleted(true);
        } else {
            System.out.println("Warning: Attempted to delete invalid index: " + index);
        }
    }

}