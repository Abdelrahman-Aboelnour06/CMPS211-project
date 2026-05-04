package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import java.util.List;
import java.util.Scanner;

public class groupin_tester {

    static StringBuilder baseText = new StringBuilder("cat ");
    static String u1Word = "dog";
    static String u2Word = "frog";
    static int insertIdx = 4;

    public static String renderDocumentView(BlockCRDT doc) {
        StringBuilder sb = new StringBuilder();
        List<BlockNode> blocks = doc.getOrderedNodes();

        for (int i = 0; i < blocks.size(); i++) {
            BlockNode block = blocks.get(i);
            if (block.isDeleted() || block.getContent() == null) {
                continue;
            }

            List<CharNode> chars = block.getChars();
            for (int j = 0; j < chars.size(); j++) {
                CharNode c = chars.get(j);
                if (!c.isDeleted()) {
                    sb.append(c.getValue());
                }
            }
        }
        return sb.toString();
    }

    public static void synchronizeLogicalClocks(BlockCRDT user1, BlockCRDT user2, BlockID u1Block, BlockID u2Block) {
        CharCRDT crdt1 = user1.getBlock(u1Block).getContent();
        CharCRDT crdt2 = user2.getBlock(u2Block).getContent();

        long clock1 = crdt1.generateID().getClock();
        long clock2 = crdt2.generateID().getClock();

        while (clock1 < clock2) {
            clock1 = crdt1.generateID().getClock();
        }
        while (clock2 < clock1) {
            clock2 = crdt2.generateID().getClock();
        }
    }

    public static void setupInputs(Scanner scanner) {
        System.out.println("\n--- Setup Custom Inputs for 2 Users ---");

        System.out.print("Enter shared base text (current: '" + baseText + "'): ");
        String text = scanner.nextLine();
        if (!text.isEmpty()) {
            baseText = new StringBuilder(text);
        }

        System.out.print("Enter string for User 1 (current: '" + u1Word + "'): ");
        String w1 = scanner.nextLine();
        if (!w1.isEmpty()) u1Word = w1;

        System.out.print("Enter string for User 2 (current: '" + u2Word + "'): ");
        String w2 = scanner.nextLine();
        if (!w2.isEmpty()) u2Word = w2;

        System.out.print("Enter concurrent insertion index (0 to " + baseText.length() + "): ");
        String idxStr = scanner.nextLine();
        if (!idxStr.isEmpty() && idxStr.matches("\\d+")) {
            insertIdx = Math.min(Integer.parseInt(idxStr), baseText.length());
        }
    }

    private static void testConcurrentEdits() {
        System.out.println("\n--- Testing Concurrent Character Edits ---");

        Clock sharedClock = new Clock();
        BlockCRDT user1Doc = new BlockCRDT(1, sharedClock);
        BlockCRDT user2Doc = new BlockCRDT(2, sharedClock);

        BlockNode u1Block = user1Doc.insertTopLevelBlock(new CharCRDT(1));
        BlockNode u2Block = user2Doc.insertTopLevelBlock(new CharCRDT(2));

        // Sync Base Text
        for (int i = 0; i < baseText.length(); i++) {
            user1Doc.insertCharInBlock(u1Block.getId(), baseText.charAt(i), i);
        }
        for (CharNode node : user1Doc.getBlock(u1Block.getId()).getChars()) {
            user2Doc.getBlock(u2Block.getId()).getContent().RemotelyInsertion(node.getID(), node.getParentID(), node.getValue());
        }

        synchronizeLogicalClocks(user1Doc, user2Doc, u1Block.getId(), u2Block.getId());

        List<CharNode> q1 = new java.util.ArrayList<>();
        for (int i = 0; i < u1Word.length(); i++) {
            user1Doc.insertCharInBlock(u1Block.getId(), u1Word.charAt(i), insertIdx + i);
            q1.add(user1Doc.getBlock(u1Block.getId()).getChars().get(insertIdx + i));
        }

        List<CharNode> q2 = new java.util.ArrayList<>();
        for (int i = 0; i < u2Word.length(); i++) {
            user2Doc.insertCharInBlock(u2Block.getId(), u2Word.charAt(i), insertIdx + i);
            q2.add(user2Doc.getBlock(u2Block.getId()).getChars().get(insertIdx + i));
        }

        // Final Sync
        for (CharNode n : q1) user2Doc.getBlock(u2Block.getId()).getContent().RemotelyInsertion(n.getID(), n.getParentID(), n.getValue());
        for (CharNode n : q2) user1Doc.getBlock(u1Block.getId()).getContent().RemotelyInsertion(n.getID(), n.getParentID(), n.getValue());

        String res1 = renderDocumentView(user1Doc);
        String res2 = renderDocumentView(user2Doc);

        if (res1.equals(res2)) {
            baseText.setLength(0);
            baseText.append(res1);
            System.out.println("SUCCESS: Base Text updated to: '" + baseText + "'");
        } else {
            System.out.println("FAILED: Documents did not converge.");
        }
    }

    private static void MultiUserEdits(Scanner scanner) {
        System.out.println("\n--- Testing Interactive Multi-User (Live Sync) ---");

        Clock sharedClock = new Clock();
        BlockCRDT user1 = new BlockCRDT(1, sharedClock);
        BlockCRDT user2 = new BlockCRDT(2, sharedClock);
        BlockNode u1Block = user1.insertTopLevelBlock(new CharCRDT(1));
        BlockNode u2Block = user2.insertTopLevelBlock(new CharCRDT(2));

        // 1. Setup initial state from the StringBuilder
        for (int i = 0; i < baseText.length(); i++) {
            user1.insertCharInBlock(u1Block.getId(), baseText.charAt(i), i);
        }
        for (CharNode node : user1.getBlock(u1Block.getId()).getChars()) {
            user2.getBlock(u2Block.getId()).getContent().RemotelyInsertion(node.getID(), node.getParentID(), node.getValue());
        }

        synchronizeLogicalClocks(user1, user2, u1Block.getId(), u2Block.getId());

        int turnCounter = 1;
        while (true) {
            String currentView = renderDocumentView(user1);
            baseText.setLength(0);
            baseText.append(currentView);

            System.out.println("\n=========================================");
            System.out.println(" TURN " + turnCounter + " | CURRENT BASE: '" + baseText + "'");
            System.out.println("=========================================");

            // --- USER 1 ---
            System.out.print("[User 1] WORD (or '-1'): ");
            String w1 = scanner.nextLine();
            if (w1.equals("-1")) break;

            int max1 = user1.getBlock(u1Block.getId()).getChars().size();
            System.out.print("  > Index (0-" + max1 + "): ");
            String i1Str = scanner.nextLine();
            int i1 = (i1Str.matches("\\d+")) ? Math.min(Integer.parseInt(i1Str), max1) : max1;

            List<CharNode> turnQ1 = new java.util.ArrayList<>();
            for (int i = 0; i < w1.length(); i++) {
                user1.insertCharInBlock(u1Block.getId(), w1.charAt(i), i1 + i);
                turnQ1.add(user1.getBlock(u1Block.getId()).getChars().get(i1 + i));
            }


            System.out.print("[User 2] WORD (or '-1'): ");
            String w2 = scanner.nextLine();
            if (w2.equals("-1")) {
                for (CharNode n : turnQ1) user2.getBlock(u2Block.getId()).getContent().RemotelyInsertion(n.getID(), n.getParentID(), n.getValue());
                break;
            }

            int max2 = user2.getBlock(u2Block.getId()).getChars().size();
            System.out.print("  > Index (0-" + max2 + "): ");
            String i2Str = scanner.nextLine();
            int i2 = (i2Str.matches("\\d+")) ? Math.min(Integer.parseInt(i2Str), max2) : max2;

            List<CharNode> turnQ2 = new java.util.ArrayList<>();
            for (int i = 0; i < w2.length(); i++) {
                user2.insertCharInBlock(u2Block.getId(), w2.charAt(i), i2 + i);
                turnQ2.add(user2.getBlock(u2Block.getId()).getChars().get(i2 + i));
            }


            for (CharNode n : turnQ1) user2.getBlock(u2Block.getId()).getContent().RemotelyInsertion(n.getID(), n.getParentID(), n.getValue());
            for (CharNode n : turnQ2) user1.getBlock(u1Block.getId()).getContent().RemotelyInsertion(n.getID(), n.getParentID(), n.getValue());

            synchronizeLogicalClocks(user1, user2, u1Block.getId(), u2Block.getId());

            System.out.println("-> Turn " + turnCounter + " synced successfully.");
            turnCounter++;
        }
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        setupInputs(scanner);
        testConcurrentEdits();
        MultiUserEdits(scanner);
        scanner.close();
    }
}