package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import javax.swing.*;
import java.awt.*;
import java.awt.Color;

public class StartScreen {

    private JFrame frame;
    private JTextField usernameField;
    private JTextField sessionCodeField;
    private JLabel statusLabel;

   // private DummySessionService sessionService;
    private Client client;

    public StartScreen() {
        //sessionService = new DummySessionService();
        try {
            Clock clock = new Clock();
           // BlockCRDT doc = new BlockCRDT(1, clock);
           // BlockNode block = doc.insertTopLevelBlock(new CharCRDT(1, clock));
            int userId = (int)(System.currentTimeMillis() % 100000); // unique enough
            BlockCRDT doc = new BlockCRDT(userId, clock);
            BlockNode block = doc.insertTopLevelBlock(new CharCRDT(userId, clock));

            client = new Client("ws://localhost:8080/collab", doc, clock, block.getId());
            client.connectBlocking();
        } catch (Exception e) {
            e.printStackTrace();
        }
        initializeUI();
    }

    private void initializeUI() {
        frame = new JFrame("Collaborative Editor");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(500, 300);
        frame.setLocationRelativeTo(null);

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new GridLayout(7, 1, 10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel titleLabel = new JLabel("Collaborative Editor", SwingConstants.CENTER);
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 22));

        usernameField = new JTextField();
        sessionCodeField = new JTextField();

        JButton createButton = new JButton("Create New Document");
        JButton joinButton = new JButton("Join Session");
        JButton myDocsButton = new JButton("My Documents");

        statusLabel = new JLabel(" ", SwingConstants.CENTER);

        mainPanel.add(titleLabel);
        mainPanel.add(createLabeledField("Username:", usernameField));
        mainPanel.add(createLabeledField("Session Code:", sessionCodeField));
        mainPanel.add(createButton);
        mainPanel.add(joinButton);
        mainPanel.add(myDocsButton);
        mainPanel.add(statusLabel);

        frame.add(mainPanel);
        frame.setVisible(true);

        createButton.addActionListener(e -> handleCreateSession());
        joinButton.addActionListener(e -> handleJoinSession());
        myDocsButton.addActionListener(e -> handleMyDocuments());
    }

    private JPanel createLabeledField(String labelText, JTextField field) {
        JPanel panel = new JPanel(new BorderLayout(10, 0));
        JLabel label = new JLabel(labelText);
        panel.add(label, BorderLayout.WEST);
        panel.add(field, BorderLayout.CENTER);
        return panel;
    }

    private void handleCreateSession() {
        String username = usernameField.getText();

        /*SessionJoinResult result = sessionService.createSession(username);

        if (result.success) {
            statusLabel.setText("Created session: " + result.sessionCode);

            new EditorUI(result.username, result.sessionCode,sessionService);

            frame.dispose();
        } else {
            statusLabel.setText(result.message);
        }*/
        client.setMessageListener(op -> {
            SwingUtilities.invokeLater(() -> {
                if ("SESSION_CREATED".equals(op.type)) {
                    new EditorUI(username, op.sessionCode, client);
                    frame.dispose();
                } else if ("ERROR".equals(op.type)) {
                    statusLabel.setText(op.payload);
                }
            });
        });

        client.createSession(username);
    }

    private void handleJoinSession() {
        String username = usernameField.getText();
        String sessionCode = sessionCodeField.getText();

       /* SessionJoinResult result = sessionService.joinSession(username, sessionCode);

        if (result.success) {
            statusLabel.setText("Joined session: " + result.sessionCode);

            new EditorUI(result.username, result.sessionCode,sessionService);

            frame.dispose();
        } else {
            statusLabel.setText(result.message);
        }*/
        client.setMessageListener(op -> {
            SwingUtilities.invokeLater(() -> {
                if ("SESSION_JOINED".equals(op.type)) {
                    new EditorUI(username, op.sessionCode, client);
                    frame.dispose();
                } else if ("ERROR".equals(op.type)) {
                    statusLabel.setText(op.payload);
                }
            });
        });

        client.joinSession(username, sessionCode);
    }

    private void handleMyDocuments() {
        String username = usernameField.getText().trim();
        if (username.isEmpty()) {
            statusLabel.setText("Enter your username first.");
            return;
        }

        client.setMessageListener(op -> {
            SwingUtilities.invokeLater(() -> {
                if ("DOCS_LIST".equals(op.type)) {
                    showDocumentsDialog(username, op.payload);
                } else if ("ERROR".equals(op.type)) {
                    statusLabel.setText(op.payload);
                }
            });
        });

        Operations listOp = new Operations();
        listOp.type          = "LIST_DOCS";
        listOp.ownerUsername = username;
        client.send(listOp.toJson());
    }

    private void showDocumentsDialog(String username, String jsonPayload) {
        // Parse the JSON array from the server
        java.util.List<com.google.gson.JsonObject> docs = new java.util.ArrayList<>();
        try {
            com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(jsonPayload).getAsJsonArray();
            for (com.google.gson.JsonElement el : arr) {
                docs.add(el.getAsJsonObject());
            }
        } catch (Exception ex) {
            statusLabel.setText("Failed to parse documents.");
            return;
        }

        JDialog dialog = new JDialog(frame, "My Documents", true);
        dialog.setSize(480, 350);
        dialog.setLocationRelativeTo(frame);
        dialog.setLayout(new BorderLayout());

        if (docs.isEmpty()) {
            dialog.add(new JLabel("No saved documents found.", SwingConstants.CENTER));
            dialog.setVisible(true);
            return;
        }

        JPanel listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));

        for (com.google.gson.JsonObject doc : docs) {
            String editorCode = doc.get("editorCode").getAsString();
            String viewerCode = doc.get("viewerCode").getAsString();

            JPanel row = new JPanel(new BorderLayout(10, 0));
            row.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));

            JLabel info = new JLabel("Editor: " + editorCode + "   Viewer: " + viewerCode);
            info.setFont(new Font("Segoe UI", Font.PLAIN, 13));

            JButton openBtn   = new JButton("Open");
            JButton deleteBtn = new JButton("Delete");
            deleteBtn.setForeground(Color.RED);

            /*openBtn.addActionListener(e -> {
                dialog.dispose();

                client.setMessageListener(op -> SwingUtilities.invokeLater(() -> {
                    if ("SESSION_CREATED".equals(op.type)) {
                        // Session created, now load the saved document content
                        Operations loadOp = new Operations();
                        loadOp.type        = "LOAD_DOC";
                        loadOp.sessionCode = editorCode; // the original editor code from MongoDB
                        client.send(loadOp.toJson());

                    } else if ("DOC_LOADED".equals(op.type)) {
                        // Content loaded, now open the editor and replay it
                        CharCRDT crdt = client.getActiveCharCRDT();
                        if (crdt != null && op.payload != null) {
                            CharCRDT loaded = CrdtSerializer.fromJson(op.payload, (int)(System.currentTimeMillis() % 100000));
                            for (CharNode node : loaded.getOrderedNodes()) {
                                crdt.RemotelyInsertion(node.getID(), node.getParentID(), node.getValue());
                            }
                        }
                        client.setOriginalEditorCode(editorCode);
                        new EditorUI(username, op.sessionCode != null ? op.sessionCode : client.getSessionCode(), client);
                        frame.dispose();

                    } else if ("ERROR".equals(op.type)) {
                        statusLabel.setText(op.payload);
                    }
                }));

                client.createSession(username);
            });*/

            openBtn.addActionListener(e -> {
                dialog.dispose();

                client.setMessageListener(op -> SwingUtilities.invokeLater(() -> {
                    if ("SESSION_CREATED".equals(op.type)) {
                        // Load saved CRDT content into the local CRDT
                        if (op.payload != null && !op.payload.isBlank()) {
                            CharCRDT crdt = client.getActiveCharCRDT();
                            if (crdt != null) {
                                CharCRDT loaded = CrdtSerializer.fromJson(
                                        op.payload,
                                        (int)(System.currentTimeMillis() % 100000)
                                );
                                for (CharNode node : loaded.getOrderedNodes()) {
                                    crdt.RemotelyInsertion(
                                            node.getID(), node.getParentID(), node.getValue()
                                    );
                                }
                            }
                        }
                        // Use the ORIGINAL editor code, not a new one
                        client.setOriginalEditorCode(editorCode);
                        new EditorUI(username, op.editorCode, client);
                        frame.dispose();

                    } else if ("ERROR".equals(op.type)) {
                        statusLabel.setText(op.payload);
                    }
                }));

                // Send OPEN_DOC instead of createSession — preserves original codes
                Operations openOp = new Operations();
                openOp.type        = "OPEN_DOC";
                openOp.sessionCode = editorCode;
                openOp.username    = username;
                client.send(openOp.toJson());
            });

            deleteBtn.addActionListener(e -> {
                int confirm = JOptionPane.showConfirmDialog(dialog,
                        "Delete document " + editorCode + "?",
                        "Confirm", JOptionPane.YES_NO_OPTION);
                if (confirm == JOptionPane.YES_OPTION) {
                    Operations delOp = new Operations();
                    delOp.type        = "DELETE_DOC";
                    delOp.sessionCode = editorCode;
                    delOp.username = username;
                    client.send(delOp.toJson());
                    dialog.dispose();
                    statusLabel.setText("Deleted: " + editorCode);
                }
            });

            /*JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
            btns.add(openBtn);
            btns.add(deleteBtn);*/
            JButton renameBtn = new JButton("Rename");
            renameBtn.addActionListener(e -> {
                String newName = JOptionPane.showInputDialog(dialog,
                        "New name:", "Rename", JOptionPane.PLAIN_MESSAGE);
                if (newName != null && !newName.isBlank()) {
                    Operations renameOp = new Operations();
                    renameOp.type        = "RENAME_DOC";
                    renameOp.sessionCode = editorCode;
                    renameOp.username    = username;
                    renameOp.payload     = newName.trim();
                    // Wait for server confirmation BEFORE refreshing the list
                    client.setMessageListener(op -> SwingUtilities.invokeLater(() -> {
                        if ("DOC_RENAMED".equals(op.type)) {
                            dialog.dispose();
                            handleMyDocuments(); // now the DB has the new name
                        } else if ("ERROR".equals(op.type)) {
                            statusLabel.setText(op.payload);
                        }
                    }));

                    client.send(renameOp.toJson());
                }
            });

            JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
            btns.add(openBtn);
            btns.add(renameBtn);
            btns.add(deleteBtn);

            row.add(info, BorderLayout.CENTER);
            row.add(btns, BorderLayout.EAST);
            listPanel.add(row);
            listPanel.add(new JSeparator());
        }

        dialog.add(new JScrollPane(listPanel), BorderLayout.CENTER);

        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dialog.dispose());
        JPanel south = new JPanel();
        south.add(closeBtn);
        dialog.add(south, BorderLayout.SOUTH);

        dialog.setVisible(true);
    }

    public static void main(String[] args) {
       // SwingUtilities.invokeLater(StartScreen::new);
        //new StartScreen();
        SwingUtilities.invokeLater(StartScreen::new);
    }
}