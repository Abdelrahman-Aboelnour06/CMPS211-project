=========================================================
  DOCS ProMax - Collaborative Plain Text Editor
  Team: APT_3ala_barka_el_allah
=========================================================

PREREQUISITES
-------------
1. Java 17 or higher (JDK)
2. Maven 3.8+ (or use the included Maven wrapper: ./mvnw)
3. MongoDB running locally on port 27017
   - Install MongoDB Community Edition from https://www.mongodb.com/try/download/community
   - Start MongoDB: run "mongod" in a terminal (or start the MongoDB service)
   - The app will automatically create a database named "collab_editor"
4. A network connection between all collaborating machines (or localhost for testing)


HOW TO RUN - SERVER
--------------------
The server is a Spring Boot application. Start it first before any clients connect.

Option A - Using Maven wrapper (recommended):
  Windows:   mvnw.cmd spring-boot:run
  Mac/Linux: ./mvnw spring-boot:run

Option B - Build a JAR and run it:
  1. mvnw.cmd package -DskipTests       (Windows)
     ./mvnw package -DskipTests         (Mac/Linux)
  2. java -jar target/APT_3ala_barka_el_allah-0.0.1-SNAPSHOT.jar

The server starts on port 8080.
WebSocket endpoint: ws://localhost:8080/collab

You should see:  "Started Apt3alaBarkaElAllahApplication in X seconds"


HOW TO RUN - CLIENT (UI)
-------------------------
The graphical editor is launched from StartScreen.java.

After the server is running, open a second terminal and run:

  mvnw.cmd exec:java -Dexec.mainClass="com.DOCS_ProMax.APT_3ala_barka_el_allah.StartScreen"   (Windows)
  ./mvnw exec:java -Dexec.mainClass="com.DOCS_ProMax.APT_3ala_barka_el_allah.StartScreen"     (Mac/Linux)

Or run StartScreen.java directly from your IDE (IntelliJ / Eclipse / VS Code).

IMPORTANT: Before running on multiple machines, update the WebSocket server
address in StartScreen.java (line ~30):
  Change: "ws://172.20.10.3:8080/collab"
  To:     "ws://<server-machine-IP>:8080/collab"
  Or for local testing: "ws://localhost:8080/collab"


USING THE APPLICATION
----------------------
1. START SCREEN
   - Enter a username.
   - Click "Create New Document" to start a new session.
     You will receive an EDITOR CODE and a VIEWER CODE.
   - Share the editor code with collaborators who should edit.
   - Share the viewer code with collaborators who should only read.
   - Click "Join Session" and enter the code to join an existing session.
   - Click "My Documents" to view, open, rename, or delete saved documents.

2. EDITOR FEATURES
   - Type to insert characters in real time.
   - Backspace to delete characters.
   - Enter to insert a new line (auto-splits block if it exceeds 10 lines).
   - Bold / Italic buttons (or select text first) for formatting.
   - Ctrl+Z = Undo, Ctrl+Y = Redo (works across all users' edits).
   - Ctrl+C = Copy, Ctrl+V = Paste, Ctrl+X = Cut.
   - Import: loads a .txt file into the editor.
   - Export: saves the visible document text to a .txt file.
   - Save: persists the document to MongoDB.
   - History (⏱ History button): view and restore previous saved versions.

3. COLLABORATION
   - Remote cursors are shown as colored vertical bars in the text area.
   - Active users are listed in the right-hand panel.
   - Right-click for block operations: Move Up/Down, Copy Block,
     Split Block Here, Delete Block.
   - Right-click to add or view comments on highlighted text.

4. VIEWER ROLE
   - Viewers can read the document and see cursors/users but cannot edit.
   - The toolbar shows only read-only controls for viewers.


RUNNING TESTS
--------------
Unit tests (Spring context load test):
  mvnw.cmd test       (Windows)
  ./mvnw test         (Mac/Linux)

Manual smoke test (requires server to be running first):
  Run TestClient.java - verifies WebSocket connection and session creation.

Database integration test (requires server + MongoDB running):
  Run DBTestClient.java - tests SAVE_DOC, LOAD_DOC, and LIST_DOCS flows.

CRDT local logic test (no server needed):
  Run Main.java - tests local insert/delete operations on CharCRDT.

Concurrent editing test (no server needed):
  Run groupin_tester.java - interactive test for two-user concurrent edits.


PROJECT STRUCTURE
------------------
src/main/java/.../
  Apt3alaBarkaElAllahApplication.java  -- Spring Boot entry point (server)
  StartScreen.java                     -- Client GUI entry point
  EditorUI.java                        -- Main editor window
  Server.java                          -- WebSocket message handler
  Client.java                          -- WebSocket client
  SessionManager.java                  -- Session/user tracking
  BlockCRDT.java                       -- Block-level CRDT
  CharCRDT.java                        -- Character-level CRDT
  BlockNode.java / CharNode.java       -- CRDT tree nodes
  BlockID.java / CharID.java           -- Unique identifiers
  Clock.java                           -- Lamport logical clock
  UndoRedoManager.java                 -- Undo/redo stacks
  CrdtSerializer.java                  -- JSON serialization for MongoDB
  DocumentEntity.java                  -- MongoDB document schema
  DocumentRepository.java              -- Spring Data MongoDB repository
  Operations.java                      -- WebSocket message model
  Comment.java                         -- Comment data model

src/main/resources/
  application.properties               -- MongoDB connection config


CONFIGURATION
--------------
MongoDB URI is set in src/main/resources/application.properties:
  spring.data.mongodb.uri=mongodb://localhost:27017/collab_editor

To change the port, add to application.properties:
  server.port=8080


KNOWN NOTES
------------
- The server IP in StartScreen.java is currently hardcoded to 172.20.10.3.
  Change this to your server machine's IP or "localhost" for local testing.
- MongoDB must be running BEFORE starting the server or the app will fail
  to connect to the database (it will still run but persistence won't work).
- Blocks automatically split when they exceed 10 lines and merge when
  they fall below 2 lines, in accordance with the CRDT block constraints.
- The undo/redo stack holds up to 10 operations per user.
- Disconnected clients have a 5-minute window to reconnect and receive
  any operations they missed.

=========================================================
