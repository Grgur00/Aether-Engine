package io.aetherdb.workbench;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.aetherdb.rpc.codec.RpcFrame;
import io.aetherdb.rpc.codec.RpcFrameCodecV1;
import io.aetherdb.rpc.codec.RpcFrameHeaderV1;
import io.aetherdb.rpc.codec.RpcFrameType;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.HexFormat;
import java.util.UUID;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;

/** Interactive exact-byte RPC v1 frame encoder and decoder. */
final class RpcFrameInspector extends JPanel {
    private static final long serialVersionUID = 1L;
    private final JComboBox<RpcFrameType> type =
            new JComboBox<>(new RpcFrameType[] {RpcFrameType.REQUEST, RpcFrameType.RESPONSE});
    private final JSpinner stream =
            new JSpinner(new SpinnerNumberModel(1L, 1L, Long.MAX_VALUE, 1L));
    private final JSpinner code = new JSpinner(new SpinnerNumberModel(1, 0, Integer.MAX_VALUE, 1));
    private final JSpinner timeout =
            new JSpinner(new SpinnerNumberModel(5_000, 0, Integer.MAX_VALUE, 100));
    private final JTextArea payload = new JTextArea("hello from Aether RPC", 5, 60);
    private final JTextArea wireHex = new JTextArea(12, 60);
    private final JLabel summary =
            new JLabel("64-byte big-endian header • masked CRC32C • maximum payload 1 MiB");

    RpcFrameInspector() {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 18, 12, 18));
        JPanel fields = new JPanel(new GridLayout(2, 4, 8, 5));
        fields.add(new JLabel("Frame type"));
        fields.add(new JLabel("Stream ID"));
        fields.add(new JLabel("Operation/status code"));
        fields.add(new JLabel("Timeout ms"));
        fields.add(type);
        fields.add(stream);
        fields.add(code);
        fields.add(timeout);
        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.add(fields, BorderLayout.NORTH);
        JPanel body = new JPanel(new GridLayout(2, 1, 6, 6));
        body.add(titled("UTF-8 payload", new JScrollPane(payload)));
        wireHex.setLineWrap(true);
        wireHex.setWrapStyleWord(true);
        body.add(titled("Wire bytes (hex, editable for decode)", new JScrollPane(wireHex)));
        top.add(body, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton encode = new JButton("Encode frame");
        JButton decode = new JButton("Decode hex");
        JButton corrupt = new JButton("Corrupt last byte");
        encode.addActionListener(event -> encode());
        decode.addActionListener(event -> decode());
        corrupt.addActionListener(event -> corrupt());
        actions.add(encode);
        actions.add(decode);
        actions.add(corrupt);
        actions.add(summary);
        add(top, BorderLayout.CENTER);
        add(actions, BorderLayout.SOUTH);
    }

    private static JPanel titled(String title, JScrollPane content) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(content);
        return panel;
    }

    private void encode() {
        try {
            RpcFrameType selected = (RpcFrameType) type.getSelectedItem();
            byte[] bytes = payload.getText().getBytes(UTF_8);
            int timeoutMillis = selected == RpcFrameType.REQUEST ? (Integer) timeout.getValue() : 0;
            RpcFrameHeaderV1 header =
                    new RpcFrameHeaderV1(
                            selected,
                            RpcFrameHeaderV1.BEGIN | RpcFrameHeaderV1.END,
                            (Long) stream.getValue(),
                            (Integer) code.getValue(),
                            bytes.length,
                            bytes.length,
                            0,
                            timeoutMillis,
                            0,
                            UUID.randomUUID());
            byte[] encoded = RpcFrameCodecV1.encode(new RpcFrame(header, bytes));
            wireHex.setText(HexFormat.of().formatHex(encoded));
            summary.setText(
                    "Encoded "
                            + encoded.length
                            + " bytes: 64 header + "
                            + bytes.length
                            + " payload");
        } catch (RuntimeException failure) {
            showError(failure);
        }
    }

    private void decode() {
        try {
            RpcFrame frame =
                    RpcFrameCodecV1.decode(
                            HexFormat.of().parseHex(wireHex.getText().replaceAll("\\s", "")));
            payload.setText(new String(frame.payload(), UTF_8));
            type.setSelectedItem(frame.header().type());
            stream.setValue(frame.header().streamId());
            code.setValue(frame.header().code());
            timeout.setValue(frame.header().timeoutMillis());
            summary.setText(
                    "Valid "
                            + frame.header().type()
                            + " frame • stream "
                            + frame.header().streamId()
                            + " • checksum verified");
        } catch (RuntimeException failure) {
            showError(failure);
        }
    }

    private void corrupt() {
        String hex = wireHex.getText().replaceAll("\\s", "");
        if (hex.length() < 2) return;
        wireHex.setText(hex.substring(0, hex.length() - 2) + (hex.endsWith("00") ? "01" : "00"));
        summary.setText("Last byte modified — Decode hex should reject the checksum");
    }

    private void showError(RuntimeException failure) {
        JOptionPane.showMessageDialog(
                this, failure.getMessage(), "Invalid RPC frame", JOptionPane.WARNING_MESSAGE);
    }
}
