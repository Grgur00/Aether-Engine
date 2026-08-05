package io.aetherdb.workbench;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Optional;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

final class RecordDialog {
    private RecordDialog() {}

    static Optional<RecordInput> show(Component parent, String title, String key, String value) {
        JTextField keyField = new JTextField(key, 36);
        JTextArea valueArea = new JTextArea(value, 10, 36);
        valueArea.setLineWrap(true); valueArea.setWrapStyleWord(true);
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(5, 5, 5, 5); constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.gridx = 0; constraints.gridy = 0; form.add(new JLabel("Key"), constraints);
        constraints.gridx = 1; constraints.weightx = 1; constraints.fill = GridBagConstraints.HORIZONTAL; form.add(keyField, constraints);
        constraints.gridx = 0; constraints.gridy = 1; constraints.weightx = 0; constraints.fill = GridBagConstraints.NONE; form.add(new JLabel("Value"), constraints);
        constraints.gridx = 1; constraints.weightx = 1; constraints.weighty = 1; constraints.fill = GridBagConstraints.BOTH;
        JScrollPane valueScroll = new JScrollPane(valueArea); valueScroll.setPreferredSize(new Dimension(440, 210)); form.add(valueScroll, constraints);
        JPanel content = new JPanel(new BorderLayout()); content.add(form);
        int result = JOptionPane.showConfirmDialog(parent, content, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return Optional.empty();
        if (keyField.getText().isEmpty()) {
            JOptionPane.showMessageDialog(parent, "Key must not be empty in the text workbench.", "Invalid key", JOptionPane.WARNING_MESSAGE);
            return Optional.empty();
        }
        return Optional.of(new RecordInput(keyField.getText(), valueArea.getText()));
    }

    record RecordInput(String key, String value) {}
}
