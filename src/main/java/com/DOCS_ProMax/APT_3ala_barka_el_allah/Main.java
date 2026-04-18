package com.DOCS_ProMax.APT_3ala_barka_el_allah;

public class Main {
    public static void main(String[] args) {
        Document doc = new Document(1);

        System.out.println("--- Starting Test ---");

        doc.LocalInsert('H', 0);
        doc.LocalInsert('E', 1);
        doc.LocalInsert('L', 2);
        doc.LocalInsert('L', 3);
        doc.LocalInsert('O', 4);
        System.out.println("Initial string: " + doc.RenderDocument());

        doc.LocalDelete(3);
        System.out.println("After deleting index 3: " + doc.RenderDocument());

        doc.LocalDelete(0);
        System.out.println("After deleting index 0: " + doc.RenderDocument());

        doc.LocalInsert('B', 0);
        System.out.println("After inserting B at index 0: " + doc.RenderDocument());

        System.out.println("--- Test Complete ---");
    }
}