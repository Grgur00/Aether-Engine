package io.aetherdb.workbench;

import java.util.List;

import javax.swing.table.AbstractTableModel;

/** Swing table model exposing the fields of the currently selected database entry. */
final class WorkspaceTableModel extends AbstractTableModel {
    private static final long serialVersionUID = 1L;
    private static final String[] COLUMNS = {"Group", "Field", "Value", "Value bytes"};
    private transient List<DatabaseWorkspace.Row> rows = List.of();

    void replaceRows(List<DatabaseWorkspace.Row> replacement) {
        rows = List.copyOf(replacement);
        fireTableDataChanged();
    }

    DatabaseWorkspace.Row row(int modelIndex) {
        return rows.get(modelIndex);
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(int column) {
        return column == 3 ? Integer.class : String.class;
    }

    @Override
    public Object getValueAt(int row, int column) {
        DatabaseWorkspace.Row value = rows.get(row);
        return switch (column) {
            case 0 -> value.group();
            case 1 -> value.field();
            case 2 -> value.value();
            case 3 -> value.valueBytes();
            default -> throw new IndexOutOfBoundsException(column);
        };
    }
}
