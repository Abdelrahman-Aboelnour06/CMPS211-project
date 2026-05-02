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


    private JFrame frame;
    private JTextPane textPane;
    private String username;
    private String sessionCode;
    private int pendingUndoRedoCount = 0;
    //private DummySessionService sessionService;
    private Client client;
    private DefaultListModel<String> usersListModel;
    private JList<String> usersList;
    //cursor
    private java.util.Map<String, Integer> remoteCursors = new java.util.HashMap<>();
    private DefaultListModel<String> cursorListModel;
    private JList<String> cursorList;
    private Highlighter highlighter;
    private Map<String, Object> cursorHighlights = new HashMap<>();
    private Map<String, Color> userColors = new HashMap<>();
    private final Map<String, Color> assignedColors = new java.util.LinkedHashMap<>();
    private final Color[] CURSOR_COLORS = {
            new Color(255, 105, 180), // pink
            new Color(30, 144, 255),  // blue
            new Color(50, 205, 50),   // green
            new Color(255, 165, 0)    // orange
    };

    //Comments

    private final java.util.Map<String, Comment> activeComments    = new java.util.LinkedHashMap<>();
    private final Map<String, Object>             commentHighlights = new HashMap<>();
    private JPanel commentsListPanel;

    //private SessionUsersListener usersListener;

    // 1. Declare your Document manager
    private Document doc;

    public EditorUI(String username, String sessionCode, Client client,String documentName) {

        // --- MAKE IT LOOK MODERN ---
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }
        // 2. Initialize the Document with userID 1
        //doc = new Document(1);

       // this.doc = new Document(new java.util.Random().nextInt(1000));

        // Use the Client's CRDT so UI and network share the same data
        CharCRDT sharedCRDT = client.getActiveCharCRDT();
        this.doc = new Document(sharedCRDT);

        this.username = username;
        this.sessionCode = sessionCode;
        //this.sessionService = sessionService;
        this.client = client;

        client.setMessageListener(op -> {
            SwingUtilities.invokeLater(() -> {

                switch (op.type) {

                    case "ACTIVE_USERS" -> {
                        List<String> users =
                                new com.google.gson.Gson().fromJson(
                                        op.payload,
                                        new com.google.gson.reflect.TypeToken<List<String>>() {}.getType()
                                );
                        updateActiveUsers(users);
                    }

                   /* case "INSERT_CHAR", "DELETE_CHAR" -> {
                        renderDocument(textPane.getCaretPosition());

                        drawRemoteCursors();
                    }*/

                    case "INSERT_CHAR", "DELETE_CHAR", "UNDELETE_CHAR","FORMAT_CHAR" -> {


                        int caret = textPane.getCaretPosition();

                        if ("UNDELETE_CHAR".equals(op.type)) {
                            caret += 1; // redo insert → move right
                        }
                        renderDocument(caret);
                        drawRemoteCursors();
                    }

                    /*case "SESSION_JOINED" -> {
                        // Small delay to let all replayed ops arrive and apply first
                        new javax.swing.Timer(300, e -> {
                            renderDocument(0);
                            drawRemoteCursors();
                        }) {{ setRepeats(false); }}.start();
                    }*/

                    case "SESSION_JOINED" -> {
                        // Poll every 200ms until content appears, max 5 seconds
                        javax.swing.Timer poller = new javax.swing.Timer(200, null);
                        int[] attempts = {0};
                        poller.addActionListener(ev -> {
                            attempts[0]++;
                            int savedCaret=textPane != null ? textPane.getCaretPosition() : 0;
                            renderDocument(savedCaret);
                            drawRemoteCursors();
                            // Stop after content appears OR after 5 seconds
                            if (!doc.GetVisibleNodes().isEmpty() || attempts[0] >= 25) {
                                poller.stop();
                            }
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
                        else activeComments.clear(); // auto bulk delete
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

                }

            });
        });
        // ADD THIS — render whatever is already in the CRDT right now
        SwingUtilities.invokeLater(() -> {
            renderDocument(0);
            drawRemoteCursors();
        });
        // Startup poller: replayed INSERT_CHAR ops arrive on the WebSocket thread
        // concurrently with EditorUI construction on the EDT. Poll every 250 ms for
        // up to 5 seconds so we catch any ops that land after the first render.
        /*javax.swing.Timer startupPoller = new javax.swing.Timer(250, null);
        int[] pollCount = {0};
        startupPoller.addActionListener(ev -> {
            pollCount[0]++;
            renderDocument(0);
            drawRemoteCursors();
            if (pollCount[0] >= 20) startupPoller.stop(); // stop after 5 seconds
        });
        startupPoller.start();
        client.requestActiveUsers();*/

        javax.swing.Timer startupPoller = new javax.swing.Timer(250, null);
        int[] pollCount = {0};
        startupPoller.addActionListener(ev -> {
            if (!client.isOpen()) {
                startupPoller.stop();
                return;
            }
            pollCount[0]++;
            // Stop as soon as user starts typing
            if (!doc.GetVisibleNodes().isEmpty() && pollCount[0] > 2) {
                startupPoller.stop();
                return;
            }
            int savedCaret = textPane != null ? textPane.getCaretPosition() : 0;
            renderDocument(savedCaret);
            drawRemoteCursors();
            if (pollCount[0] >= 20) startupPoller.stop();
        });
        startupPoller.start();
        client.requestActiveUsers();

        client.sendGetComments();

        // ... use the username for the Window Title ...
        //frame = new JFrame("Collaborative Editor - " + username + " (" + sessionCode + ")");
        //frame = new JFrame(documentName + " — " + username + " (" + sessionCode + ")");

        frame = new JFrame(
                "📄 " + documentName + "  |  " + username +
                        (client.isEditor() ? "  —  EDITOR" : "  —  VIEWER [READ ONLY]")
        );

        // --- SETUP THE WINDOW ---
        //frame = new JFrame("Collaborative Text Editor");


        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(800, 600);
        frame.setLayout(new BorderLayout());


        //setting up toolbar
        // -
        JToolBar toolBar = new JToolBar();
        // Make it so the user can't drag the toolbar out of the window
        toolBar.setFloatable(false);

        // Create the buttons
        JButton boldBtn = new JButton("Bold");
        JButton italicBtn = new JButton("Italic");
        JButton undoBtn = new JButton("Undo");
        JButton redoBtn = new JButton("Redo");

        // 1. Create a bigger, clearer font for the buttons
        Font buttonFont = new Font("Segoe UI", Font.BOLD, 14);

        // 2. Apply font and "Meat" (Padding) to Bold button
        boldBtn.setFont(buttonFont);
        boldBtn.setMargin(new Insets(10, 25, 10, 25)); // Top, Left, Bottom, Right

        // 3. Apply font and "Meat" (Padding) to Italic button
        italicBtn.setFont(buttonFont);
        italicBtn.setMargin(new Insets(10, 25, 10, 25));

        // so that don't take control of the cursor
        boldBtn.setFocusable(false);
        italicBtn.setFocusable(false);

        undoBtn.setFocusable(false);
        redoBtn.setFocusable(false);

        undoBtn.setFont(buttonFont);
        redoBtn.setFont(buttonFont);

        undoBtn.setMargin(new Insets(10, 25, 10, 25));
        redoBtn.setMargin(new Insets(10, 25, 10, 25));

        // --- BUTTON CLICK LOGIC ---
        // --- BUTTON CLICK LOGIC ---
        boldBtn.addActionListener(e -> {
            int start = textPane.getSelectionStart();
            int end = textPane.getSelectionEnd();

            if (start != end) {
                doc.FormatSelection(start, end, true);

                // broadcast each formatted node
                List<CharNode> visible = doc.GetVisibleNodes();
                for (int i = start; i < end; i++) client.sendFormat(visible.get(i));
                renderDocument(textPane.getCaretPosition());
                drawRemoteCursors();

                // Re-highlight the exact same text so it stays selected!
                textPane.setSelectionStart(start);
                textPane.setSelectionEnd(end);
            }
        });

        italicBtn.addActionListener(e -> {
            int start = textPane.getSelectionStart();
            int end = textPane.getSelectionEnd();

            if (start != end) {
                doc.FormatSelection(start, end, false);
                List<CharNode> visible = doc.GetVisibleNodes();
                for (int i = start; i < end; i++) client.sendFormat(visible.get(i));

                renderDocument(textPane.getCaretPosition());
                drawRemoteCursors();

                // Re-highlight the exact same text so it stays selected!
                textPane.setSelectionStart(start);
                textPane.setSelectionEnd(end);
            }
        });

        undoBtn.addActionListener(e -> {
            if (client.isEditor()) {

                client.sendUndo();
                textPane.requestFocusInWindow();
            }
        });

        redoBtn.addActionListener(e -> {
            if (client.isEditor()) {
                client.sendRedo();
                textPane.requestFocusInWindow();
            }
        });

        // Add the buttons to the toolbar
        if (client.isEditor()) {
            toolBar.add(boldBtn);
            toolBar.add(italicBtn);
            toolBar.add(undoBtn);
            toolBar.add(redoBtn);
        }

        // ADD after toolBar.add(italicBtn);
        JLabel editorCodeLabel = new JLabel("  Editor Code: " + client.getEditorCode());
        editorCodeLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        editorCodeLabel.setForeground(new Color(0, 100, 180));

        JButton copyEditorBtn = new JButton("Copy Editor Code");
        copyEditorBtn.setFocusable(false);
        copyEditorBtn.addActionListener(ev -> {
            java.awt.Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(client.getEditorCode()), null);
            copyEditorBtn.setText("Copied!");
            new javax.swing.Timer(1500, t -> copyEditorBtn.setText("Copy Editor Code")).start();
        });

        JLabel viewerCodeLabel = new JLabel("  Viewer Code: " + client.getViewerCode());
        viewerCodeLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        viewerCodeLabel.setForeground(new Color(120, 80, 180));

        JButton copyViewerBtn = new JButton("Copy Viewer Code");
        copyViewerBtn.setFocusable(false);
        copyViewerBtn.addActionListener(ev -> {
            java.awt.Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(client.getViewerCode()), null);
            copyViewerBtn.setText("Copied!");
            new javax.swing.Timer(1500, t -> copyViewerBtn.setText("Copy Viewer Code")).start();
        });

        /*toolBar.addSeparator();
        toolBar.add(codeLabel);
        toolBar.add(copyBtn);*/

        JButton importBtn = new JButton("Import");
        JButton exportBtn = new JButton("Export");
        importBtn.setFocusable(false);
        exportBtn.setFocusable(false);
        importBtn.setFont(buttonFont);
        exportBtn.setFont(buttonFont);
        importBtn.setMargin(new Insets(10, 25, 10, 25));
        exportBtn.setMargin(new Insets(10, 25, 10, 25));

        importBtn.addActionListener(e -> onImport());
        exportBtn.addActionListener(e -> onExport());

        JButton saveBtn = new JButton("Save");
        saveBtn.setFocusable(false);
        saveBtn.setFont(buttonFont);
        saveBtn.setMargin(new Insets(10, 25, 10, 25));
        saveBtn.addActionListener(e -> onSave());

        toolBar.addSeparator();
        if (client.isEditor()) {
            toolBar.add(importBtn);
            toolBar.add(exportBtn);
            toolBar.add(saveBtn);
        }
        toolBar.addSeparator();

        //Codes
        if (client.isEditor()) {
            toolBar.add(editorCodeLabel);
            toolBar.add(copyEditorBtn);
            toolBar.add(viewerCodeLabel);
            toolBar.add(copyViewerBtn);
        } else {
            toolBar.add(viewerCodeLabel);
            toolBar.add(copyViewerBtn);
        }


        // Add the toolbar to the TOP of the window
        frame.add(toolBar, BorderLayout.NORTH);



      //  JPanel usersPanel = createUsersPanel();
        //frame.add(usersPanel, BorderLayout.EAST);


        JPanel rightPanel = new JPanel();
        rightPanel.setLayout(new BorderLayout());

        /*JPanel usersPanel = createUsersPanel();
        JPanel cursorPanel = createCursorPanel();

        rightPanel.add(usersPanel, BorderLayout.CENTER);
        rightPanel.add(cursorPanel, BorderLayout.SOUTH);*/
        JPanel usersPanel = createUsersPanel();
        JPanel cursorPanel = createCursorPanel();
        JPanel commentsPanel = createCommentsSidebar();

        rightPanel.add(usersPanel, BorderLayout.NORTH);
        rightPanel.add(cursorPanel, BorderLayout.CENTER);
        rightPanel.add(commentsPanel, BorderLayout.SOUTH);

        frame.add(rightPanel, BorderLayout.EAST);


        // --- SETUP THE TEXT AREA ---
        textPane = new JTextPane();
        textPane.setEditable(client.isEditor());

        textPane.addCaretListener(e -> {
            if (client != null) {
                client.sendCursor(textPane.getCaretPosition());
            }
        });

        highlighter = textPane.getHighlighter();

        JPopupMenu contextMenu = new JPopupMenu();
        JMenuItem addCommentItem = new JMenuItem("💬 Add Comment");
        addCommentItem.addActionListener(e -> {
            int start = textPane.getSelectionStart();
            int end   = textPane.getSelectionEnd();

            if (start == end) {
                JOptionPane.showMessageDialog(frame, "Highlight some text first.");
                return;
            }

            List<CharNode> visible = doc.GetVisibleNodes();
            if (end > visible.size()) return;

            String commentText = JOptionPane.showInputDialog(frame,
                    "Enter comment:", "Add Comment", JOptionPane.PLAIN_MESSAGE);
            if (commentText == null || commentText.isBlank()) return;

            client.sendAddComment(commentText.trim(),
                    visible.get(start),
                    visible.get(end - 1));
        });

        JMenuItem viewCommentsItem = new JMenuItem("📋 View Comments");
        viewCommentsItem.addActionListener(e -> {
            if (activeComments.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "No comments yet.");
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (Comment c : activeComments.values()) {
                sb.append("• ").append(c.authorUsername)
                        .append(": ").append(c.text).append("\n");
            }
            JOptionPane.showMessageDialog(frame, sb.toString(),
                    "Comments", JOptionPane.INFORMATION_MESSAGE);
        });

        contextMenu.add(addCommentItem);
        contextMenu.add(viewCommentsItem);
        textPane.setComponentPopupMenu(contextMenu);


        // Give the text some breathing room and a modern, readable font
        textPane.setMargin(new Insets(20, 20, 20, 20));
        textPane.setFont(new Font("Segoe UI", Font.PLAIN, 16));
        JScrollPane scrollPane = new JScrollPane(textPane);
        frame.add(scrollPane, BorderLayout.CENTER);

        // --- THE KEYSTROKE TRAP ---
        // --- THE KEYSTROKE TRAP ---
        textPane.addKeyListener(new KeyAdapter() {

            @Override
            public void keyPressed(KeyEvent e) {
                // COPY
                if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_C) {
                    e.consume();
                    handleCopy();
                    return;
                }

                // SELECT ALL
                if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_A) {
                    e.consume();
                    textPane.requestFocusInWindow();
                    textPane.selectAll();
                    return;
                }



                if (!client.isEditor()) return;

                // PASTE
                if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_V) {
                    e.consume();
                    handlePaste();
                    return;
                }

                // CUT
                if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_X) {
                    e.consume();
                    handleCut();
                    return;
                }

                // UNDO
                if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_Z) {
                    e.consume();
                    client.sendUndo();
                    return;
                }
                //REDO
                if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_Y) {
                    e.consume();
                    client.sendRedo();
                    return;
                }
                // 1. Handle Backspace
                if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
                    e.consume();
                    int cursorPosition = textPane.getCaretPosition();

                    if (cursorPosition > 0) {
                        CharNode deleted =doc.LocalDelete(cursorPosition - 1);

                        if (deleted != null) client.sendDeleteChar(deleted);
                        shiftRemoteCursors(cursorPosition - 1, -1);

                        renderDocument(cursorPosition - 1);
                        drawRemoteCursors();
                    }
                }
                // 2. Handle Enter (New Line) safely!
                else if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    e.consume();
                    int cursorPosition = textPane.getCaretPosition();

                    // THE CLAMP: Forces the index to never be larger than the CRDT's actual size
                    int safeIndex = Math.min(cursorPosition, doc.RenderDocument().length());

                    // Explicitly insert exactly one newline character
                    CharNode inserted = doc.LocalInsert('\n', safeIndex);   // returns node
                    if (inserted != null) client.sendInsertChar(inserted);  // ADD THIS LINE
                    shiftRemoteCursors(safeIndex, +1);
                    renderDocument(safeIndex + 1);
                    drawRemoteCursors();
                }
            }

            @Override
            public void keyTyped(KeyEvent e) {
                if (!client.isEditor()) return;
                e.consume();
                char typedChar = e.getKeyChar();

                /* Ignore backspace, newlines, and Windows carriage returns here!
                if (typedChar == '\b' || typedChar == '\n' || typedChar == '\r') return;*/

                if (e.isControlDown() || Character.isISOControl(typedChar)) {
                    e.consume();
                    return;
                }


                int cursorPosition = textPane.getCaretPosition();

                // THE CLAMP: Protects normal typing from OS index bugs
                int safeIndex = Math.min(cursorPosition, doc.RenderDocument().length());

                // THE CLAMP: Protects normal typing from OS index bugs

                CharNode inserted = doc.LocalInsert(typedChar, safeIndex);

                doc.InheritFormatting(safeIndex);

                if (inserted != null) client.sendInsertChar(inserted);

                shiftRemoteCursors(safeIndex, +1);

                // Copy the formatting of the letter we just typed next to!


                renderDocument(safeIndex + 1);
                drawRemoteCursors();

                //doc.LocalInsert(typedChar, safeIndex);
                //renderDocument(safeIndex + 1);
            }
        });

        /*usersListener = this::updateActiveUsers;
        sessionService.addUsersListener(sessionCode, usersListener);
        updateActiveUsers(sessionService.getUsersInSession(sessionCode));

        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                sessionService.leaveSession(username, sessionCode);
                sessionService.removeUsersListener(sessionCode, usersListener);
            }
        });*/

        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                if (client != null) {
                    client.close();
                }
            }
        });

        frame.setVisible(true);
    }

    // --- THE DUMB VIEW RENDERER ---
    /*private void renderDocument(int newCursorPosition) {
        // 1. Ask your Document for the final built string
        String currentText = doc.RenderDocument();

        // 2. Wipe the JTextPane and replace it with the exact string
        textPane.setText(currentText);

        // 3. Force the cursor back to exactly where it belongs!
        // (We use Math.min just as a safety net so it doesn't accidentally crash if the text is too short)
        textPane.setCaretPosition(Math.min(newCursorPosition, currentText.length()));
    }*/

    private JPanel createUsersPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(200, 150));
        panel.setBorder(BorderFactory.createTitledBorder("Active Users"));

        usersListModel = new DefaultListModel<>();
        usersList = new JList<>(usersListModel);
        usersList.setFont(new Font("Segoe UI", Font.PLAIN, 14));

        JScrollPane usersScrollPane = new JScrollPane(usersList);
        panel.add(usersScrollPane, BorderLayout.CENTER);

        return panel;
    }

    /*public void updateActiveUsers(List<String> users) {
        usersListModel.clear();

        for (String user : users) {
            usersListModel.addElement(user);
        }

        remoteCursors.keySet().removeIf(user -> !users.contains(user));
        updateRemoteCursorDisplay();
        drawRemoteCursors();
    }*/

    public void updateActiveUsers(List<String> users) {
        usersListModel.clear();
        for (String user : users) {
            usersListModel.addElement(user);
        }

        //assignedColors.keySet().retainAll(users);
        remoteCursors.keySet().removeIf(user -> !users.contains(user));
        updateRemoteCursorDisplay();
        drawRemoteCursors();
    }

    // cursor
    private JPanel createCursorPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(220, 100));
        panel.setBorder(BorderFactory.createTitledBorder("Remote Cursors"));

        cursorListModel = new DefaultListModel<>();
        cursorList = new JList<>(cursorListModel);
        cursorList.setFont(new Font("Segoe UI", Font.PLAIN, 14));

        JScrollPane scrollPane = new JScrollPane(cursorList);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createCommentsSidebar() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(220, 250));
        panel.setBorder(BorderFactory.createTitledBorder("Comments"));

        commentsListPanel = new JPanel();
        commentsListPanel.setLayout(new BoxLayout(commentsListPanel, BoxLayout.Y_AXIS));

        JScrollPane scroll = new JScrollPane(commentsListPanel);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildCommentCard(Comment c) {
        JPanel card = new JPanel(new BorderLayout(5, 5));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0,
                        c.resolved ? Color.GRAY : new Color(255, 200, 0)),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)
        ));
        card.setBackground(c.resolved ? new Color(245, 245, 245) : Color.WHITE);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));

        JLabel header = new JLabel("<html><b>" + c.authorUsername + "</b> &nbsp;"
                + new java.text.SimpleDateFormat("HH:mm")
                .format(new java.util.Date(c.timestamp))
                + "</html>");
        header.setFont(new Font("Segoe UI", Font.PLAIN, 11));

        JTextArea textArea = new JTextArea(c.text);
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setBackground(card.getBackground());
        textArea.setFont(new Font("Segoe UI", Font.PLAIN, 12));

        JButton resolveBtn = new JButton(c.resolved ? "Resolved ✓" : "Resolve");
        resolveBtn.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        resolveBtn.setFocusable(false);
        resolveBtn.addActionListener(e -> client.sendResolveComment(c.id));

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        bottom.setBackground(card.getBackground());
        bottom.add(resolveBtn);

        card.add(header,   BorderLayout.NORTH);
        card.add(textArea, BorderLayout.CENTER);
        card.add(bottom,   BorderLayout.SOUTH);
        return card;
    }

    private void updateRemoteCursorDisplay() {
        cursorListModel.clear();

        for (java.util.Map.Entry<String, Integer> entry : remoteCursors.entrySet()) {
            cursorListModel.addElement(entry.getKey() + " -> " + entry.getValue());
        }
    }



    /*private Color getUserColor(String user) {
        int index = Math.abs(user.toLowerCase().hashCode()) % 4;

        Color[] colors = {
                new Color(255, 105, 180), // pink
                Color.BLUE,
                Color.GREEN,
                Color.ORANGE
        };

        return colors[index];
    }*/

    /*private Color getUserColor(String user) {
        if (!assignedColors.containsKey(user)) {
            int index = assignedColors.size() % CURSOR_COLORS.length;
            assignedColors.put(user, CURSOR_COLORS[index]);
        }
        return assignedColors.get(user);
    }*/
    private Color getUserColor(String user) {
        int index = Math.abs(user.toLowerCase().hashCode()) % CURSOR_COLORS.length;
        return CURSOR_COLORS[index];
    }

    /*private void drawRemoteCursors() {
        for (Object tag : cursorHighlights.values()) {
            highlighter.removeHighlight(tag);
        }
        cursorHighlights.clear();

        int length = textPane.getDocument().getLength();

        if (length == 0) {
            return;
        }

        for (Map.Entry<String, Integer> entry : remoteCursors.entrySet()) {
            String user = entry.getKey();
            int pos = entry.getValue();

            if (pos < 0 || pos >= length) {
                continue;
            }

            try {
                Color color = getUserColor(user);

                Object tag = highlighter.addHighlight(
                        pos,
                        pos + 1,
                        new DefaultHighlighter.DefaultHighlightPainter(color)
                );

                cursorHighlights.put(user, tag);

            } catch (BadLocationException e) {
                e.printStackTrace();
            }
        }
    }*/

    private void drawRemoteCursors() {
        for (Object tag : cursorHighlights.values()) {
            highlighter.removeHighlight(tag);
        }
        cursorHighlights.clear();

        int length = textPane.getDocument().getLength();

        for (Map.Entry<String, Integer> entry : remoteCursors.entrySet()) {
            String user = entry.getKey();
            int pos = entry.getValue();

            // Clamp to valid document range
            pos = Math.max(0, Math.min(pos, length));
            if (length == 0) continue;

            try {
                Color color = getUserColor(user);
                // Highlight range just needs to be valid; CursorPainter uses p0 to draw the line
                int p0 = Math.min(pos, length - 1);
                Object tag = highlighter.addHighlight(
                        p0, p0 + 1,
                        new CursorPainter(color, pos)  // pass actual pos separately
                );
                cursorHighlights.put(user, tag);
            } catch (BadLocationException e) {
                e.printStackTrace();
            }
        }
    }


    // --- THE DUMB VIEW RENDERER (UPGRADED FOR RICH TEXT) ---
    private void renderDocument(int newCursorPosition) {
        // 1. Get the list of nodes with their formatting memory from the Document
        List<CharNode> nodes = doc.GetVisibleNodes();

        // 2. Wipe the text box completely clean
        textPane.setText("");

        // 3. Grab the "paintbrush" for the text pane
        StyledDocument styledDoc = textPane.getStyledDocument();

        // 4. Loop through the nodes and paint them one by one
        try {
            for (CharNode node : nodes) {
                // Set up the specific style for this exact letter
                SimpleAttributeSet style = new SimpleAttributeSet();
                StyleConstants.setBold(style, node.isBold());
                StyleConstants.setItalic(style, node.isItalic());

                // Paint the letter onto the screen using that style
                styledDoc.insertString(styledDoc.getLength(), String.valueOf(node.getValue()), style);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 5. Put the cursor back where it belongs safely
        textPane.setCaretPosition(Math.min(newCursorPosition, styledDoc.getLength()));
        drawCommentHighlights();
    }
    private void shiftRemoteCursors(int atIndex, int delta) {
        for (Map.Entry<String, Integer> entry : remoteCursors.entrySet()) {
            int pos = entry.getValue();
            if (pos > atIndex) {
                remoteCursors.put(entry.getKey(), pos + delta);
            }
        }
    }

   /*   public static void main(String[] args) {
       // SwingUtilities.invokeLater(() -> new EditorUI());
        javax.swing.SwingUtilities.invokeLater(() -> {
            // We are passing "Guest" into the EditorUI here
            new EditorUI("Guest_" + (int)(Math.random() * 100));
        });
    }*/

    // Draws a thin vertical line instead of a block highlight
    private static class CursorPainter implements Highlighter.HighlightPainter {
        private final Color color;
        private final int cursorPos; // the REAL position, not the clamped one

        CursorPainter(Color color, int cursorPos) {
            this.color = color;
            this.cursorPos = cursorPos;
        }

        @Override
        public void paint(Graphics g, int p0, int p1, Shape bounds, JTextComponent c) {
            try {
                // Use the actual cursor position for modelToView
                Rectangle r = c.modelToView(cursorPos);
                if (r != null) {
                    g.setColor(color);
                    g.fillRect(r.x, r.y, 3, r.height); // thin vertical line at exact position
                }
            } catch (BadLocationException e) {
                e.printStackTrace();
            }
        }
    }
    /*private void onImport() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Text files (*.txt)", "txt"));
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;

        String content;
        try {
            content = java.nio.file.Files.readString(chooser.getSelectedFile().toPath());
        } catch (java.io.IOException ex) {
            JOptionPane.showMessageDialog(frame, "Failed to read file: " + ex.getMessage());
            return;
        }

        int startIndex = doc.RenderDocument().length();
        for (int i = 0; i < content.length(); i++) {
            CharNode inserted = doc.LocalInsert(content.charAt(i), startIndex + i);
            if (inserted != null) client.sendInsertChar(inserted);
        }
        renderDocument(doc.RenderDocument().length());
        drawRemoteCursors();
    }*/

    private void onImport() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Text files (*.txt)", "txt"));
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;

        String content;
        try {
            content = java.nio.file.Files.readString(chooser.getSelectedFile().toPath());
        } catch (java.io.IOException ex) {
            JOptionPane.showMessageDialog(frame, "Failed to read file: " + ex.getMessage());
            return;
        }

        // --- Progress Dialog ---
        JDialog progressDialog = new JDialog(frame, "Importing...", false);
        JProgressBar progressBar = new JProgressBar(0, content.length());
        progressBar.setStringPainted(true);
        progressBar.setString("0 / " + content.length() + " characters");
        JLabel progressLabel = new JLabel("Importing file, please wait...", SwingConstants.CENTER);
        progressLabel.setBorder(BorderFactory.createEmptyBorder(10, 10, 5, 10));

        JPanel progressPanel = new JPanel(new BorderLayout(5, 5));
        progressPanel.setBorder(BorderFactory.createEmptyBorder(10, 15, 15, 15));
        progressPanel.add(progressLabel, BorderLayout.NORTH);
        progressPanel.add(progressBar, BorderLayout.CENTER);

        progressDialog.add(progressPanel);
        progressDialog.setSize(350, 100);
        progressDialog.setLocationRelativeTo(frame);
        progressDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        progressDialog.setVisible(true);

        // --- SwingWorker does the heavy lifting off the EDT ---
        final String finalContent = content;
        SwingWorker<Void, Integer> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                int startIndex = doc.RenderDocument().length();
                for (int i = 0; i < finalContent.length(); i++) {
                    CharNode inserted = doc.LocalInsert(finalContent.charAt(i), startIndex + i);
                    if (inserted != null) client.sendInsertChar(inserted);
                    publish(i + 1); // report progress
                }
                return null;
            }

            @Override
            protected void process(java.util.List<Integer> chunks) {
                int latest = chunks.get(chunks.size() - 1);
                progressBar.setValue(latest);
                progressBar.setString(latest + " / " + finalContent.length() + " characters");
            }

            @Override
            protected void done() {
                progressDialog.dispose();
                renderDocument(doc.RenderDocument().length());
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
            pw.print(doc.RenderDocument());
            JOptionPane.showMessageDialog(frame, "Exported successfully to: " + file.getName());
        } catch (java.io.IOException ex) {
            JOptionPane.showMessageDialog(frame, "Failed to export: " + ex.getMessage());
        }
    }
    private void onSave() {
        CharCRDT crdt = client.getActiveCharCRDT();
        if (crdt == null) {
            JOptionPane.showMessageDialog(frame, "Not connected to a session.");
            return;
        }
        String crdtJson = CrdtSerializer.toJson(crdt);
        client.sendSaveDoc(crdtJson);
        JOptionPane.showMessageDialog(frame, "Document saved successfully.");
    }

    private void drawCommentHighlights() {
        for (Object tag : commentHighlights.values()) highlighter.removeHighlight(tag);
        commentHighlights.clear();
        if (commentsListPanel != null) commentsListPanel.removeAll();

        List<CharNode> visible = doc.GetVisibleNodes();

        for (Comment c : activeComments.values()) {
            int startIdx = -1, endIdx = -1;
            for (int i = 0; i < visible.size(); i++) {
                CharNode n = visible.get(i);
                if (n.getID().getUserID() == c.startCharUser
                        && n.getID().getClock() == c.startCharClock) startIdx = i;
                if (n.getID().getUserID() == c.endCharUser
                        && n.getID().getClock() == c.endCharClock)   endIdx = i + 1;
            }

            if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
                try {
                    Color color = c.resolved
                            ? new Color(220, 220, 220, 120)
                            : new Color(255, 255, 0, 150);
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

        if (commentsListPanel != null) {
            commentsListPanel.revalidate();
            commentsListPanel.repaint();
        }
    }

    //Copy,paste,cut

    private String cleanClipboardText(String text) {
        if (text == null) return "";

        text = text.replace("\r\n", "\n");
        text = text.replace("\r", "\n");

        StringBuilder cleaned = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);

            if (Character.isISOControl(ch) && ch != '\n' && ch != '\t') {
                continue;
            }

            cleaned.append(ch);
        }

        return cleaned.toString();
    }

    private void handleCopy() {
        String selectedText = cleanClipboardText(textPane.getSelectedText());
        if (selectedText == null || selectedText.isEmpty()) return;

        Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(selectedText), null);
    }
    private void handlePaste() {
        if (!client.isEditor()) return;

        try {
            String pastedText = (String) Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .getData(DataFlavor.stringFlavor);
            pastedText = cleanClipboardText(pastedText);
            if (pastedText.isEmpty()) return;


            int cursorPosition = textPane.getCaretPosition();
            int safeIndex = Math.min(cursorPosition, doc.RenderDocument().length());

            for (int i = 0; i < pastedText.length(); i++) {
                CharNode inserted = doc.LocalInsert(pastedText.charAt(i), safeIndex + i);

                if (inserted != null) {
                    doc.InheritFormatting(safeIndex + i);
                    client.sendInsertChar(inserted);
                }
            }

            renderDocument(safeIndex + pastedText.length());
            drawRemoteCursors();

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, "Paste failed");
        }
    }

    private void handleCut() {
        if (!client.isEditor()) return;

        int start = textPane.getSelectionStart();
        int end = textPane.getSelectionEnd();

        if (start == end) return;

        String selectedText = cleanClipboardText(textPane.getSelectedText());
        if (selectedText == null) return;

        Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(selectedText), null);

        for (int i = end - 1; i >= start; i--) {
            CharNode deleted = doc.LocalDelete(i);
            if (deleted != null) {
                client.sendDeleteChar(deleted);
            }
        }

        renderDocument(start);
        drawRemoteCursors();
    }

}