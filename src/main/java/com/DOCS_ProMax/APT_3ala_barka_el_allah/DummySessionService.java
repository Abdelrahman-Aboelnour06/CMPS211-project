package com.DOCS_ProMax.APT_3ala_barka_el_allah;

import java.util.*;

public class DummySessionService {

    private static final Map<String, List<String>> sessions = new HashMap<>();
    private static final Map<String, List<SessionUsersListener>> listeners = new HashMap<>();
    private static final Random random = new Random();


    public SessionJoinResult createSession(String username) {
        if (username == null || username.trim().isEmpty()) {
            return new SessionJoinResult(false, null, null, "Please enter a username.");
        }

        String cleanedUsername = username.trim();
        String sessionCode = generateCode();

        List<String> users = new ArrayList<>();
        users.add(cleanedUsername);
        sessions.put(sessionCode, users);
        listeners.put(sessionCode, new ArrayList<>());

        return new SessionJoinResult(
                true,
                cleanedUsername,
                sessionCode,
                "Session created successfully."
        );
    }

    public SessionJoinResult joinSession(String username, String sessionCode) {
        if (username == null || username.trim().isEmpty()) {
            return new SessionJoinResult(false, null, null, "Please enter a username.");
        }

        if (sessionCode == null || sessionCode.trim().isEmpty()) {
            return new SessionJoinResult(false, null, null, "Please enter a session code.");
        }

        String cleanedUsername = username.trim();
        String cleanedCode = sessionCode.trim().toUpperCase();

        if (!sessions.containsKey(cleanedCode)) {
            return new SessionJoinResult(false, cleanedUsername, cleanedCode, "Session not found.");
        }

        List<String> users = sessions.get(cleanedCode);
        if (!users.contains(cleanedUsername)) {
            users.add(cleanedUsername);
            notifyUsersChanged(cleanedCode);
        }



        return new SessionJoinResult(
                true,
                cleanedUsername,
                cleanedCode,
                "Joined session successfully."
        );
    }

    public void leaveSession(String username, String sessionCode) {
        if (username == null || sessionCode == null) {
            return;
        }

        String cleanedUsername = username.trim();
        String cleanedCode = sessionCode.trim().toUpperCase();

        if (!sessions.containsKey(cleanedCode)) {
            return;
        }

        List<String> users = sessions.get(cleanedCode);
        users.remove(cleanedUsername);

        notifyUsersChanged(cleanedCode);

        if (users.isEmpty()) {
            sessions.remove(cleanedCode);
            listeners.remove(cleanedCode);
        }
    }

    public List<String> getUsersInSession(String sessionCode) {
        String cleanedCode = sessionCode.trim().toUpperCase();

        if (!sessions.containsKey(cleanedCode)) {
            return new ArrayList<>();
        }

        return new ArrayList<>(sessions.get(cleanedCode));
    }

    private String generateCode() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder code = new StringBuilder();

        for (int i = 0; i < 6; i++) {
            code.append(chars.charAt(random.nextInt(chars.length())));
        }

        return code.toString();
    }

    private void notifyUsersChanged(String sessionCode) {
        List<String> users = new ArrayList<>(sessions.get(sessionCode));
        List<SessionUsersListener> sessionListeners = listeners.get(sessionCode);

        for (SessionUsersListener listener : sessionListeners) {
            listener.onUsersChanged(users);
        }
    }

    public void addUsersListener(String sessionCode, SessionUsersListener listener) {
        String cleanedCode = sessionCode.trim().toUpperCase();

        if (!listeners.containsKey(cleanedCode)) {
            listeners.put(cleanedCode, new ArrayList<>());
        }

        listeners.get(cleanedCode).add(listener);
    }

    public void removeUsersListener(String sessionCode, SessionUsersListener listener) {
        String cleanedCode = sessionCode.trim().toUpperCase();

        if (listeners.containsKey(cleanedCode)) {
            listeners.get(cleanedCode).remove(listener);
        }
    }
}