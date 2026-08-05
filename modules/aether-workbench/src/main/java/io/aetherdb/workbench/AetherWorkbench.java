package io.aetherdb.workbench;

import io.aetherdb.engine.Aether;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.UUID;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTabbedPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableCellRenderer;

/** Desktop browser and universal typed-value editor for Aether databases. */
public final class AetherWorkbench {
    private final DatabaseWorkspace workspace;
    private final WorkspaceTableModel model = new WorkspaceTableModel();
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel("Ready — in-memory session");
    private final JFrame frame = new JFrame("Aether Engine Workbench");
    private final boolean persistent;
    private final String sessionDescription;

    private AetherWorkbench(
            io.aetherdb.api.AetherDatabase database,
            boolean ownsDatabase,
            boolean persistent,
            String sessionDescription) {
        workspace = new DatabaseWorkspace(database, ownsDatabase);
        this.persistent = persistent;
        this.sessionDescription = sessionDescription;
        build();
        refresh("Ready");
    }

    public static void main(String[] arguments) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException ignored) { /* platform fallback */ }
            try {
                if (arguments.length == 0) {
                    new AetherWorkbench(Aether.openInMemory(), true, false, "in-memory session")
                            .frame.setVisible(true);
                }
                else {
                    Path directory = Path.of(arguments[0]).toAbsolutePath().normalize();
                    new AetherWorkbench(Aether.open(directory), true, true, directory.toString())
                            .frame.setVisible(true);
                }
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

    /** Opens the workbench on an application's existing live database without taking ownership of it. */
    public static void open(io.aetherdb.api.AetherDatabase database) {
        if (database == null) throw new IllegalArgumentException("database must not be null");
        SwingUtilities.invokeLater(() -> new AetherWorkbench(
                database, false, false, "attached application session").frame.setVisible(true));
    }

    private void build() {
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setMinimumSize(new Dimension(760, 500)); frame.setSize(980, 640); frame.setLocationByPlatform(true);
        frame.addWindowListener(new java.awt.event.WindowAdapter() { @Override public void windowClosed(java.awt.event.WindowEvent event) { workspace.close(); } });

        JPanel heading = new JPanel(new BorderLayout(12, 4)); heading.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));
        JLabel title = new JLabel("Aether Data Explorer"); title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        JLabel notice = new JLabel(persistent
                ? "Persistent database • schema-aware editing • " + sessionDescription
                : "In-memory session • data is discarded when this window closes");
        notice.setForeground(new Color(150, 75, 0));
        heading.add(title, BorderLayout.NORTH); heading.add(notice, BorderLayout.SOUTH);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 7));
        JButton add = new JButton("Add entry"); JButton edit = new JButton("Edit selected");
        JButton delete = new JButton("Delete selected"); JButton refresh = new JButton("Refresh");
        add.addActionListener(event -> addRecord()); edit.addActionListener(event -> editRecord());
        delete.addActionListener(event -> deleteRecord()); refresh.addActionListener(event -> refresh("Refreshed"));
        add.setEnabled(true);
        edit.setEnabled(true);
        delete.setEnabled(true);
        actions.add(add); actions.add(edit); actions.add(delete); actions.add(refresh);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setAutoCreateRowSorter(true);
        table.setRowHeight(26); table.getColumnModel().getColumn(0).setPreferredWidth(270);
        table.getColumnModel().getColumn(1).setPreferredWidth(150); table.getColumnModel().getColumn(2).setPreferredWidth(430);
        table.getColumnModel().getColumn(3).setPreferredWidth(90);
        GroupedRowRenderer groupedRenderer = new GroupedRowRenderer();
        for (int column = 0; column < table.getColumnCount(); column++) table.getColumnModel().getColumn(column).setCellRenderer(groupedRenderer);
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) editRecord();
            }
        });
        JScrollPane scroll = new JScrollPane(table); scroll.setBorder(BorderFactory.createEmptyBorder(0, 18, 0, 18));
        status.setBorder(BorderFactory.createEmptyBorder(9, 18, 12, 18));

        frame.add(heading, BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout()); center.add(actions, BorderLayout.NORTH); center.add(scroll, BorderLayout.CENTER);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Data Explorer", center);
        tabs.addTab("RPC Frame Inspector", new RpcFrameInspector());
        tabs.addTab("Replication Inspector", new ReplicationInspector());
        frame.add(tabs, BorderLayout.CENTER); frame.add(status, BorderLayout.SOUTH);
    }

    private void addRecord() {
        if (persistent) {
            DatabaseWorkspace.Row selected = selectedRow();
            if (selected == null) return;
            if (!selected.key().startsWith("collection/")) {
                showError("Select an existing typed entry in the target collection first.");
                return;
            }
            RecordDialog.show(
                    frame,
                    "Add entry using selected collection/schema",
                    suggestedKey(selected),
                    RecordDialog.blankStructuredValues(selected.value()),
                    true)
                    .ifPresent(input -> {
                        try {
                            String key = workspace.addTypedEntry(
                                    selected.key(), input.key(), input.value());
                            refresh("Added “" + key + "”");
                        }
                        catch (IllegalArgumentException failure) {
                            showError(failure.getMessage());
                        }
                    });
            return;
        }
        RecordDialog.show(frame, "Add record", "", "").ifPresent(input -> {
            if (workspace.contains(input.key())) { showError("That key already exists. Select it and choose Edit selected."); return; }
            workspace.put(input.key(), input.value()); refresh("Added “" + input.key() + "”");
        });
    }

    private static String suggestedKey(DatabaseWorkspace.Row selected) {
        String field = selected.field();
        try {
            UUID.fromString(field);
            return UUID.randomUUID().toString();
        }
        catch (IllegalArgumentException ignored) {
            return "new-key";
        }
    }

    private void editRecord() {
        DatabaseWorkspace.Row selected = selectedRow(); if (selected == null) return;
        if (!workspace.canEdit(selected.key())) {
            showError("This entry does not contain a valid editable value envelope.");
            return;
        }
        RecordDialog.show(
                frame,
                "Edit record",
                selected.key(),
                selected.value(),
                workspace.keyEditable(selected.key())).ifPresent(input -> {
            try { workspace.edit(selected.key(), input.key(), input.value()); refresh("Updated “" + input.key() + "”"); }
            catch (IllegalArgumentException failure) { showError(failure.getMessage()); }
        });
    }

    private void deleteRecord() {
        DatabaseWorkspace.Row selected = selectedRow(); if (selected == null) return;
        int choice = JOptionPane.showConfirmDialog(frame, "Delete “" + selected.key() + "”?", "Delete record", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.OK_OPTION) { workspace.delete(selected.key()); refresh("Deleted “" + selected.key() + "”"); }
    }

    private DatabaseWorkspace.Row selectedRow() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) { showError("Select a record first."); return null; }
        return model.row(table.convertRowIndexToModel(viewRow));
    }
    private void refresh(String message) {
        model.replaceRows(workspace.rows());
        status.setText(message + " • " + workspace.size() + " record(s) • " + sessionDescription);
    }
    private void showError(String message) { JOptionPane.showMessageDialog(frame, message, "Aether Workbench", JOptionPane.WARNING_MESSAGE); }

    private final class GroupedRowRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;
        private final Color alternateGroup = new Color(245, 248, 252);

        @Override public Component getTableCellRendererComponent(
                JTable source, Object value, boolean selected, boolean focused, int viewRow, int viewColumn) {
            int modelRow = source.convertRowIndexToModel(viewRow);
            DatabaseWorkspace.Row row = model.row(modelRow);
            boolean startsGroup = viewRow == 0 || !row.group().equals(model.row(source.convertRowIndexToModel(viewRow - 1)).group());
            Object displayValue = viewColumn == 0 && !startsGroup ? "" : value;
            Component component = super.getTableCellRendererComponent(source, displayValue, selected, focused, viewRow, viewColumn);
            if (!selected) component.setBackground((row.group().hashCode() & 1) == 0 ? alternateGroup : source.getBackground());
            setBorder(startsGroup
                    ? BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(205, 212, 220))
                    : BorderFactory.createEmptyBorder());
            setFont(viewColumn == 0 && startsGroup ? source.getFont().deriveFont(Font.BOLD) : source.getFont());
            return component;
        }
    }
}
