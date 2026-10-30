/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.ai;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicButtonUI;

/** Local assistant styling; the rest of Logisim keeps its own look and feel. */
final class AiTheme {
  private AiTheme() {}

  private static boolean dark() {
    final var color = UIManager.getColor("Panel.background");
    return color != null
        && color.getRed() * 0.2126 + color.getGreen() * 0.7152 + color.getBlue() * 0.0722 < 128;
  }

  private static Color palette(int light, int dark) {
    return new Color(dark() ? dark : light);
  }

  static Color background() {
    return palette(0xF7F8FA, 0x191C22);
  }

  static Color surface() {
    return palette(0xFFFFFF, 0x22262E);
  }

  static Color foreground() {
    return palette(0x242B36, 0xE5E9F0);
  }

  static Color muted() {
    return palette(0x667183, 0xA5B0C0);
  }

  static Color border() {
    return palette(0xE0E5EC, 0x363D49);
  }

  static Color accent() {
    return palette(0x315ECC, 0x91B0FF);
  }

  static Color onAccent() {
    return palette(0xFFFFFF, 0x15254A);
  }

  static Color userBackground() {
    return palette(0xE8EEFC, 0x2A3752);
  }

  static Color userForeground() {
    return palette(0x273C68, 0xDCE7FF);
  }

  static Color success() {
    return palette(0x25805F, 0x79C6A7);
  }

  static Color errorBackground() {
    return palette(0xFFF0F0, 0x3E292F);
  }

  static Font font(float size) {
    final var system = UIManager.getFont("Label.font");
    return (system == null ? new Font(Font.SANS_SERIF, Font.PLAIN, 14) : system)
        .deriveFont(Font.PLAIN, Math.max(13, size));
  }

  static JPanel roundedPanel(Color fill, Color outline, int radius, Insets padding) {
    final var panel =
        new JPanel() {
          private static final long serialVersionUID = 1L;

          @Override
          protected void paintComponent(Graphics graphics) {
            final var canvas = (Graphics2D) graphics.create();
            try {
              canvas.setRenderingHint(
                  RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
              final int diameter = Math.max(0, radius * 2);
              if (fill != null) {
                canvas.setColor(fill);
                canvas.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, diameter, diameter);
              }
              if (outline != null) {
                canvas.setColor(outline);
                canvas.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, diameter, diameter);
              }
            } finally {
              canvas.dispose();
            }
            super.paintComponent(graphics);
          }
        };
    panel.setOpaque(false);
    panel.setForeground(foreground());
    panel.setFont(font(14));
    panel.setBorder(
        BorderFactory.createEmptyBorder(padding.top, padding.left, padding.bottom, padding.right));
    return panel;
  }

  static void styleButton(AbstractButton button, boolean primary) {
    styleButton(button, primary, false);
  }

  static void styleQuietButton(AbstractButton button) {
    styleButton(button, false, true);
  }

  private static void styleButton(AbstractButton button, boolean primary, boolean quiet) {
    button.putClientProperty("html.disable", Boolean.TRUE);
    button.setUI(new AssistantButtonUI(primary, quiet));
    button.setOpaque(false);
    button.setContentAreaFilled(false);
    button.setBorderPainted(false);
    button.setRolloverEnabled(true);
    button.setFont(font(13));
    button.setForeground(primary ? onAccent() : quiet ? muted() : foreground());
    button.setBackground(primary ? accent() : surface());
    button.setBorder(
        BorderFactory.createEmptyBorder(
            quiet ? 6 : 8, quiet ? 7 : 13, quiet ? 6 : 8, quiet ? 7 : 13));
    button.setIconTextGap(6);
    button.setMinimumSize(new Dimension(0, quiet ? 30 : 36));
  }

  private static final class AssistantButtonUI extends BasicButtonUI {
    private final boolean primary;
    private final boolean quiet;

    AssistantButtonUI(boolean primary, boolean quiet) {
      this.primary = primary;
      this.quiet = quiet;
    }

    @Override
    public Dimension getPreferredSize(JComponent component) {
      final var size = super.getPreferredSize(component);
      return new Dimension(size.width, Math.max(quiet ? 30 : 36, size.height));
    }

    @Override
    public void paint(Graphics graphics, JComponent component) {
      final var button = (AbstractButton) component;
      final var model = button.getModel();
      final var canvas = (Graphics2D) graphics.create();
      try {
        canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (!quiet || model.isRollover() || model.isPressed()) {
          var fill = primary ? accent() : surface();
          if (model.isPressed() || model.isRollover()) {
            fill =
                blend(fill, primary ? onAccent() : foreground(), model.isPressed() ? 0.12 : 0.05);
          }
          if (!button.isEnabled()) fill = blend(background(), fill, 0.55);
          canvas.setColor(fill);
          canvas.fillRoundRect(0, 0, component.getWidth() - 1, component.getHeight() - 1, 16, 16);
          if (!quiet && !primary) {
            canvas.setColor(border());
            canvas.drawRoundRect(0, 0, component.getWidth() - 1, component.getHeight() - 1, 16, 16);
          }
        }
        if (button.isFocusOwner() && button.isFocusPainted()) {
          canvas.setColor(primary ? onAccent() : accent());
          canvas.setStroke(new BasicStroke(1.2f));
          canvas.drawRoundRect(2, 2, component.getWidth() - 5, component.getHeight() - 5, 12, 12);
        }
      } finally {
        canvas.dispose();
      }
      super.paint(graphics, component);
    }
  }

  private static Color blend(Color first, Color second, double proportion) {
    return new Color(
        (int) (first.getRed() * (1 - proportion) + second.getRed() * proportion),
        (int) (first.getGreen() * (1 - proportion) + second.getGreen() * proportion),
        (int) (first.getBlue() * (1 - proportion) + second.getBlue() * proportion));
  }

  static Icon icon(String name) {
    return new OutlineIcon(name);
  }

  private record OutlineIcon(String name) implements Icon {
    @Override
    public int getIconWidth() {
      return 18;
    }

    @Override
    public int getIconHeight() {
      return 18;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
      final var canvas = (Graphics2D) graphics.create();
      try {
        canvas.translate(x, y);
        canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        canvas.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        canvas.setColor(component.getForeground());
        switch (name) {
          case "settings" -> {
            for (int line = 0; line < 3; line++) {
              final int axis = 4 + line * 5;
              final int handle = line == 1 ? 6 : 12;
              canvas.drawLine(2, axis, handle - 3, axis);
              canvas.drawLine(handle + 3, axis, 16, axis);
              canvas.drawOval(handle - 2, axis - 2, 4, 4);
            }
          }
          case "circuit" -> {
            canvas.drawRoundRect(2, 2, 14, 14, 3, 3);
            canvas.drawOval(5, 5, 2, 2);
            canvas.drawOval(11, 11, 2, 2);
            canvas.drawLine(7, 6, 11, 6);
            canvas.drawLine(11, 6, 11, 10);
            canvas.drawLine(5, 11, 8, 11);
            canvas.drawLine(6, 11, 6, 14);
          }
          case "send" -> {
            final var arrow = new Path2D.Float();
            arrow.moveTo(4, 8);
            arrow.lineTo(9, 3);
            arrow.lineTo(14, 8);
            canvas.draw(arrow);
            canvas.drawLine(9, 3, 9, 15);
          }
          case "permission-dot" -> {
            final boolean allowed =
                component.isEnabled()
                    && (!(component instanceof JComponent styled)
                        || !Boolean.FALSE.equals(styled.getClientProperty("ai.permissionAllowed")));
            canvas.setColor(allowed ? success() : muted());
            canvas.fillOval(6, 6, 6, 6);
          }
          default -> canvas.drawOval(4, 4, 10, 10);
        }
      } finally {
        canvas.dispose();
      }
    }
  }
}
