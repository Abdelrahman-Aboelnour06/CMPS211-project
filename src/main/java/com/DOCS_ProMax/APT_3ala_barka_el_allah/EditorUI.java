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

public class EditorUI {


    private JFrame frame;
    private JTextPane textPane;
    private String username;
    private String sessionCode;
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
    //private SessionUsersListener usersListener;

    // 1. Declare your Document manager
    private Document doc;

    public EditorUI(String username, String sessionCode, Client client) {

        // --- MAKE IT LOOK MODERN ---
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }
        // 2. Initialize the Document with userID 1
        //doc = new Document(1);

        this.doc = new Document(new java.util.Random().nextInt(1000));
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

                    case "INSERT_CHAR", "DELETE_CHAR" -> {
                        renderDocument(textPane.getCaretPosition());
                        drawRemoteCursors();
                    }

                    case "CURSOR" -> {
                        if (op.username != null && !op.username.equals(username)) {
                            remoteCursors.put(op.username, op.cursorIndex);
                            drawRemoteCursors();
                            updateRemoteCursorDisplay();
                        }
                    }

                }

            });
        });
        client.requestActiveUsers();
        // ... use the username for the Window Title ...
        frame = new JFrame("Collaborative Editor - " + username + " (" + sessionCode + ")");

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

        // --- BUTTON CLICK LOGIC ---
        // --- BUTTON CLICK LOGIC ---
        boldBtn.addActionListener(e -> {
            int start = textPane.getSelectionStart();
            int end = textPane.getSelectionEnd();

            if (start != end) {
                doc.FormatSelection(start, end, true);
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
                renderDocument(textPane.getCaretPosition());
                drawRemoteCursors();

                // Re-highlight the exact same text so it stays selected!
                textPane.setSelectionStart(start);
                textPane.setSelectionEnd(end);
            }
        });

        // Add the buttons to the toolbar
        toolBar.add(boldBtn);
        toolBar.add(italicBtn);

        // Add the toolbar to the TOP of the window
        frame.add(toolBar, BorderLayout.NORTH);



      //  JPanel usersPanel = createUsersPanel();
        //frame.add(usersPanel, BorderLayout.EAST);


        JPanel rightPanel = new JPanel();
        rightPanel.setLayout(new BorderLayout());

        JPanel usersPanel = createUsersPanel();
        JPanel cursorPanel = createCursorPanel();

        rightPanel.add(usersPanel, BorderLayout.CENTER);
        rightPanel.add(cursorPanel, BorderLayout.SOUTH);

        frame.add(rightPanel, BorderLayout.EAST);


        // --- SETUP THE TEXT AREA ---
        textPane = new JTextPane();

        textPane.addCaretListener(e -> {
            if (client != null) {
                client.sendCursor(textPane.getCaretPosition());
            }
        });

        highlighter = textPane.getHighlighter();


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
                // 1. Handle Backspace
                if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
                    e.consume();
                    int cursorPosition = textPane.getCaretPosition();

                    if (cursorPosition > 0) {
                        doc.LocalDelete(cursorPosition - 1);
                        renderDocument(cursorPosition - 1);
                    }
                }
                // 2. Handle Enter (New Line) safely!
                else if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    e.consume();
                    int cursorPosition = textPane.getCaretPosition();

                    // THE CLAMP: Forces the index to never be larger than the CRDT's actual size
                    int safeIndex = Math.min(cursorPosition, doc.RenderDocument().length());

                    // Explicitly insert exactly one newline character
                    doc.LocalInsert('\n', safeIndex);
                    renderDocument(safeIndex + 1);
                    drawRemoteCursors();
                }
            }

            @Override
            public void keyTyped(KeyEvent e) {
                e.consume();
                char typedChar = e.getKeyChar();

                // Ignore backspace, newlines, and Windows carriage returns here!
                if (typedChar == '\b' || typedChar == '\n' || typedChar == '\r') return;

                int cursorPosition = textPane.getCaretPosition();

                // THE CLAMP: Protects normal typing from OS index bugs
                int safeIndex = Math.min(cursorPosition, doc.RenderDocument().length());

                // THE CLAMP: Protects normal typing from OS index bugs

                doc.LocalInsert(typedChar, safeIndex);

                // Copy the formatting of the letter we just typed next to!
                doc.InheritFormatting(safeIndex);

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
        panel.setPreferredSize(new Dimension(200, 0));
        panel.setBorder(BorderFactory.createTitledBorder("Active Users"));

        usersListModel = new DefaultListModel<>();
        usersList = new JList<>(usersListModel);
        usersList.setFont(new Font("Segoe UI", Font.PLAIN, 14));

        JScrollPane usersScrollPane = new JScrollPane(usersList);
        panel.add(usersScrollPane, BorderLayout.CENTER);

        return panel;
    }

    public void updateActiveUsers(List<String> users) {
        usersListModel.clear();

        for (String user : users) {
            usersListModel.addElement(user);
        }

        remoteCursors.keySet().removeIf(user -> !users.contains(user));
        updateRemoteCursorDisplay();
        drawRemoteCursors();
    }

    // cursor
    private JPanel createCursorPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(220, 120));
        panel.setBorder(BorderFactory.createTitledBorder("Remote Cursors"));

        cursorListModel = new DefaultListModel<>();
        cursorList = new JList<>(cursorListModel);
        cursorList.setFont(new Font("Segoe UI", Font.PLAIN, 14));

        JScrollPane scrollPane = new JScrollPane(cursorList);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    private void updateRemoteCursorDisplay() {
        cursorListModel.clear();

        for (java.util.Map.Entry<String, Integer> entry : remoteCursors.entrySet()) {
            cursorListModel.addElement(entry.getKey() + " -> " + entry.getValue());
        }
    }



    private Color getUserColor(String user) {
        int index = Math.abs(user.toLowerCase().hashCode()) % 4;

        Color[] colors = {
                new Color(255, 105, 180), // pink
                Color.BLUE,
                Color.GREEN,
                Color.ORANGE
        };

        return colors[index];
    }

    private void drawRemoteCursors() {
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
    }

   /*   public static void main(String[] args) {
       // SwingUtilities.invokeLater(() -> new EditorUI());
        javax.swing.SwingUtilities.invokeLater(() -> {
            // We are passing "Guest" into the EditorUI here
            new EditorUI("Guest_" + (int)(Math.random() * 100));
        });
    }*/
}