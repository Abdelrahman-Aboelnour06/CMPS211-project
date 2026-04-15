import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import javax.swing.text.StyledDocument;
import javax.swing.text.StyleConstants;
import javax.swing.text.SimpleAttributeSet;
import java.util.List;
import javax.swing.UIManager;
import java.awt.Insets;
import java.awt.Font;

public class EditorUI {


    private JFrame frame;
    private JTextPane textPane;

    // 1. Declare your Document manager
    private Document doc;

    public EditorUI(String username) {

        // --- MAKE IT LOOK MODERN ---
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }
        // 2. Initialize the Document with userID 1
        //doc = new Document(1);

        this.doc = new Document(new java.util.Random().nextInt(1000));
        // ... use the username for the Window Title ...
        frame = new JFrame("Collaborative Editor - " + username);

        // --- SETUP THE WINDOW ---
        //frame = new JFrame("Collaborative Text Editor");


        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
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

        // --- SETUP THE TEXT AREA ---
        textPane = new JTextPane();
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

                //doc.LocalInsert(typedChar, safeIndex);
                //renderDocument(safeIndex + 1);
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

    public static void main(String[] args) {
       // SwingUtilities.invokeLater(() -> new EditorUI());
        javax.swing.SwingUtilities.invokeLater(() -> {
            // We are passing "Guest" into the EditorUI here
            new EditorUI("Guest_" + (int)(Math.random() * 100));
        });
    }
}