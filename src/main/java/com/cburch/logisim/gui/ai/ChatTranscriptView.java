/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.ai;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.text.AttributedString;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.text.View;

/** Selectable conversation text and structured content, sized to the assistant's narrow sidebar. */
final class ChatTranscriptView extends JPanel implements Scrollable {
  private static final long serialVersionUID = 1L;
  private final List<MessageRow> rows = new ArrayList<>();
  private List<Message> messages = List.of();
  private String emptyText = "";
  private long revision;
  private int measuredWidth = -1;

  enum Kind {
    USER,
    ASSISTANT,
    DEBUG,
    ERROR
  }

  record Message(Kind kind, String title, String text, String stableId, JComponent content) {
    Message(Kind kind, String title, String text) {
      this(kind, title, text, "", null);
    }

    Message(Kind kind, String title, String text, String stableId) {
      this(kind, title, text, stableId, null);
    }

    Message {
      Objects.requireNonNull(kind);
      title = title == null ? "" : title;
      text = text == null ? "" : text;
      stableId = stableId == null ? "" : stableId;
    }
  }

  private record MessageIdentity(Kind kind, String stableId) {}

  private static Object matchingKey(Message message) {
    return message.stableId().isBlank()
        ? message
        : new MessageIdentity(message.kind(), message.stableId());
  }

  private static String displayText(Message message) {
    return message.kind() == Kind.ASSISTANT
        ? AssistantTextFormatter.toDisplayText(message.text())
        : message.text();
  }

  ChatTranscriptView() {
    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    setBorder(BorderFactory.createEmptyBorder(12, 18, 24, 18));
    setBackground(AiTheme.background());
    setForeground(AiTheme.foreground());
    setFont(AiTheme.font(14));
    setName("transcript");
  }

  void setEmptyText(String text) {
    emptyText = text == null ? "" : text;
    repaint();
  }

  /** Embedded cards can change height without changing their message identity. */
  void refreshContentLayout() {
    measuredWidth = -1;
    revalidate();
    repaint();
  }

  @Override
  protected void paintComponent(Graphics graphics) {
    super.paintComponent(graphics);
    if (!messages.isEmpty() || emptyText.isBlank()) return;
    final var canvas = (Graphics2D) graphics.create();
    try {
      canvas.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      final var font = AiTheme.font(14);
      final var layouts = new ArrayList<TextLayout>();
      float height = 0;
      for (final var paragraph : emptyText.split("\\R", -1)) {
        final var attributed = new AttributedString(paragraph.isEmpty() ? " " : paragraph);
        attributed.addAttribute(TextAttribute.FONT, font);
        final var iterator = attributed.getIterator();
        final var lines = new LineBreakMeasurer(iterator, canvas.getFontRenderContext());
        while (lines.getPosition() < iterator.getEndIndex()) {
          final var line = lines.nextLayout(Math.max(1, getWidth() - 40));
          layouts.add(line);
          height += line.getAscent() + line.getDescent() + line.getLeading();
        }
      }
      canvas.setColor(AiTheme.muted());
      float y = Math.max(28, (getHeight() - height) / 2);
      for (final var line : layouts) {
        y += line.getAscent();
        line.draw(canvas, Math.max(20, (getWidth() - line.getAdvance()) / 2), y);
        y += line.getDescent() + line.getLeading();
      }
    } finally {
      canvas.dispose();
    }
  }

  /** Stable messages retain their fold state and text components, including copied selections. */
  void setMessages(List<Message> updated) {
    final var next = List.copyOf(updated);
    if (messages.equals(next)) return;

    final var scrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
    final var scrollbar = scrollPane == null ? null : scrollPane.getVerticalScrollBar();
    final int oldPosition = scrollbar == null ? 0 : scrollbar.getValue();
    final boolean followTail =
        scrollbar == null
            || oldPosition + scrollbar.getVisibleAmount() >= scrollbar.getMaximum() - 8;

    final var available = new HashMap<Object, ArrayDeque<MessageRow>>();
    for (final var row : rows) {
      available.computeIfAbsent(matchingKey(row.message), ignored -> new ArrayDeque<>()).add(row);
    }
    final var nextRows = new ArrayList<MessageRow>();
    for (final var message : next) {
      final var matches = available.get(matchingKey(message));
      final var row =
          matches == null || matches.isEmpty() ? new MessageRow(message) : matches.remove();
      row.updateMessage(message);
      nextRows.add(row);
    }

    // Appending is the usual path: leave already-visible, selectable components in place.
    int shared = 0;
    while (shared < rows.size()
        && shared < nextRows.size()
        && rows.get(shared) == nextRows.get(shared)) {
      shared++;
    }
    for (int i = rows.size() - 1; i >= shared; i--) remove(rows.get(i));
    for (int i = shared; i < nextRows.size(); i++) add(nextRows.get(i));
    rows.clear();
    rows.addAll(nextRows);
    messages = next;
    final long currentRevision = ++revision;
    revalidate();
    repaint();

    if (scrollbar != null) {
      SwingUtilities.invokeLater(
          () -> {
            if (revision != currentRevision) return;
            scrollPane.validate();
            scrollbar.setValue(followTail ? scrollbar.getMaximum() : oldPosition);
          });
    }
  }

  String plainText() {
    final var text = new StringBuilder();
    for (final var message : messages) {
      if (!text.isEmpty()) text.append("\n\n");
      if ((message.kind() == Kind.DEBUG || message.kind() == Kind.ERROR)
          && !message.title().isBlank()) {
        text.append(message.title()).append(":\n");
      }
      text.append(message.text());
    }
    return text.toString();
  }

  @Override
  public Dimension getPreferredScrollableViewportSize() {
    return new Dimension(360, 420);
  }

  @Override
  public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
    return 18;
  }

  @Override
  public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
    final int extent =
        orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
    return Math.max(18, extent - 18);
  }

  @Override
  public boolean getScrollableTracksViewportWidth() {
    return true;
  }

  @Override
  public boolean getScrollableTracksViewportHeight() {
    return messages.isEmpty();
  }

  private void updateLayoutWidth() {
    if (!(getLayout() instanceof BoxLayout layout)) return;
    final int width = contentWidth();
    if (width != measuredWidth) {
      measuredWidth = width;
      // BoxLayout caches child heights. Wrapped text has a different height at each view width.
      layout.invalidateLayout(this);
    }
  }

  @Override
  public Dimension getPreferredSize() {
    updateLayoutWidth();
    return super.getPreferredSize();
  }

  @Override
  public void doLayout() {
    updateLayoutWidth();
    super.doLayout();
  }

  private int contentWidth() {
    final var insets = getInsets();
    int width = getWidth();
    if (getParent() instanceof JViewport viewport && viewport.getExtentSize().width > 0) {
      width = viewport.getExtentSize().width;
    }
    return Math.max(100, (width > 0 ? width : 340) - insets.left - insets.right);
  }

  private final class MessageRow extends JPanel {
    private static final long serialVersionUID = 1L;
    private Message message;
    private final Card card;
    private final JTextArea text;
    private final JLabel title;
    private final JToggleButton disclosure;
    private JComponent content;

    MessageRow(Message message) {
      super(null);
      this.message = message;
      setOpaque(false);
      setAlignmentX(LEFT_ALIGNMENT);
      setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
      updateRowName();
      card = new Card(message.kind());
      text = new JTextArea(displayText(message));
      text.setName("messageText");
      text.setEditable(false);
      text.setLineWrap(true);
      text.setWrapStyleWord(true);
      text.setOpaque(false);
      text.setBorder(null);
      text.setMargin(new Insets(0, 0, 0, 0));
      final var font = AiTheme.font(message.kind() == Kind.DEBUG ? 13 : 14);
      text.setFont(font);
      text.setForeground(
          message.kind() == Kind.USER
              ? AiTheme.userForeground()
              : message.kind() == Kind.DEBUG ? AiTheme.muted() : AiTheme.foreground());
      card.add(text);
      if (message.kind() == Kind.DEBUG) {
        title = null;
        disclosure = new JToggleButton(message.title(), new DisclosureIcon());
        disclosure.putClientProperty("html.disable", Boolean.TRUE);
        disclosure.setName("messageDisclosure");
        disclosure.setFont(AiTheme.font(13));
        disclosure.setForeground(AiTheme.muted());
        disclosure.setOpaque(false);
        disclosure.setContentAreaFilled(false);
        disclosure.setBorderPainted(false);
        disclosure.setMargin(new Insets(0, 0, 0, 0));
        disclosure.setHorizontalAlignment(SwingConstants.LEADING);
        disclosure.getAccessibleContext().setAccessibleName(message.title());
        disclosure.setToolTipText(message.title());
        disclosure.addActionListener(
            event -> {
              text.setVisible(disclosure.isSelected());
              measuredWidth = -1;
              ChatTranscriptView.this.revalidate();
              ChatTranscriptView.this.repaint();
            });
        disclosure.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "toggleDetails");
        disclosure
            .getActionMap()
            .put(
                "toggleDetails",
                new AbstractAction() {
                  @Override
                  public void actionPerformed(ActionEvent event) {
                    disclosure.doClick(0);
                  }
                });
        text.setVisible(false);
        card.add(disclosure);
      } else if (message.kind() == Kind.ERROR) {
        disclosure = null;
        title = new JLabel(message.title());
        title.putClientProperty("html.disable", Boolean.TRUE);
        title.setName("messageTitle");
        title.setFont(font.deriveFont(Font.BOLD));
        title.setForeground(AiTheme.foreground());
        title.setVisible(!message.title().isBlank());
        card.add(title);
      } else {
        title = null;
        disclosure = null;
      }
      updateContent(message.content());
      add(card);
    }

    private void updateRowName() {
      setName(
          message.content() == null
              ? "message-" + message.kind().name().toLowerCase(java.util.Locale.ROOT)
              : "message-specification");
    }

    private void updateContent(JComponent updated) {
      if (content != updated) {
        if (content != null) card.remove(content);
        content = updated;
        if (content != null) card.add(content);
      }
      text.setVisible(content == null && (disclosure == null || disclosure.isSelected()));
      if (disclosure != null) disclosure.setVisible(content == null);
      if (title != null) title.setVisible(content == null && !message.title().isBlank());
      updateRowName();
    }

    private void updateMessage(Message updated) {
      if (message.equals(updated)) return;
      message = updated;
      final var displayed = displayText(updated);
      if (!text.getText().equals(displayed)) {
        final int mark = text.getCaret().getMark();
        final int dot = text.getCaret().getDot();
        text.setText(displayed);
        text.setCaretPosition(Math.min(mark, displayed.length()));
        text.moveCaretPosition(Math.min(dot, displayed.length()));
      }
      if (disclosure != null) {
        disclosure.setText(updated.title());
        disclosure.setToolTipText(updated.title());
        disclosure.getAccessibleContext().setAccessibleName(updated.title());
      }
      if (title != null) {
        title.setText(updated.title());
        title.setVisible(!updated.title().isBlank());
      }
      updateContent(updated.content());
      measuredWidth = -1;
    }

    private int cardWidth(int rowWidth) {
      final var insets = getInsets();
      final int available = Math.max(64, rowWidth - insets.left - insets.right);
      return message.kind() == Kind.USER ? Math.max(64, available * 89 / 100) : available;
    }

    private int titleHeight() {
      if (content != null) return 0;
      if (disclosure != null) {
        return Math.max(22, disclosure.getPreferredSize().height);
      }
      return title == null || !title.isVisible() ? 0 : title.getPreferredSize().height + 6;
    }

    private int cardHeight(int width) {
      if (content != null) {
        content.setSize(width, Math.max(1, content.getHeight()));
        content.invalidate();
        return Math.max(1, content.getPreferredSize().height);
      }
      if (disclosure != null && !disclosure.isSelected()) return titleHeight();
      // Measure the wrapping view without resizing the component during a preferred-size query.
      final var view = text.getUI().getRootView(text);
      view.setSize(Math.max(1, width - horizontalPadding() * 2), Integer.MAX_VALUE);
      return Math.max(
              text.getFontMetrics(text.getFont()).getHeight(),
              (int) Math.ceil(view.getPreferredSpan(View.Y_AXIS)))
          + titleHeight()
          + verticalPadding();
    }

    private int horizontalPadding() {
      return message.kind() == Kind.ASSISTANT ? 0 : message.kind() == Kind.DEBUG ? 12 : 14;
    }

    private int verticalPadding() {
      return switch (message.kind()) {
        case ASSISTANT -> 0;
        case DEBUG -> 34;
        case USER, ERROR -> 24;
      };
    }

    @Override
    public Dimension getPreferredSize() {
      final int width = contentWidth();
      final var insets = getInsets();
      return new Dimension(width, cardHeight(cardWidth(width)) + insets.top + insets.bottom);
    }

    @Override
    public Dimension getMaximumSize() {
      return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    public Dimension getMinimumSize() {
      return new Dimension(0, getPreferredSize().height);
    }

    @Override
    public void doLayout() {
      final var insets = getInsets();
      final int width = cardWidth(getWidth());
      final int x = message.kind() == Kind.USER ? getWidth() - insets.right - width : insets.left;
      final int height = cardHeight(width);
      card.setBounds(x, insets.top, width, height);
      if (content != null) {
        text.setBounds(0, 0, 0, 0);
        if (disclosure != null) disclosure.setBounds(0, 0, 0, 0);
        if (title != null) title.setBounds(0, 0, 0, 0);
        content.setBounds(0, 0, width, height);
        content.doLayout();
        return;
      }
      final int headingHeight = titleHeight();
      if (disclosure != null) {
        disclosure.setBounds(0, 0, width, headingHeight);
        if (!disclosure.isSelected()) {
          text.setBounds(0, 0, 0, 0);
          return;
        }
      } else if (title != null) {
        title.setBounds(14, 12, width - 28, Math.max(0, headingHeight - 6));
      }
      final int top =
          message.kind() == Kind.DEBUG
              ? headingHeight + 22
              : message.kind() == Kind.ASSISTANT ? 0 : 12 + headingHeight;
      final int bottom = message.kind() == Kind.ASSISTANT ? 0 : 12;
      text.setBounds(
          horizontalPadding(),
          top,
          Math.max(1, width - horizontalPadding() * 2),
          Math.max(0, height - top - bottom));
    }
  }

  private static final class DisclosureIcon implements Icon {
    @Override
    public int getIconWidth() {
      return 12;
    }

    @Override
    public int getIconHeight() {
      return 12;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
      graphics.setColor(component.getForeground());
      if (component instanceof AbstractButton button && button.isSelected()) {
        graphics.fillPolygon(new int[] {x + 2, x + 10, x + 6}, new int[] {y + 4, y + 4, y + 8}, 3);
      } else {
        graphics.fillPolygon(new int[] {x + 4, x + 8, x + 4}, new int[] {y + 2, y + 6, y + 10}, 3);
      }
    }
  }

  private static final class Card extends JPanel {
    private static final long serialVersionUID = 1L;
    private final Kind kind;

    Card(Kind kind) {
      super(null);
      this.kind = kind;
      setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      if (!(getParent() instanceof MessageRow row)
          || row.content != null
          || kind == Kind.ASSISTANT
          || (kind == Kind.DEBUG && !row.disclosure.isSelected())) {
        super.paintComponent(graphics);
        return;
      }
      final var fill =
          switch (kind) {
            case USER -> AiTheme.userBackground();
            case DEBUG -> AiTheme.surface();
            case ERROR -> AiTheme.errorBackground();
            case ASSISTANT -> AiTheme.background();
          };
      final int top = kind == Kind.DEBUG ? row.titleHeight() + 10 : 0;
      final var canvas = (Graphics2D) graphics.create();
      try {
        canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        canvas.setColor(fill);
        canvas.fillRoundRect(0, top, getWidth() - 1, getHeight() - top - 1, 24, 24);
      } finally {
        canvas.dispose();
      }
      super.paintComponent(graphics);
    }
  }
}
