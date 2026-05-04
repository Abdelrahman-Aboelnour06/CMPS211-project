package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import javax.swing.text.StyledDocument;
import javax.swing.text.StyleConstants;
import javax.swing.text.SimpleAttributeSet;
import java.util.HashMap;
import java.util.List;
import javax.swing.UIManager;
import java.awt.Insets;
import java.awt.Font;
import javax.swing.text.*;
import java.awt.Color;
import java.util.Map;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;

public class EditorUI {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------
    private static final int MAX_LINES_PER_BLOCK = 10;

    // -----------------------------------------------------------------------
    // UI components
    // -----------------------------------------------------------------------
    private JFrame frame;
    private JTextPane textPane;
    private String username;
    private String sessionCode;
    private Client client;
    private DefaultListModel<String> usersListModel;
    private JList<String> usersList;

    // Cursor tracking
    private java.util.Map<String, Integer> remoteCursors = new java.util.HashMap<>();
    private DefaultListModel<String> cursorListModel;
    private JList<String> cursorList;
    private Highlighter highlighter;
    private Map<String, Object> cursorHighlights = new HashMap<>();
    private final Map<String, Color> assignedColors = new java.util.LinkedHashMap<>();
    private final Color[] CURSOR_COLORS = {
            new Color(255, 105, 180),
            new Color(30, 144, 255),
            new Color(50, 205, 50),
            new Color(255, 165, 0)
    };

    // Comments
    private final java.util.Map<String, Comment> activeComments = new java.util.LinkedHashMap<>();
    private final Map<String, Object> commentHighlights = new HashMap<>();
    private JPanel commentsListPanel;

    // Document model
    private Document doc;


    private BlockID copiedBlockID = null;


    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------
    public EditorUI(String username, String sessionCode, Client client, String documentName) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        CharCRDT sharedCRDT = client.getActiveCharCRDT();
        this.doc = new Document(sharedCRDT);
        this.username = username;
        this.sessionCode = sessionCode;
        this.client = client;

        // Message listener
        client.setMessageListener(op -> {
            SwingUtilities.invokeLater(() -> {
                switch (op.type) {
                    case "ACTIVE_USERS" -> {
                        List<String> users = new com.google.gson.Gson().fromJson(
                                op.payload,
                                new com.google.gson.reflect.TypeToken<List<String>>() {}.getType()
                        );
                        updateActiveUsers(users);
                    }
                    case "INSERT_CHAR", "DELETE_CHAR", "UNDELETE_CHAR", "FORMAT_CHAR" -> {
                        int caret = textPane.getCaretPosition();
                        if ("UNDELETE_CHAR".equals(op.type)) caret += 1;
                        renderDocument(caret);
                        drawRemoteCursors();
                    }
                    case "SESSION_JOINED" -> {
                        javax.swing.Timer poller = new javax.swing.Timer(200, null);
                        int[] attempts = {0};
                        poller.addActionListener(ev -> {
                            attempts[0]++;
                            int savedCaret = textPane != null ? textPane.getCaretPosition() : 0;
                            renderDocument(savedCaret);
                            drawRemoteCursors();
                            if (!doc.GetVisibleNodes().isEmpty() || attempts[0] >= 25) poller.stop();
                        });
                        poller.start();
                    }
                    case "CURSOR" -> {
                        if (op.username != null && !op.username.equals(username)) {
                            remoteCursors.put(op.username, op.cursorIndex);
                            drawRemoteCursors();
                            updateRemoteCursorDisplay();
                        }
                    }
                    case "COMMENT_ADDED" -> {
                        Comment c = new com.google.gson.Gson().fromJson(op.payload, Comment.class);
                        if (c != null) {
                            activeComments.put(c.id, c);
                            drawCommentHighlights();
                        }
                    }
                    case "COMMENT_DELETED" -> {
                        if (op.commentId != null) activeComments.remove(op.commentId);
                        else activeComments.clear();
                        drawCommentHighlights();
                    }
                    case "COMMENTS_LIST" -> {
                        activeComments.clear();
                        java.util.List<Comment> list = new com.google.gson.Gson().fromJson(
                                op.payload,
                                new com.google.gson.reflect.TypeToken<java.util.List<Comment>>(){}.getType()
                        );
                        if (list != null) list.forEach(c -> activeComments.put(c.id, c));
                        drawCommentHighlights();
                    }
                    case "COMMENT_RESOLVED" -> {
                        if (op.commentId != null) {
                            Comment c = activeComments.get(op.commentId);
                            if (c != null) {
                                c.resolved = true;
                                drawCommentHighlights();
                            }
                        }
                    }
                    case "VERSIONS_LIST" -> showVersionsDialog(op.payload);

                    case "DOC_LOADED" -> {
                        if (op.payload == null || op.payload.isBlank()) break;

                        BlockCRDT localDoc = client.getLocalDoc();

                        // Wipe all existing blocks
                        localDoc.getAllNodesIncludingDeleted()
                                .forEach(bn -> bn.setDeleted(true));

                        // Reload full multi-block document
                        CrdtSerializer.loadDocumentJson(op.payload, localDoc);

                        // Point activeBlockID at the first live block
                        List<BlockNode> live = localDoc.getOrderedNodes();
                        if (!live.isEmpty()) {
                            client.setActiveBlockID(live.get(0).getId());
                        }

                        renderDocument(0);
                        drawRemoteCursors();
                    }
                    case "INSERT_BLOCK", "DELETE_BLOCK", "SPLIT_BLOCK", "MERGE_BLOCK",
                         "MOVE_BLOCK", "COPY_BLOCK","MOVE_BLOCK_EXEC" -> {
                        renderDocument(textPane != null ? textPane.getCaretPosition() : 0);
                        drawRemoteCursors();
                        updateRemoteCursorDisplay();
                    }
                }
            });
        });

        SwingUtilities.invokeLater(() -> { renderDocument(0); drawRemoteCursors(); });

        // Startup poller
        javax.swing.Timer startupPoller = new javax.swing.Timer(250, null);
        int[] pollCount = {0};
        startupPoller.addActionListener(ev -> {
            if (!client.isOpen()) { startupPoller.stop(); return; }
            pollCount[0]++;
            if (!doc.GetVisibleNodes().isEmpty() && pollCount[0] > 2) { startupPoller.stop(); return; }
            int savedCaret = textPane != null ? textPane.getCaretPosition() : 0;
            renderDocument(savedCaret);
            drawRemoteCursors();
            if (pollCount[0] >= 20) startupPoller.stop();
        });
        startupPoller.start();
        client.requestActiveUsers();
        client.sendGetComments();

        // Frame
        frame = new JFrame(
                "📄 " + documentName + "  |  " + username +
                        (client.isEditor() ? "  —  EDITOR" : "  —  VIEWER [READ ONLY]")
        );
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(900, 650);
        frame.setLayout(new BorderLayout());

        // Toolbar
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        Font buttonFont = new Font("Segoe UI", Font.BOLD, 13);
        Insets btnMargin = new Insets(8, 18, 8, 18);

        JButton boldBtn    = makeToolbarButton("Bold",    buttonFont, btnMargin);
        JButton italicBtn  = makeToolbarButton("Italic",  buttonFont, btnMargin);
        JButton undoBtn    = makeToolbarButton("Undo",    buttonFont, btnMargin);
        JButton redoBtn    = makeToolbarButton("Redo",    buttonFont, btnMargin);
        JButton importBtn  = makeToolbarButton("Import",  buttonFont, btnMargin);
        JButton exportBtn  = makeToolbarButton("Export",  buttonFont, btnMargin);
        JButton saveBtn    = makeToolbarButton("Save",    buttonFont, btnMargin);
        JButton historyBtn = makeToolbarButton("⏱ History", buttonFont, btnMargin);

        boldBtn.addActionListener(e -> {
            int start = textPane.getSelectionStart();
            int end   = textPane.getSelectionEnd();
            if (start == end) return;
            List<CharNode> allVisible = getAllVisibleNodes();
            if (end > allVisible.size()) return;
            for (int i = start; i < end; i++) {
                CharNode node = allVisible.get(i);
                node.setBold(!node.isBold());
                client.sendFormat(node);
            }
            int savedStart = start, savedEnd = end;
            renderDocument(textPane.getCaretPosition());
            drawRemoteCursors();
            textPane.setSelectionStart(savedStart);
            textPane.setSelectionEnd(savedEnd);
        });

        italicBtn.addActionListener(e -> {
            int start = textPane.getSelectionStart();
            int end   = textPane.getSelectionEnd();
            if (start == end) return;
            List<CharNode> allVisible = getAllVisibleNodes();
            if (end > allVisible.size()) return;
            for (int i = start; i < end; i++) {
                CharNode node = allVisible.get(i);
                node.setItalic(!node.isItalic());
                client.sendFormat(node);
            }
            int savedStart = start, savedEnd = end;
            renderDocument(textPane.getCaretPosition());
            drawRemoteCursors();
            textPane.setSelectionStart(savedStart);
            textPane.setSelectionEnd(savedEnd);
        });

        undoBtn.addActionListener(e -> { if (client.isEditor()) { client.sendUndo(); textPane.requestFocusInWindow(); } });
        redoBtn.addActionListener(e -> { if (client.isEditor()) { client.sendRedo(); textPane.requestFocusInWindow(); } });
        importBtn.addActionListener(e -> onImport());
        exportBtn.addActionListener(e -> onExport());
        saveBtn.addActionListener(e -> onSave());
        historyBtn.addActionListener(e -> requestVersionHistory());

        // Sharing codes
        JLabel editorCodeLabel = new JLabel("  Editor: " + client.getEditorCode());
        editorCodeLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        editorCodeLabel.setForeground(new Color(0, 100, 180));
        JButton copyEditorBtn = makeToolbarButton("Copy Editor", buttonFont, new Insets(8, 10, 8, 10));
        copyEditorBtn.addActionListener(ev -> {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(client.getEditorCode()), null);
            copyEditorBtn.setText("Copied!");
            new javax.swing.Timer(1500, t -> copyEditorBtn.setText("Copy Editor")).start();
        });

        JLabel viewerCodeLabel = new JLabel("  Viewer: " + client.getViewerCode());
        viewerCodeLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        viewerCodeLabel.setForeground(new Color(120, 80, 180));
        JButton copyViewerBtn = makeToolbarButton("Copy Viewer", buttonFont, new Insets(8, 10, 8, 10));
        copyViewerBtn.addActionListener(ev -> {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(client.getViewerCode()), null);
            copyViewerBtn.setText("Copied!");
            new javax.swing.Timer(1500, t -> copyViewerBtn.setText("Copy Viewer")).start();
        });

        if (client.isEditor()) {
            toolBar.add(boldBtn);
            toolBar.add(italicBtn);
            toolBar.add(undoBtn);
            toolBar.add(redoBtn);
            toolBar.addSeparator();
            toolBar.add(importBtn);
            toolBar.add(exportBtn);
            toolBar.add(saveBtn);
            toolBar.add(historyBtn);
            toolBar.addSeparator();
            toolBar.add(editorCodeLabel);
            toolBar.add(copyEditorBtn);
        }
        toolBar.add(viewerCodeLabel);
        toolBar.add(copyViewerBtn);
        if (!client.isEditor()) {
            toolBar.addSeparator();
            toolBar.add(historyBtn);
        }
        frame.add(toolBar, BorderLayout.NORTH);

        // Right panel
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.add(createUsersPanel(), BorderLayout.NORTH);
        rightPanel.add(createCursorPanel(), BorderLayout.CENTER);
        rightPanel.add(createCommentsSidebar(), BorderLayout.SOUTH);
        frame.add(rightPanel, BorderLayout.EAST);

        // Text pane
        textPane = new JTextPane();
        textPane.setEditable(client.isEditor());
        textPane.addCaretListener(e -> {
            if (client != null) {
                client.sendCursor(textPane.getCaretPosition());
                updateActiveBlockFromCursor(textPane.getCaretPosition());
            }
        });
        highlighter = textPane.getHighlighter();

        JPopupMenu contextMenu = buildContextMenu();
        textPane.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) {
                    e.consume();
                    contextMenu.show(textPane, e.getX(), e.getY());
                }
            }
            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) {
                    e.consume();
                    contextMenu.show(textPane, e.getX(), e.getY());
                }
            }

        });
        // ── FILE: EditorUI.java ───────────────────────────────────────────────────
// ADD a PopupMenuListener so pasteBlockItem enables/disables dynamically
// Place this right after the two lines where the MouseListener is added
// (the mousePressed / mouseReleased block we added previously)

        contextMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                // Refresh enabled state of Paste Block every time menu opens
                for (int i = 0; i < contextMenu.getComponentCount(); i++) {
                    java.awt.Component comp = contextMenu.getComponent(i);
                    if (comp instanceof JMenuItem item
                            && "📌 Paste Block".equals(item.getText())) {
                        item.setEnabled(copiedBlockID != null);
                        break;
                    }
                }
            }
            @Override public void popupMenuWillBecomeInvisible(
                    javax.swing.event.PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(
                    javax.swing.event.PopupMenuEvent e) {}
        });
        textPane.setMargin(new Insets(20, 20, 20, 20));
        textPane.setFont(new Font("Segoe UI", Font.PLAIN, 16));
        JScrollPane scrollPane = new JScrollPane(textPane);
        frame.add(scrollPane, BorderLayout.CENTER);

        // Key listener
        textPane.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_C) { e.consume(); handleCopy(); return; }
                if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_A) { e.consume(); textPane.selectAll(); return; }
                if (!client.isEditor()) return;
                if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_V) { e.consume(); handlePaste(); return; }
                if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_X) { e.consume(); handleCut(); return; }
                if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_Z) { e.consume(); client.sendUndo(); return; }
                if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_Y) { e.consume(); client.sendRedo(); return; }
                if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
                    e.consume();
                    int cursorPosition = textPane.getCaretPosition();
                    if (cursorPosition > 0) {
                        CharNode deleted = globalDelete(cursorPosition - 1);
                        if (deleted != null) client.sendDeleteChar(deleted);
                        shiftRemoteCursors(cursorPosition - 1, -1);
                        renderDocument(cursorPosition - 1);
                        drawRemoteCursors();
                    }
                } else if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    e.consume();
                    int cursorPosition = textPane.getCaretPosition();
                    int safeIndex = Math.min(cursorPosition, getAllVisibleNodes().size());
                    CharNode inserted = globalInsert('\n', safeIndex);
                    if (inserted != null) client.sendInsertChar(inserted);
                    shiftRemoteCursors(safeIndex, +1);

                    // Auto-split if the active block exceeds MAX_LINES.
                    BlockID activeBlock = client.getActiveBlockID();
                    if (activeBlock != null && inserted != null) {
                        BlockNode blockNode = client.getLocalDoc().getBlock(activeBlock);
                        if (blockNode != null && blockNode.getLineCount() > MAX_LINES_PER_BLOCK) {
                            int localIndex = computeLocalIndex(activeBlock, inserted);
                            if (localIndex > 0) {
                                // FIX: capture newBlock and send its ID over the wire
                                BlockNode newBlock = client.getLocalDoc()
                                        .splitBlockAtCursor(activeBlock, localIndex);
                                if (newBlock != null) {
                                    client.sendSplitBlock(activeBlock, newBlock, localIndex);
                                    client.setActiveBlockID(newBlock.getId());
                                }
                            }
                        }
                    }
                    renderDocument(safeIndex + 1);
                    drawRemoteCursors();
                }
            }

            @Override
            public void keyTyped(KeyEvent e) {
                if (!client.isEditor()) return;
                e.consume();
                char typedChar = e.getKeyChar();
                if (e.isControlDown() || Character.isISOControl(typedChar)) { e.consume(); return; }
                int cursorPosition = textPane.getCaretPosition();
                int safeIndex = Math.min(cursorPosition, getAllVisibleNodes().size());
                CharNode inserted = globalInsert(typedChar, safeIndex);
                doc.InheritFormatting(safeIndex);
                if (inserted != null) client.sendInsertChar(inserted);
                shiftRemoteCursors(safeIndex, +1);
                renderDocument(safeIndex + 1);
                drawRemoteCursors();
            }
        });

        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                if (client != null) client.close();
            }
        });

        frame.setVisible(true);
    }

    // =========================================================================
    // Helper: compute LOCAL index of a CharNode inside its block
    // =========================================================================
    /**
     * Returns the visible-character position of {@code target} within its block.
     * "Visible" means non-deleted.  Used to compute the split index after an
     * auto-split triggered by pressing Enter.
     */
    private int computeLocalIndex(BlockID blockID, CharNode target) {
        BlockNode block = client.getLocalDoc().getBlock(blockID);
        if (block == null || block.getContent() == null) return -1;
        List<CharNode> chars = block.getContent().getOrderedNodes();
        int visible = 0;
        for (CharNode cn : chars) {
            if (cn.getID().equals(target.getID())) return visible;
            if (!cn.isDeleted()) visible++;
        }
        return -1;
    }

    // =========================================================================
    // Version history
    // =========================================================================
    private void requestVersionHistory() {
        String code = client.getEditorCode() != null ? client.getEditorCode() : client.getSessionCode();
        if (code == null) {
            JOptionPane.showMessageDialog(frame, "Not connected to a session.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        Operations op = new Operations();
        op.type = "GET_VERSIONS";
        op.sessionCode = code;
        client.send(op.toJson());
    }

    private void showVersionsDialog(String jsonPayload) {
        java.util.List<String> versions;
        try {
            versions = new com.google.gson.Gson().fromJson(
                    jsonPayload,
                    new com.google.gson.reflect.TypeToken<java.util.List<String>>(){}.getType()
            );
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, "Could not parse version list.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (versions == null || versions.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "No saved versions found.", "Version History", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JDialog dialog = new JDialog(frame, "⏱  Version History", true);
        dialog.setSize(540, 400);
        dialog.setLocationRelativeTo(frame);
        dialog.setLayout(new BorderLayout(10, 10));
        JLabel header = new JLabel("  " + versions.size() + " snapshot(s) available — newest last", SwingConstants.LEFT);
        header.setFont(new Font("Segoe UI", Font.BOLD, 13));
        header.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
        dialog.add(header, BorderLayout.NORTH);
        JPanel listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        for (int i = 0; i < versions.size(); i++) {
            final int versionIndex = i;
            String preview = buildVersionPreview(versions.get(i));
            int versionNumber = i + 1;
            JPanel row = new JPanel(new BorderLayout(10, 0));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
            row.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 3, 0, 0,
                            (i == versions.size() - 1) ? new Color(0, 120, 180) : new Color(160, 160, 160)),
                    BorderFactory.createEmptyBorder(8, 10, 8, 8)
            ));
            row.setBackground(i % 2 == 0 ? UIManager.getColor("Panel.background") : new Color(245, 248, 252));
            JLabel infoLabel = new JLabel("<html><b>Version " + versionNumber + "</b>"
                    + (i == versions.size() - 1 ? " (most recent)" : "")
                    + "<br><font color='gray' size='2'>" + preview + "</font></html>");
            infoLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            JButton restoreBtn = new JButton("Restore");
            restoreBtn.setFont(new Font("Segoe UI", Font.BOLD, 12));
            restoreBtn.setFocusable(false);
            restoreBtn.setEnabled(client.isEditor());
            restoreBtn.addActionListener(ev -> {
                int confirm = JOptionPane.showConfirmDialog(dialog,
                        "Restore Version " + versionNumber
                                + "?\n\nThis will overwrite the current document for ALL collaborators.",
                        "Confirm Restore", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (confirm != JOptionPane.YES_OPTION) return;
                String sc = client.getEditorCode() != null ? client.getEditorCode() : client.getSessionCode();
                Operations rollback = new Operations();
                rollback.type = "ROLLBACK_VERSION";
                rollback.sessionCode = sc;
                rollback.versionIndex = versionIndex;
                rollback.username = username;
                client.send(rollback.toJson());
                dialog.dispose();
                JOptionPane.showMessageDialog(frame, "Version " + versionNumber + " restored.", "Restored",
                        JOptionPane.INFORMATION_MESSAGE);
            });
            row.add(infoLabel, BorderLayout.CENTER);
            row.add(restoreBtn, BorderLayout.EAST);
            listPanel.add(row);
            listPanel.add(Box.createVerticalStrut(4));
        }
        JScrollPane scroll = new JScrollPane(listPanel);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        dialog.add(scroll, BorderLayout.CENTER);
        JButton closeBtn = new JButton("Close");
        closeBtn.setFocusable(false);
        closeBtn.addActionListener(ev -> dialog.dispose());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(closeBtn);
        dialog.add(south, BorderLayout.SOUTH);
        SwingUtilities.invokeLater(() -> {
            JScrollBar sb = scroll.getVerticalScrollBar();
            sb.setValue(sb.getMaximum());
        });
        dialog.setVisible(true);
    }

 private String buildVersionPreview(String crdtJson) {
        if (crdtJson == null || crdtJson.isBlank()) return "(empty)";
        StringBuilder sb = new StringBuilder();
        try {
            // Try multi-block format (new saves)
            BlockCRDT tempDoc = new BlockCRDT(0, new Clock());
            CrdtSerializer.loadDocumentJson(crdtJson, tempDoc);
            for (BlockNode bn : tempDoc.getOrderedNodes()) {
                if (bn.getContent() == null) continue;
                for (CharNode n : bn.getContent().getOrderedNodes()) {
                    if (!n.isDeleted()) sb.append(n.getValue());
                    if (sb.length() >= 80) break;
                }
                if (sb.length() >= 80) break;
            }
        } catch (Exception ex) {
            // Fall back to single-block legacy format
            try {
                CharCRDT crdt = CrdtSerializer.fromJson(crdtJson,
                        (int)(System.currentTimeMillis() % 100000));
                for (CharNode n : crdt.getOrderedNodes()) {
                    if (!n.isDeleted()) sb.append(n.getValue());
                    if (sb.length() >= 80) break;
                }
            } catch (Exception ex2) {
                return "(snapshot)";
            }
        }
        String text = sb.toString().replace('\n', ' ').trim();
        return text.isEmpty() ? "(empty)"
                : (text.length() > 75 ? text.substring(0, 75) + "…" : text);
    }    // =========================================================================
    // BLOCK OPERATIONS
    // =========================================================================
// ── FILE: EditorUI.java ───────────────────────────────────────────────────
// REPLACE buildContextMenu() entirely

    private JPopupMenu buildContextMenu() {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem addCommentItem = new JMenuItem("💬 Add Comment");
        addCommentItem.addActionListener(e -> {
            int start = textPane.getSelectionStart();
            int end   = textPane.getSelectionEnd();
            if (start == end) {
                JOptionPane.showMessageDialog(frame, "Highlight some text first.");
                return;
            }
            List<CharNode> visible = getAllVisibleNodes();
            if (end > visible.size() || start < 0) return;
            String commentText = JOptionPane.showInputDialog(frame,
                    "Enter comment:", "Add Comment", JOptionPane.PLAIN_MESSAGE);
            if (commentText == null || commentText.isBlank()) return;
            client.sendAddComment(commentText.trim(),
                    visible.get(start), visible.get(end - 1));
        });

        JMenuItem viewCommentsItem = new JMenuItem("📋 View Comments");
        viewCommentsItem.addActionListener(e -> {
            if (activeComments.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "No comments yet."); return;
            }
            StringBuilder sb = new StringBuilder();
            for (Comment c : activeComments.values())
                sb.append("• ").append(c.authorUsername)
                        .append(": ").append(c.text).append("\n");
            JOptionPane.showMessageDialog(frame, sb.toString(),
                    "Comments", JOptionPane.INFORMATION_MESSAGE);
        });

        menu.add(addCommentItem);
        menu.add(viewCommentsItem);

        if (client.isEditor()) {
            menu.addSeparator();

            JMenuItem moveUpItem = new JMenuItem("⬆ Move Block Up");
            moveUpItem.addActionListener(e -> handleMoveBlock(-1));

            JMenuItem moveDownItem = new JMenuItem("⬇ Move Block Down");
            moveDownItem.addActionListener(e -> handleMoveBlock(1));

            JMenuItem copyBlockItem = new JMenuItem("📋 Copy Block");
            copyBlockItem.addActionListener(e -> handleCopyBlock());

            JMenuItem pasteBlockItem = new JMenuItem("📌 Paste Block");
            pasteBlockItem.addActionListener(e -> handlePasteBlock());
            // Gray out paste if nothing is copied yet
            pasteBlockItem.setEnabled(copiedBlockID != null);

            JMenuItem splitBlockItem = new JMenuItem("✂ Split Block Here");
            splitBlockItem.addActionListener(e -> handleManualSplitBlock());

            JMenuItem deleteBlockItem = new JMenuItem("🗑 Delete Block");
            deleteBlockItem.addActionListener(e -> handleDeleteBlock());

            menu.add(moveUpItem);
            menu.add(moveDownItem);
            menu.addSeparator();
            menu.add(copyBlockItem);
            menu.add(pasteBlockItem);
            menu.addSeparator();
            menu.add(splitBlockItem);
            menu.add(deleteBlockItem);
        }
        return menu;
    }
// ── FILE: EditorUI.java ───────────────────────────────────────────────────
// REPLACE handleMoveBlock() entirely

// ── FILE: EditorUI.java ───────────────────────────────────────────────────
// REPLACE handleMoveBlock() entirely

// REPLACE handleMoveBlock() entirely in EditorUI.java

    private void handleMoveBlock(int direction) {
        BlockID activeBlockID = client.getActiveBlockID();
        if (activeBlockID == null) {
            JOptionPane.showMessageDialog(frame, "No active block selected.");
            return;
        }

        BlockCRDT blockDoc = client.getLocalDoc();
        List<BlockNode> ordered = blockDoc.getOrderedNodes();
        if (ordered.isEmpty()) return;

        // Find current index in live (non-deleted) list
        int currentIdx = -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).getId().equals(activeBlockID)) {
                currentIdx = i;
                break;
            }
        }
        if (currentIdx == -1) {
            JOptionPane.showMessageDialog(frame, "Could not locate block.");
            return;
        }

        int targetIdx = currentIdx + direction;
        if (targetIdx < 0) {
            JOptionPane.showMessageDialog(frame, "Already at top.");
            return;
        }
        if (targetIdx >= ordered.size()) {
            JOptionPane.showMessageDialog(frame, "Already at bottom.");
            return;
        }

        BlockNode sourceBlock     = ordered.get(currentIdx);
        BlockNode targetLiveBlock = ordered.get(targetIdx);

        // -----------------------------------------------------------------------
        // Determine anchor: the live block that the moved block will go AFTER.
        // anchor == null means "insert at very top".
        //
        // Moving UP   (direction == -1):
        //   The moved block lands before targetLiveBlock.
        //   anchor = the live block just before targetLiveBlock, or null if none.
        //
        // Moving DOWN (direction == +1):
        //   The moved block lands after targetLiveBlock.
        //   anchor = targetLiveBlock itself.
        // -----------------------------------------------------------------------
        BlockID anchorID;
        if (direction == -1) {
            // Moving up: anchor is the live block before targetLiveBlock
            if (targetIdx == 0) {
                anchorID = null; // goes to very top
            } else {
                anchorID = ordered.get(targetIdx - 1).getId();
            }
        } else {
            // Moving down: anchor is targetLiveBlock
            anchorID = targetLiveBlock.getId();
        }

        // -----------------------------------------------------------------------
        // Build fresh content copy with new char IDs
        // -----------------------------------------------------------------------
        CharCRDT newContent = new CharCRDT(blockDoc.getUserid(), blockDoc.getClock());
        CharID lastParentID = newContent.rootID;
        for (CharNode cn : sourceBlock.getChars()) {
            CharNode inserted = newContent.insertNode(lastParentID, cn.getValue());
            if (inserted != null) {
                inserted.setBold(cn.isBold());
                inserted.setItalic(cn.isItalic());
                lastParentID = inserted.getID();
            }
        }

        // -----------------------------------------------------------------------
        // Compute local insert position from anchor
        // -----------------------------------------------------------------------
        List<BlockNode> rawChildren = blockDoc.getRootChildren();
        int insertPos;
        if (anchorID == null) {
            insertPos = 0;
        } else {
            BlockNode anchorBlock = blockDoc.getBlock(anchorID);
            int rawAnchorIdx = rawChildren.indexOf(anchorBlock);
            insertPos = (rawAnchorIdx == -1) ? rawChildren.size() : rawAnchorIdx + 1;
        }

        // -----------------------------------------------------------------------
        // Insert new block locally at computed position
        // -----------------------------------------------------------------------
        BlockNode movedBlock = blockDoc.insertBlockAfterAnchor(null, newContent);
        if (movedBlock == null) {
            JOptionPane.showMessageDialog(frame, "Move failed.");
            return;
        }

        // -----------------------------------------------------------------------
        // Soft-delete the original block (no merge side effects)
        // -----------------------------------------------------------------------
        blockDoc.softDeleteNode(activeBlockID);

        // -----------------------------------------------------------------------
        // Send ONE atomic message so remote peers replay identically using anchor
        // -----------------------------------------------------------------------
        client.sendBeginGroup();
        client.sendMoveBlockExec(movedBlock, anchorID, activeBlockID);
        client.sendEndGroup();

        client.setActiveBlockID(movedBlock.getId());

        SwingUtilities.invokeLater(() -> {
            renderDocument(0);
            drawRemoteCursors();
            updateRemoteCursorDisplay();
        });
    }
    private void handleCopyBlock() {
        BlockID activeBlockID = client.getActiveBlockID();
        if (activeBlockID == null) {
            JOptionPane.showMessageDialog(frame, "No active block to copy.");
            return;
        }
        BlockNode sourceBlock = client.getLocalDoc().getBlock(activeBlockID);
        if (sourceBlock == null || sourceBlock.isDeleted()) {
            JOptionPane.showMessageDialog(frame, "Block not found.");
            return;
        }
        copiedBlockID = activeBlockID;
        // Briefly flash a status message in the frame title to confirm copy
        String originalTitle = frame.getTitle();
        frame.setTitle("✅ Block Copied!  —  " + originalTitle);
        new javax.swing.Timer(1500, ev -> frame.setTitle(originalTitle)).start();
    }
    // ── FILE: EditorUI.java ───────────────────────────────────────────────────
// 7. ADD this new method handlePasteBlock() anywhere in the private methods section

// ── FILE: EditorUI.java ───────────────────────────────────────────────────
// REPLACE handlePasteBlock() entirely

    private void handlePasteBlock() {
        if (copiedBlockID == null) {
            JOptionPane.showMessageDialog(frame,
                    "No block copied. Right-click and choose 'Copy Block' first.");
            return;
        }

        BlockCRDT blockDoc = client.getLocalDoc();
        BlockNode sourceBlock = blockDoc.getBlock(copiedBlockID);
        if (sourceBlock == null || sourceBlock.isDeleted()
                || sourceBlock.getContent() == null) {
            JOptionPane.showMessageDialog(frame, "Copied block no longer exists.");
            copiedBlockID = null;
            return;
        }

        // Find insertion position: after active block in raw list
        BlockID   activeBlockID = client.getActiveBlockID();
        List<BlockNode> rawChildren = blockDoc.getRootChildren();
        BlockNode activeBlock = (activeBlockID != null)
                ? blockDoc.getBlock(activeBlockID) : null;

        int insertPos;
        if (activeBlock != null && !activeBlock.isDeleted()) {
            int rawIdx = rawChildren.indexOf(activeBlock);
            insertPos  = (rawIdx == -1) ? rawChildren.size() : rawIdx + 1;
        } else {
            insertPos = rawChildren.size();
        }

        // Build fresh content with new IDs
        CharCRDT newContent = new CharCRDT(blockDoc.getUserid(), blockDoc.getClock());
        CharID lastParentID = newContent.rootID;
        for (CharNode cn : sourceBlock.getChars()) {
            CharNode inserted = newContent.insertNode(lastParentID, cn.getValue());
            if (inserted != null) {
                inserted.setBold(cn.isBold());
                inserted.setItalic(cn.isItalic());
                lastParentID = inserted.getID();
            }
        }

        BlockID anchorID = (activeBlock != null && !activeBlock.isDeleted())
                ? activeBlockID : null;

        // Insert locally using ghost-safe anchor insert
        BlockNode pastedBlock = blockDoc.insertBlockAfterAnchor(anchorID, newContent);
        if (pastedBlock == null) {
            JOptionPane.showMessageDialog(frame, "Paste failed."); return;
        }

// Send ONE atomic message
        client.sendBeginGroup();
        client.sendMoveBlockExec(pastedBlock, anchorID, null);
        client.sendEndGroup();

        client.setActiveBlockID(pastedBlock.getId());

        SwingUtilities.invokeLater(() -> {
            renderDocument(0);
            drawRemoteCursors();
            updateRemoteCursorDisplay();
        });
    }
    // -------------------------------------------------------------------------
    // FIX: handleManualSplitBlock
    // Uses sendSplitBlock(blockID, newBlock, localIndex) so every remote peer
    // receives the exact BlockID of the new second block and recreates it with
    // the same identity → characters typed there are visible on all peers.
    // -------------------------------------------------------------------------
    private void handleManualSplitBlock() {
        BlockID activeBlockID = client.getActiveBlockID();
        if (activeBlockID == null) { JOptionPane.showMessageDialog(frame, "No active block."); return; }

        int globalCursorPos = textPane.getCaretPosition();
        BlockCRDT blockDoc  = client.getLocalDoc();
        List<BlockNode> ordered = blockDoc.getOrderedNodes();

        int globalOffset = 0;
        int localIndex   = -1;
        BlockNode activeBlock = null;

        for (BlockNode block : ordered) {
            if (block.isDeleted() || block.getContent() == null) continue;
            List<CharNode> visibleChars = new java.util.ArrayList<>();
            for (CharNode cn : block.getContent().getOrderedNodes())
                if (!cn.isDeleted()) visibleChars.add(cn);
            int visibleCount = visibleChars.size();

            if (block.getId().equals(activeBlockID)) {
                activeBlock = block;
                // localIndex = cursor position relative to start of this block
                localIndex  = Math.max(0, Math.min(globalCursorPos - globalOffset, visibleCount));
                break;
            }
            globalOffset += visibleCount;
        }

        if (activeBlock == null) {
            JOptionPane.showMessageDialog(frame, "Cannot find active block.");
            return;
        }

        // Count visible chars in active block
        int totalVisible = (int) activeBlock.getContent().getOrderedNodes()
                .stream().filter(cn -> !cn.isDeleted()).count();

        if (localIndex < 0 || localIndex >= totalVisible) {
            JOptionPane.showMessageDialog(frame,
                    "Cannot split at cursor – place cursor between characters inside the block.");
            return;
        }

        // Insert '\n' at split point so the split behaves like pressing Enter
        CharID parentID = (localIndex == 0)
                ? activeBlock.getContent().rootID
                : activeBlock.getChars().get(localIndex - 1).getID();
        CharNode newline = activeBlock.getContent().insertNode(parentID, '\n');
        if (newline != null) client.sendInsertChar(newline);

        // Now split AFTER the newline (localIndex + 1)
        BlockNode newBlock = blockDoc.splitBlockAtCursor(activeBlockID, localIndex + 1);
        if (newBlock != null) {
            client.sendSplitBlock(activeBlockID, newBlock, localIndex + 1);
            client.setActiveBlockID(newBlock.getId());
            renderDocument(globalCursorPos + 1);
            drawRemoteCursors();
            updateRemoteCursorDisplay();
            JOptionPane.showMessageDialog(frame, "Block split successfully!");
        } else {
            JOptionPane.showMessageDialog(frame, "Split failed.");
        }
    }


    private void handleDeleteBlock() {
        BlockID activeBlockID = client.getActiveBlockID();
        if (activeBlockID == null) { JOptionPane.showMessageDialog(frame, "No active block."); return; }
        int confirm = JOptionPane.showConfirmDialog(frame,
                "Delete this entire block?\n(Use Ctrl+Z for character-level undo.)",
                "Delete Block", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        BlockCRDT blockDoc = client.getLocalDoc();
        client.sendDeleteBlock(activeBlockID);
        blockDoc.deleteNode(activeBlockID);

        List<BlockNode> remaining = blockDoc.getOrderedNodes();
        if (remaining.isEmpty()) {
            BlockNode newBlock = blockDoc.insertTopLevelBlock(
                    new CharCRDT(blockDoc.getUserid(), blockDoc.getClock()));
            client.setActiveBlockID(newBlock.getId());
            client.sendInsertBlock(newBlock);
        } else {
            client.setActiveBlockID(remaining.get(0).getId());
        }
        renderDocument(0);
        drawRemoteCursors();
        updateRemoteCursorDisplay();
    }

    // =========================================================================
    // Render helpers
    // =========================================================================

    private List<CharNode> getAllVisibleNodes() {
        List<CharNode> result = new java.util.ArrayList<>();
        List<BlockNode> liveBlocks = client.getLocalDoc().getOrderedNodes();
        for (BlockNode block : liveBlocks) {
            if (block.isDeleted() || block.getContent() == null) continue;
            for (CharNode node : block.getContent().getOrderedNodes())
                if (!node.isDeleted()) result.add(node);
        }
        if (result.isEmpty() && liveBlocks.isEmpty()) result = doc.GetVisibleNodes();
        return result;
    }

    private CharNode globalDelete(int globalIndex) {
        List<CharNode> allNodes = getAllVisibleNodes();
        if (globalIndex >= 0 && globalIndex < allNodes.size()) {
            CharNode node = allNodes.get(globalIndex);
            node.SetDeleted(true);
            return node;
        }
        return null;
    }

    private CharNode globalInsert(char value, int globalIndex) {
        List<BlockNode> liveBlocks = client.getLocalDoc().getOrderedNodes();
        if (liveBlocks.isEmpty()) return doc.LocalInsert(value, globalIndex);

        int accumulated = 0;
        for (BlockNode block : liveBlocks) {
            if (block.isDeleted() || block.getContent() == null) continue;
            List<CharNode> visibleInBlock = new java.util.ArrayList<>();
            for (CharNode cn : block.getContent().getOrderedNodes())
                if (!cn.isDeleted()) visibleInBlock.add(cn);
            int blockSize    = visibleInBlock.size();
            boolean isLast   = (block == liveBlocks.get(liveBlocks.size() - 1));
            if (globalIndex <= accumulated + blockSize || isLast) {
                int localIdx = Math.max(0, Math.min(globalIndex - accumulated, blockSize));
                CharID parentID = (localIdx == 0)
                        ? block.getContent().rootID
                        : visibleInBlock.get(localIdx - 1).getID();
                return block.getContent().insertNode(parentID, value);
            }
            accumulated += blockSize;
        }
        BlockNode last = liveBlocks.get(liveBlocks.size() - 1);
        return last.getContent().insertNode(last.getContent().rootID, value);
    }

    private void renderDocument(int newCursorPosition) {
        List<CharNode> nodes = getAllVisibleNodes();
        textPane.setText("");
        StyledDocument styledDoc = textPane.getStyledDocument();
        try {
            for (CharNode node : nodes) {
                SimpleAttributeSet style = new SimpleAttributeSet();
                StyleConstants.setBold(style, node.isBold());
                StyleConstants.setItalic(style, node.isItalic());
                styledDoc.insertString(styledDoc.getLength(), String.valueOf(node.getValue()), style);
            }
        } catch (Exception e) { e.printStackTrace(); }
        textPane.setCaretPosition(Math.min(newCursorPosition, styledDoc.getLength()));
        drawCommentHighlights();
    }

    // =========================================================================
    // Panels
    // =========================================================================
    private JPanel createUsersPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(200, 150));
        panel.setBorder(BorderFactory.createTitledBorder("Active Users"));
        usersListModel = new DefaultListModel<>();
        usersList = new JList<>(usersListModel);
        usersList.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        panel.add(new JScrollPane(usersList), BorderLayout.CENTER);
        return panel;
    }

    private JPanel createCursorPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(220, 100));
        panel.setBorder(BorderFactory.createTitledBorder("Remote Cursors"));
        cursorListModel = new DefaultListModel<>();
        cursorList = new JList<>(cursorListModel);
        cursorList.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        panel.add(new JScrollPane(cursorList), BorderLayout.CENTER);
        return panel;
    }

    private JPanel createCommentsSidebar() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(220, 250));
        panel.setBorder(BorderFactory.createTitledBorder("Comments"));
        commentsListPanel = new JPanel();
        commentsListPanel.setLayout(new BoxLayout(commentsListPanel, BoxLayout.Y_AXIS));
        panel.add(new JScrollPane(commentsListPanel), BorderLayout.CENTER);
        return panel;
    }

    // =========================================================================
    // Helpers
    // =========================================================================
    private void updateActiveBlockFromCursor(int cursorPos) {
        int currentCount = 0;
        for (BlockNode block : client.getLocalDoc().getOrderedNodes()) {
            if (block.isDeleted() || block.getContent() == null) continue;
            int visibleInBlock = 0;
            for (CharNode cn : block.getContent().getOrderedNodes())
                if (!cn.isDeleted()) visibleInBlock++;
            currentCount += visibleInBlock;
            if (cursorPos <= currentCount || visibleInBlock == 0) {
                client.setActiveBlockID(block.getId());
                return;
            }
        }
    }

    private JButton makeToolbarButton(String text, Font font, Insets margin) {
        JButton btn = new JButton(text);
        btn.setFont(font);
        btn.setMargin(margin);
        btn.setFocusable(false);
        return btn;
    }

    public void updateActiveUsers(List<String> users) {
        usersListModel.clear();
        users.forEach(usersListModel::addElement);
        remoteCursors.keySet().removeIf(u -> !users.contains(u));
        updateRemoteCursorDisplay();
        drawRemoteCursors();
    }

    private void updateRemoteCursorDisplay() {
        cursorListModel.clear();
        remoteCursors.forEach((u, pos) -> cursorListModel.addElement(u + " → " + pos));
    }

    private Color getUserColor(String user) {
        return CURSOR_COLORS[Math.abs(user.toLowerCase().hashCode()) % CURSOR_COLORS.length];
    }

    private void drawRemoteCursors() {
        cursorHighlights.values().forEach(highlighter::removeHighlight);
        cursorHighlights.clear();
        int length = textPane.getDocument().getLength();
        for (Map.Entry<String, Integer> entry : remoteCursors.entrySet()) {
            String user = entry.getKey();
            int pos = Math.max(0, Math.min(entry.getValue(), length));
            if (length == 0) continue;
            try {
                int p0 = Math.min(pos, length - 1);
                Object tag = highlighter.addHighlight(p0, p0 + 1,
                        new CursorPainter(getUserColor(user), pos));
                cursorHighlights.put(user, tag);
            } catch (BadLocationException ignored) {}
        }
        textPane.repaint();
    }

    private void shiftRemoteCursors(int atIndex, int delta) {
        remoteCursors.replaceAll((u, pos) -> pos > atIndex ? pos + delta : pos);
    }

    private JPanel buildCommentCard(Comment c) {
        JPanel card = new JPanel(new BorderLayout(5, 5));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0,
                        c.resolved ? Color.GRAY : new Color(255, 200, 0)),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        card.setBackground(c.resolved ? new Color(245, 245, 245) : Color.WHITE);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));
        JLabel header = new JLabel("<html><b>" + c.authorUsername + "</b> &nbsp;"
                + new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date(c.timestamp)) + "</html>");
        header.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        JTextArea textArea = new JTextArea(c.text);
        textArea.setEditable(false); textArea.setLineWrap(true); textArea.setWrapStyleWord(true);
        textArea.setBackground(card.getBackground());
        textArea.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        JButton resolveBtn = new JButton(c.resolved ? "Resolved ✓" : "Resolve");
        resolveBtn.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        resolveBtn.setFocusable(false);
        resolveBtn.addActionListener(e -> client.sendResolveComment(c.id));
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        bottom.setBackground(card.getBackground());
        bottom.add(resolveBtn);
        card.add(header, BorderLayout.NORTH);
        card.add(textArea, BorderLayout.CENTER);
        card.add(bottom, BorderLayout.SOUTH);
        return card;
    }

    private void drawCommentHighlights() {
        commentHighlights.values().forEach(highlighter::removeHighlight);
        commentHighlights.clear();
        if (commentsListPanel != null) commentsListPanel.removeAll();

        List<CharNode> visible = getAllVisibleNodes();
        for (Comment c : activeComments.values()) {
            int startIdx = -1, endIdx = -1;
            for (int i = 0; i < visible.size(); i++) {
                CharNode n = visible.get(i);
                if (n.getID().getUserID() == c.startCharUser && n.getID().getClock() == c.startCharClock) startIdx = i;
                if (n.getID().getUserID() == c.endCharUser   && n.getID().getClock() == c.endCharClock)   endIdx   = i + 1;
            }
            if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
                try {
                    Color color = c.resolved ? new Color(220, 220, 220, 120) : new Color(255, 255, 0, 150);
                    Object tag = highlighter.addHighlight(startIdx, endIdx,
                            new DefaultHighlighter.DefaultHighlightPainter(color));
                    commentHighlights.put(c.id, tag);
                } catch (BadLocationException ignored) {}
            }
            if (commentsListPanel != null) {
                commentsListPanel.add(buildCommentCard(c));
                commentsListPanel.add(Box.createVerticalStrut(5));
            }
        }
        if (commentsListPanel != null) { commentsListPanel.revalidate(); commentsListPanel.repaint(); }
    }

    // =========================================================================
    // Import / Export / Save
    // =========================================================================
    private void onImport() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Text files (*.txt)", "txt"));
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        String content;
        try { content = java.nio.file.Files.readString(chooser.getSelectedFile().toPath()); }
        catch (java.io.IOException ex) {
            JOptionPane.showMessageDialog(frame, "Failed to read file: " + ex.getMessage()); return;
        }
        JDialog progressDialog = new JDialog(frame, "Importing...", false);
        JProgressBar progressBar = new JProgressBar(0, content.length());
        progressBar.setStringPainted(true);
        progressBar.setString("0 / " + content.length() + " characters");
        JPanel progressPanel = new JPanel(new BorderLayout(5, 5));
        progressPanel.setBorder(BorderFactory.createEmptyBorder(10, 15, 15, 15));
        progressPanel.add(new JLabel("Importing file, please wait...", SwingConstants.CENTER), BorderLayout.NORTH);
        progressPanel.add(progressBar, BorderLayout.CENTER);
        progressDialog.add(progressPanel);
        progressDialog.setSize(350, 100);
        progressDialog.setLocationRelativeTo(frame);
        progressDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        progressDialog.setVisible(true);
        final String finalContent = content;
        SwingWorker<Void, Integer> worker = new SwingWorker<>() {
            @Override protected Void doInBackground() {
                BlockCRDT blockDoc = client.getLocalDoc();
                List<BlockNode> newBlocks = blockDoc.importText(finalContent);
                int charCount = 0;
                for (BlockNode block : newBlocks) {
                    client.sendInsertBlock(block);
                    for (CharNode cn : block.getChars()) { client.sendInsertChar(cn); publish(++charCount); }
                }
                if (!newBlocks.isEmpty()) client.setActiveBlockID(newBlocks.get(newBlocks.size()-1).getId());
                return null;
            }
            @Override protected void process(java.util.List<Integer> chunks) {
                int latest = chunks.get(chunks.size()-1);
                progressBar.setValue(latest);
                progressBar.setString(latest + " / " + finalContent.length() + " characters");
            }
            @Override protected void done() {
                progressDialog.dispose();
                renderDocument(getAllVisibleNodes().size());
                drawRemoteCursors();
                JOptionPane.showMessageDialog(frame,
                        "Import complete! " + finalContent.length() + " characters added.",
                        "Done", JOptionPane.INFORMATION_MESSAGE);
            }
        };
        worker.execute();
    }

    private void onExport() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Text files (*.txt)", "txt"));
        chooser.setSelectedFile(new java.io.File("document.txt"));
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        java.io.File file = chooser.getSelectedFile();
        if (!file.getName().endsWith(".txt")) file = new java.io.File(file.getAbsolutePath() + ".txt");
        try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(file))) {
            for (CharNode node : getAllVisibleNodes()) {
                if (node.isBold() && node.isItalic()) pw.print("***" + node.getValue() + "***");
                else if (node.isBold())   pw.print("**" + node.getValue() + "**");
                else if (node.isItalic()) pw.print("*"  + node.getValue() + "*");
                else pw.print(node.getValue());
            }
            JOptionPane.showMessageDialog(frame, "Exported successfully to: " + file.getName());
        } catch (java.io.IOException ex) {
            JOptionPane.showMessageDialog(frame, "Failed to export: " + ex.getMessage());
        }
    }

// ── FILE: EditorUI.java ───────────────────────────────────────────────────
// REPLACE onSave() entirely

    private void onSave() {
        BlockCRDT blockDoc = client.getLocalDoc();
        if (blockDoc == null) {
            JOptionPane.showMessageDialog(frame, "Not connected to a session.");
            return;
        }
        // Ensure username is set on the client before saving
        String docJson = CrdtSerializer.toDocumentJson(blockDoc);

        // sendSaveDoc already sets ownerUsername = username from client field
        // Use the overload that also sends the document name
        String currentTitle = frame.getTitle();
        // Extract doc name from frame title: "📄 NAME  |  username..."
        String docName = "Untitled";
        if (currentTitle.contains("|")) {
            String part = currentTitle.substring(
                    currentTitle.indexOf("📄") + 2,
                    currentTitle.indexOf("|")).trim();
            if (!part.isBlank()) docName = part;
        }

        client.sendSaveDoc(docJson, docName);
        JOptionPane.showMessageDialog(frame, "Document saved successfully.");
    }

    // =========================================================================
    // Copy / Paste / Cut
    // =========================================================================
    private String cleanClipboardText(String text) {
        if (text == null) return "";
        text = text.replace("\r\n", "\n").replace("\r", "\n");
        StringBuilder cleaned = new StringBuilder();
        for (char ch : text.toCharArray())
            if (!Character.isISOControl(ch) || ch == '\n' || ch == '\t') cleaned.append(ch);
        return cleaned.toString();
    }

    private void handleCopy() {
        String sel = cleanClipboardText(textPane.getSelectedText());
        if (sel == null || sel.isEmpty()) return;
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sel), null);
    }

    private void handlePaste() {
        if (!client.isEditor()) return;
        try {
            String pasted = cleanClipboardText((String) java.awt.Toolkit.getDefaultToolkit()
                    .getSystemClipboard().getData(DataFlavor.stringFlavor));
            if (pasted.isEmpty()) return;

            int cursorPosition = textPane.getCaretPosition();
            int safeIndex = Math.min(cursorPosition, getAllVisibleNodes().size());

            // Wrap entire paste as one atomic undo group
            client.sendBeginGroup();
            for (int i = 0; i < pasted.length(); i++) {
                CharNode inserted = globalInsert(pasted.charAt(i), safeIndex + i);
                if (inserted != null) {
                    doc.InheritFormatting(safeIndex + i);
                    client.sendInsertChar(inserted);
                }
            }
            client.sendEndGroup();

            renderDocument(safeIndex + pasted.length());
            drawRemoteCursors();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, "Paste failed");
        }
    }
    private void handleCut() {
        if (!client.isEditor()) return;
        int start = textPane.getSelectionStart(), end = textPane.getSelectionEnd();
        if (start == end) return;
        String sel = cleanClipboardText(textPane.getSelectedText());
        if (sel == null) return;
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sel), null);
        for (int i = end - 1; i >= start; i--) {
            CharNode deleted = globalDelete(i);
            if (deleted != null) client.sendDeleteChar(deleted);
        }
        renderDocument(start);
        drawRemoteCursors();
    }

    // =========================================================================
    // Cursor painter
    // =========================================================================
    private static class CursorPainter implements Highlighter.HighlightPainter {
        private final Color color;
        private final int cursorPos;
        CursorPainter(Color color, int cursorPos) { this.color = color; this.cursorPos = cursorPos; }
        @Override
        public void paint(Graphics g, int p0, int p1, Shape bounds, JTextComponent c) {
            try {
                Rectangle r = c.modelToView(cursorPos);
                if (r != null) { g.setColor(color); g.fillRect(r.x, r.y, 3, r.height); }
            } catch (BadLocationException ignored) {}
        }
    }
}