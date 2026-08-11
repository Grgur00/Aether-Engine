package io.aetherdb.workbench;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.aetherdb.api.WriteBatch;
import io.aetherdb.replication.log.ReplicatedWriteCommandV1;
import io.aetherdb.replication.log.StateSequencePlanner;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.HexFormat;
import java.util.UUID;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;

/** Visual encoder/decoder for deterministic replicated write commands. */
final class ReplicationInspector extends JPanel {
    private static final long serialVersionUID = 1L;
    private final JTextField key = new JTextField("example-key");
    private final JTextField value = new JTextField("example-value");
    private final JSpinner previousSequence =
            new JSpinner(new SpinnerNumberModel(0L, 0L, Long.MAX_VALUE - 1, 1L));
    private final JTextArea encoded = new JTextArea(14, 60);
    private final JLabel summary =
            new JLabel("One write batch maps to one deterministic replicated command");

    ReplicationInspector() {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 18, 12, 18));
        JPanel fields = new JPanel(new GridLayout(2, 3, 8, 5));
        fields.add(new JLabel("Key"));
        fields.add(new JLabel("Value"));
        fields.add(new JLabel("Previous state sequence"));
        fields.add(key);
        fields.add(value);
        fields.add(previousSequence);
        encoded.setLineWrap(true);
        encoded.setWrapStyleWord(true);
        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.add(fields, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(encoded);
        scroll.setBorder(BorderFactory.createTitledBorder("Replicated command bytes (hex)"));
        center.add(scroll);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton encodeButton = new JButton("Encode PUT command");
        JButton decodeButton = new JButton("Decode command");
        encodeButton.addActionListener(event -> encode());
        decodeButton.addActionListener(event -> decode());
        actions.add(encodeButton);
        actions.add(decodeButton);
        actions.add(summary);
        add(center);
        add(actions, BorderLayout.SOUTH);
    }

    private void encode() {
        try (WriteBatch batch =
                new WriteBatch()
                        .put(key.getText().getBytes(UTF_8), value.getText().getBytes(UTF_8))) {
            var sequences = StateSequencePlanner.plan((Long) previousSequence.getValue(), 1);
            byte[] bytes =
                    ReplicatedWriteCommandV1.fromBatch(UUID.randomUUID(), sequences, batch)
                            .encode();
            encoded.setText(HexFormat.of().formatHex(bytes));
            summary.setText(
                    "Encoded "
                            + bytes.length
                            + " bytes • sequence "
                            + sequences.first()
                            + " • SHA-256 body protected");
        } catch (RuntimeException failure) {
            showError(failure);
        }
    }

    private void decode() {
        try {
            var command =
                    ReplicatedWriteCommandV1.decode(
                            HexFormat.of().parseHex(encoded.getText().replaceAll("\\s", "")));
            var operation = command.operations().get(0);
            key.setText(new String(operation.key(), UTF_8));
            value.setText(new String(operation.value(), UTF_8));
            previousSequence.setValue(command.sequences().first() - 1);
            summary.setText(
                    "Valid command "
                            + command.commandId()
                            + " • "
                            + command.operations().size()
                            + " operation(s) • sequences "
                            + command.sequences().first()
                            + ".."
                            + command.sequences().last());
        } catch (RuntimeException failure) {
            showError(failure);
        }
    }

    private void showError(RuntimeException failure) {
        JOptionPane.showMessageDialog(
                this,
                failure.getMessage(),
                "Invalid replicated command",
                JOptionPane.WARNING_MESSAGE);
    }
}
