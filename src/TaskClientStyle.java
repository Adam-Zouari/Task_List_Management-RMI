import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.plaf.FontUIResource;
import javax.swing.text.JTextComponent;
import javax.swing.text.StyleContext;

/** Shared presentation settings for the Swing client and its standard dialogs. */
final class TaskClientStyle {
    static final Color BACKGROUND = new Color(0xF5F7FA);
    static final Color SURFACE = Color.WHITE;
    static final Color BORDER = new Color(0xDCE2EA);
    static final Color TEXT = new Color(0x1F2937);
    static final Color MUTED = new Color(0x6B7280);
    static final Color BLUE = new Color(0x2563EB);
    static final Color SELECTION = new Color(0xDBEAFE);
    static final Color STRIPE = new Color(0xF3F7FC);
    static final Color GREEN = new Color(0x217346);
    static final Color GREEN_SURFACE = new Color(0xEDF8F0);
    static final Color RED = new Color(0xB42332);
    static final Color RED_SURFACE = new Color(0xFFF1F2);
    static final Font FONT = StyleContext.getDefaultStyleContext().getFont(fontFamily(), Font.PLAIN, 14);
    static final Font SMALL_FONT = FONT.deriveFont(12f);

    private TaskClientStyle() {
    }

    private static String fontFamily() {
        for (String family : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
            if ("Segoe UI".equalsIgnoreCase(family)) {
                return family;
            }
        }
        return Font.SANS_SERIF;
    }

    static void install() {
        FontUIResource font = new FontUIResource(FONT);
        for (String key : new String[] {"Label.font", "Button.font", "TextField.font", "TextArea.font",
                "Table.font", "TableHeader.font", "CheckBox.font", "OptionPane.messageFont",
                "OptionPane.buttonFont"}) {
            UIManager.put(key, font);
        }
        UIManager.put("Panel.background", SURFACE);
        UIManager.put("OptionPane.background", SURFACE);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("TextField.inactiveForeground", MUTED);
        UIManager.put("TextField.inactiveBackground", BACKGROUND);
        UIManager.put("TextArea.foreground", TEXT);
        UIManager.put("TextField.selectionBackground", SELECTION);
        UIManager.put("TextArea.selectionBackground", SELECTION);
        UIManager.put("TextField.selectionForeground", TEXT);
        UIManager.put("TextArea.selectionForeground", TEXT);
        UIManager.put("Table.selectionBackground", SELECTION);
        UIManager.put("Table.selectionForeground", TEXT);
        UIManager.put("CheckBox.background", SURFACE);
    }

    static Border outline(Color color) {
        return BorderFactory.createLineBorder(color, 1, true);
    }

    static void field(JTextComponent field) {
        field.setFont(FONT);
        field.setForeground(TEXT);
        field.setBackground(SURFACE);
        field.setCaretColor(TEXT);
        field.setSelectionColor(SELECTION);
        field.setSelectedTextColor(TEXT);
        field.setDisabledTextColor(MUTED);
        Runnable update = () -> field.setBorder(BorderFactory.createCompoundBorder(
                outline(field.hasFocus() ? BLUE : BORDER), BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                update.run();
            }

            @Override
            public void focusLost(FocusEvent event) {
                update.run();
            }
        });
        update.run();
    }

    static void button(JButton button, boolean primary, boolean destructive) {
        button.setFont(FONT.deriveFont(Font.BOLD));
        button.setContentAreaFilled(false);
        button.setOpaque(true);
        button.setFocusPainted(false);
        button.setRolloverEnabled(true);
        Runnable update = () -> {
            boolean enabled = button.isEnabled();
            boolean active = button.getModel().isPressed() || button.getModel().isRollover();
            Color background = primary ? BLUE : SURFACE;
            if (active && enabled) {
                background = primary ? new Color(0x1D4ED8) : destructive ? RED_SURFACE : STRIPE;
            }
            button.setBackground(enabled ? background : new Color(0xEDF0F4));
            button.setForeground(!enabled ? MUTED : primary ? SURFACE : destructive ? RED : TEXT);
            Color edge = enabled && button.hasFocus() ? TEXT : enabled && primary ? BLUE : BORDER;
            button.setBorder(BorderFactory.createCompoundBorder(outline(edge),
                    BorderFactory.createEmptyBorder(8, 14, 8, 14)));
        };
        button.getModel().addChangeListener(event -> update.run());
        button.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                update.run();
            }

            @Override
            public void focusLost(FocusEvent event) {
                update.run();
            }
        });
        update.run();
    }
}
