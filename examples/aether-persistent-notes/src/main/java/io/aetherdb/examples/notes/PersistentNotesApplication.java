package io.aetherdb.examples.notes;

import io.aetherdb.api.typed.TypedAetherDatabase;
import io.aetherdb.embedded.typed.AetherEmbedded;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/** Small desktop app proving that typed Aether data survives close and reopen. */
public final class PersistentNotesApplication {
    private final TypedAetherDatabase database;
    private final NotesRepository notes;
    private final DefaultListModel<Note> model = new DefaultListModel<>();
    private final JFrame frame = new JFrame("Aether Persistent Notes");
    private final JTextField input = new JTextField();

    private PersistentNotesApplication(Path directory) {
        database = AetherEmbedded.open(directory);
        notes = new NotesRepository(database);
        notes.addWelcomeNotesIfEmpty();
        notes.findAll().forEach(model::addElement);
        buildWindow(directory);
    }

    public static void main(String[] arguments) {
        Path directory = arguments.length == 0
                ? Path.of("examples", "aether-persistent-notes", "data", "aether-notes")
                : Path.of(arguments[0]);
        SwingUtilities.invokeLater(() -> {
            try {
                new PersistentNotesApplication(directory).frame.setVisible(true);
            }
            catch (RuntimeException failure) {
                JOptionPane.showMessageDialog(
                        null,
                        failure.getMessage(),
                        "Cannot open Aether database",
                        JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private void buildWindow(Path directory) {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(620, 420));
        frame.setLocationByPlatform(true);
        JLabel location = new JLabel("Database: " + directory.toAbsolutePath().normalize());
        location.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));

        JList<Note> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JButton add = new JButton("Add and persist");
        add.addActionListener(event -> addNote());
        input.addActionListener(event -> addNote());

        JPanel editor = new JPanel(new BorderLayout(8, 0));
        editor.setBorder(BorderFactory.createEmptyBorder(8, 10, 10, 10));
        editor.add(input, BorderLayout.CENTER);
        editor.add(add, BorderLayout.EAST);

        frame.add(location, BorderLayout.NORTH);
        frame.add(new JScrollPane(list), BorderLayout.CENTER);
        frame.add(editor, BorderLayout.SOUTH);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                close();
            }
        });
        frame.pack();
        input.requestFocusInWindow();
    }

    private void addNote() {
        try {
            Note note = notes.add(input.getText());
            model.addElement(note);
            input.setText("");
            input.requestFocusInWindow();
        }
        catch (RuntimeException failure) {
            JOptionPane.showMessageDialog(
                    frame,
                    failure.getMessage(),
                    "Could not save note",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void close() {
        try {
            database.close();
        }
        catch (RuntimeException failure) {
            JOptionPane.showMessageDialog(
                    frame, failure.getMessage(), "Close failed", JOptionPane.ERROR_MESSAGE);
        }
        finally {
            frame.dispose();
        }
    }
}
