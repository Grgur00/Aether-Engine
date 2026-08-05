package io.aetherdb.workbench;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

final class RecordDialog {
    private RecordDialog() {}

    /** Keeps a descriptor-derived form shape while removing values copied from another record. */
    static String blankStructuredValues(String encoded) {
        StringBuilder blank = new StringBuilder();
        for (String line : encoded.split("\\R", -1)) {
            if (line.isBlank()) continue;
            int equals = line.indexOf('=');
            if (equals <= 0) return "";
            String metadata = line.substring(0, equals);
            int typeSeparator = metadata.lastIndexOf(':');
            if (typeSeparator <= 0) return "";
            String type = metadata.substring(typeSeparator + 1);
            String defaultValue = switch (type) {
                case "bool" -> "false";
                case "long" -> "0";
                case "double" -> "0.0";
                default -> "";
            };
            blank.append(metadata).append('=').append(defaultValue).append('\n');
        }
        return blank.toString();
    }

    static Optional<RecordInput> show(Component parent, String title, String key, String value) {
        return show(parent, title, key, value, true);
    }

    static Optional<RecordInput> show(
            Component parent, String title, String key, String value, boolean keyEditable) {
        JTextField keyField = new JTextField(key, 36);
        keyField.setEditable(keyEditable);
        ValueEditor valueEditor = StructuredEditor.create(value)
                .<ValueEditor>map(editor -> editor)
                .orElseGet(() -> new TextEditor(value));
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(5, 5, 5, 5); constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.gridx = 0; constraints.gridy = 0; form.add(new JLabel("Key"), constraints);
        constraints.gridx = 1; constraints.weightx = 1; constraints.fill = GridBagConstraints.HORIZONTAL; form.add(keyField, constraints);
        constraints.gridx = 0; constraints.gridy = 1; constraints.weightx = 0; constraints.fill = GridBagConstraints.NONE;
        form.add(new JLabel(valueEditor instanceof StructuredEditor ? "Fields" : "Value"), constraints);
        constraints.gridx = 1; constraints.weightx = 1; constraints.weighty = 1; constraints.fill = GridBagConstraints.BOTH;
        form.add(valueEditor.component(), constraints);
        JPanel content = new JPanel(new BorderLayout()); content.add(form);
        int result = JOptionPane.showConfirmDialog(parent, content, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return Optional.empty();
        if (keyField.getText().isEmpty()) {
            JOptionPane.showMessageDialog(parent, "Key must not be empty in the text workbench.", "Invalid key", JOptionPane.WARNING_MESSAGE);
            return Optional.empty();
        }
        return Optional.of(new RecordInput(keyField.getText(), valueEditor.value()));
    }

    private interface ValueEditor {
        JComponent component();
        String value();
    }

    private static final class TextEditor implements ValueEditor {
        private final JTextArea area;
        private final JScrollPane scroll;

        private TextEditor(String value) {
            area = new JTextArea(value, 10, 36);
            area.setLineWrap(true); area.setWrapStyleWord(true);
            scroll = new JScrollPane(area);
            scroll.setPreferredSize(new Dimension(520, 260));
        }

        @Override public JComponent component() { return scroll; }
        @Override public String value() { return area.getText(); }
    }

    private static final class StructuredEditor implements ValueEditor {
        private final List<FieldInput> fields;
        private final JScrollPane scroll;

        private StructuredEditor(List<FieldInput> fields) {
            this.fields = List.copyOf(fields);
            JPanel rows = new JPanel(new GridBagLayout());
            GridBagConstraints label = new GridBagConstraints();
            label.anchor = GridBagConstraints.WEST;
            label.insets = new Insets(5, 5, 5, 12);
            GridBagConstraints input = new GridBagConstraints();
            input.weightx = 1; input.fill = GridBagConstraints.HORIZONTAL;
            input.insets = new Insets(5, 0, 5, 5);
            for (int index = 0; index < fields.size(); index++) {
                FieldInput field = fields.get(index);
                label.gridx = 0; label.gridy = index;
                String fieldLabel = field.name().isEmpty()
                        ? "Field " + field.id()
                        : field.name() + "  ·  Field " + field.id();
                rows.add(new JLabel(fieldLabel + "  ·  " + field.type()), label);
                input.gridx = 1; input.gridy = index;
                rows.add(field.input(), input);
            }
            GridBagConstraints filler = new GridBagConstraints();
            filler.gridx = 0; filler.gridy = fields.size(); filler.gridwidth = 2;
            filler.weighty = 1; filler.fill = GridBagConstraints.VERTICAL;
            rows.add(new JPanel(), filler);
            scroll = new JScrollPane(rows);
            scroll.setBorder(null);
            scroll.setPreferredSize(new Dimension(620, Math.min(360, 55 + fields.size() * 42)));
        }

        static Optional<StructuredEditor> create(String encoded) {
            List<FieldInput> fields = new ArrayList<>();
            for (String line : encoded.split("\\R", -1)) {
                if (line.isBlank()) continue;
                int colon = line.indexOf(':');
                int equals = line.indexOf('=', colon + 1);
                if (colon <= 0 || equals <= colon + 1) return Optional.empty();
                int id;
                try { id = Integer.parseInt(line.substring(0, colon).strip()); }
                catch (NumberFormatException ignored) { return Optional.empty(); }
                String metadata = line.substring(colon + 1, equals).strip();
                int nameSeparator = metadata.lastIndexOf(':');
                String name = nameSeparator < 0 ? "" : metadata.substring(0, nameSeparator).strip();
                String type = (nameSeparator < 0 ? metadata : metadata.substring(nameSeparator + 1)).strip();
                if (!isFieldType(type)) return Optional.empty();
                String value;
                try { value = UniversalTypedValue.unescapeText(line.substring(equals + 1)); }
                catch (IllegalArgumentException ignored) { return Optional.empty(); }
                fields.add(new FieldInput(id, name, type, new JTextField(value, 38)));
            }
            return fields.isEmpty() ? Optional.empty() : Optional.of(new StructuredEditor(fields));
        }

        private static boolean isFieldType(String type) {
            return type.equals("bool") || type.equals("long") || type.equals("double")
                    || type.equals("string") || type.equals("uuid") || type.equals("instant")
                    || type.startsWith("wire-");
        }

        @Override public JComponent component() { return scroll; }

        @Override public String value() {
            StringBuilder encoded = new StringBuilder();
            for (FieldInput field : fields) {
                encoded.append(field.id()).append(':');
                if (!field.name().isEmpty()) encoded.append(field.name()).append(':');
                encoded.append(field.type()).append('=')
                        .append(UniversalTypedValue.escapeText(field.input().getText())).append('\n');
            }
            return encoded.toString();
        }
    }

    private record FieldInput(int id, String name, String type, JTextField input) {}

    record RecordInput(String key, String value) {}
}
