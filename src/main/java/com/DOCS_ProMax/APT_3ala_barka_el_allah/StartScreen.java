package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import javax.swing.*;
import java.awt.*;

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
            BlockCRDT doc = new BlockCRDT(1, clock);
            BlockNode block = doc.insertTopLevelBlock(new CharCRDT(1, clock));

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
        mainPanel.setLayout(new GridLayout(6, 1, 10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel titleLabel = new JLabel("Collaborative Editor", SwingConstants.CENTER);
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 22));

        usernameField = new JTextField();
        sessionCodeField = new JTextField();

        JButton createButton = new JButton("Create Session");
        JButton joinButton = new JButton("Join Session");

        statusLabel = new JLabel(" ", SwingConstants.CENTER);

        mainPanel.add(titleLabel);
        mainPanel.add(createLabeledField("Username:", usernameField));
        mainPanel.add(createLabeledField("Session Code:", sessionCodeField));
        mainPanel.add(createButton);
        mainPanel.add(joinButton);
        mainPanel.add(statusLabel);

        frame.add(mainPanel);
        frame.setVisible(true);

        createButton.addActionListener(e -> handleCreateSession());
        joinButton.addActionListener(e -> handleJoinSession());
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

    public static void main(String[] args) {
       // SwingUtilities.invokeLater(StartScreen::new);
        //new StartScreen();
        SwingUtilities.invokeLater(StartScreen::new);
    }
}