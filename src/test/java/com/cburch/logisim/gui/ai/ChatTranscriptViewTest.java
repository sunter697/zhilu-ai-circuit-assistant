/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.ai.util.SwingExecutor;
import com.cburch.logisim.gui.ai.ChatTranscriptView.Kind;
import com.cburch.logisim.gui.ai.ChatTranscriptView.Message;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import org.junit.jupiter.api.Test;

class ChatTranscriptViewTest {
  @Test
  void debugStartsFoldedAndCanBeOpenedAndClosedWithoutLosingRawTextOrSelection() throws Exception {
    SwingExecutor.run(
        () -> {
          final var view = new ChatTranscriptView();
          final var raw = "{ok=true, data={wireCount=3}}\n<raw debug is plain text>";
          view.setMessages(List.of(new Message(Kind.DEBUG, "Tool connect", raw)));
          layoutAtWidth(view, 360);
          final var toggle = disclosure(view);
          final var text = messageText(view);
          final int foldedHeight = view.getPreferredSize().height;
          assertFalse(toggle.isSelected());
          assertFalse(text.isVisible());
          assertFalse(text.isEditable());
          assertEquals("Tool connect:\n" + raw, view.plainText());

          toggle.doClick(0);
          layoutAtWidth(view, 360);
          assertTrue(toggle.isSelected());
          assertTrue(text.isVisible());
          assertTrue(view.getPreferredSize().height > foldedHeight);
          text.select(1, 8);
          final var selection = text.getSelectedText();
          toggle.doClick(0);
          layoutAtWidth(view, 360);
          assertFalse(text.isVisible());
          assertEquals(foldedHeight, view.getPreferredSize().height);
          assertEquals("Tool connect:\n" + raw, view.plainText());
          toggle.doClick(0);
          assertEquals(selection, text.getSelectedText());
        });
  }

  @Test
  void userAssistantAndErrorsRemainVisibleWithoutDisclosure() throws Exception {
    SwingExecutor.run(
        () -> {
          final var view = new ChatTranscriptView();
          view.setMessages(
              List.of(
                  new Message(Kind.USER, "", "Create an XOR gate"),
                  new Message(Kind.ASSISTANT, "", "Please confirm the inputs and output."),
                  new Message(Kind.ERROR, "Error", "Verification failed: unknown output")));
          layoutAtWidth(view, 340);
          assertTrue(descendants(view).stream().noneMatch(c -> c instanceof JToggleButton));
          assertTrue(
              descendants(view).stream()
                  .filter(c -> c instanceof JTextArea)
                  .allMatch(Component::isVisible));
          assertTrue(view.plainText().contains("Error:\nVerification failed: unknown output"));
        });
  }

  @Test
  void appendAndLocalizedRefreshKeepExpandedCardAndSelectionUsingStableIdentity() throws Exception {
    SwingExecutor.run(
        () -> {
          final var view = new ChatTranscriptView();
          final var first = new Message(Kind.DEBUG, "Tool connect", "raw response text", "entry-1");
          view.setMessages(List.of(first));
          final var originalRow = view.getComponent(0);
          final var toggle = disclosure(view);
          final var text = messageText(view);
          toggle.doClick(0);
          text.select(4, 12);

          final var next = new Message(Kind.DEBUG, "Diff", "second raw response", "entry-2");
          view.setMessages(List.of(first, next));
          layoutAtWidth(view, 340);
          assertSame(originalRow, view.getComponent(0));
          assertSame(text, messageText(view));
          assertEquals("response", text.getSelectedText());
          assertTrue(toggle.isSelected());
          assertFalse(disclosure((Container) view.getComponent(1)).isSelected());

          view.setMessages(
              List.of(
                  new Message(Kind.DEBUG, "工具 connect", first.text(), "entry-1"),
                  new Message(Kind.DEBUG, "差异", next.text(), "entry-2")));
          assertSame(originalRow, view.getComponent(0));
          assertSame(toggle, disclosure(view));
          assertEquals("工具 connect", toggle.getText());
          assertEquals("工具 connect", toggle.getAccessibleContext().getAccessibleName());
          assertEquals("response", text.getSelectedText());
          assertTrue(toggle.isSelected());
          assertFalse(disclosure((Container) view.getComponent(1)).isSelected());

          view.setMessages(
              List.of(
                  new Message(Kind.DEBUG, "工具 connect", "updated raw response text", "entry-1"),
                  new Message(Kind.DEBUG, "差异", next.text(), "entry-2")));
          assertSame(text, messageText(view));
          assertEquals(4, text.getSelectionStart());
          assertEquals(12, text.getSelectionEnd());
          assertTrue(toggle.isSelected());
          assertTrue(view.plainText().contains("updated raw response text"));
        });
  }

  @Test
  void disclosureSupportsSpaceAndEnterKeyboardActions() throws Exception {
    SwingExecutor.run(
        () -> {
          final var view = new ChatTranscriptView();
          view.setMessages(List.of(new Message(Kind.DEBUG, "Tool connect", "raw body")));
          final var toggle = disclosure(view);
          invokeKeyAction(toggle, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
          assertTrue(toggle.isSelected());
          invokeKeyAction(toggle, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
          assertFalse(toggle.isSelected());
          invokeKeyAction(toggle, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, false));
          invokeKeyAction(toggle, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, true));
          assertTrue(toggle.isSelected());
          assertTrue(messageText(view).isVisible());
        });
  }

  @Test
  void longTitleAndExpandedBodyStayWithinNarrowCardsAcrossRepeatedResizing() throws Exception {
    SwingExecutor.run(
        () -> {
          final var view = new ChatTranscriptView();
          final var title =
              "工具 very_long_component_action_name_and_candidate_identifier ".repeat(6);
          final var raw =
              "A long selectable response that wraps across the sidebar width. ".repeat(20);
          view.setMessages(List.of(new Message(Kind.DEBUG, title, raw, "entry-1")));
          layoutAtWidth(view, 340);
          final var toggle = disclosure(view);
          assertEquals(title, toggle.getToolTipText());
          assertContainedBounds(view);
          final int foldedHeight = view.getPreferredSize().height;

          toggle.doClick(0);
          layoutAtWidth(view, 860);
          final int wideHeight = view.getPreferredSize().height;
          final var text = messageText(view);
          text.select(7, 17);
          final var selection = text.getSelectedText();
          layoutAtWidth(view, 340);
          final int narrowHeight = view.getPreferredSize().height;
          assertTrue(narrowHeight > wideHeight);
          assertContainedBounds(view);
          layoutAtWidth(view, 860);
          assertEquals(wideHeight, view.getPreferredSize().height);
          assertContainedBounds(view);
          assertEquals(selection, text.getSelectedText());
          toggle.doClick(0);
          layoutAtWidth(view, 340);
          assertEquals(foldedHeight, view.getPreferredSize().height);
          assertContainedBounds(view);
        });
  }

  @Test
  void structuredContentUsesAvailableWidthAndKeepsItsRawTextAndIdentity() throws Exception {
    SwingExecutor.run(
        () -> {
          final var view = new ChatTranscriptView();
          final var content =
              new JPanel() {
                private static final long serialVersionUID = 1L;

                @Override
                public Dimension getPreferredSize() {
                  return new Dimension(getWidth(), getWidth() < 400 ? 180 : 80);
                }
              };
          final var raw = "Inputs: A, B\nOutput: Y\nY = ~(A XOR B)";
          view.setMessages(List.of(new Message(Kind.ASSISTANT, "", raw, "spec-1", content)));
          layoutAtWidth(view, 340);
          final var row = view.getComponent(0);
          final int narrowHeight = view.getPreferredSize().height;
          assertEquals("message-specification", row.getName());
          assertEquals(180, content.getHeight());
          assertEquals(304, content.getWidth());
          assertFalse(messageText(view).isVisible());
          assertEquals(raw, view.plainText());
          assertContainedBounds(view);

          view.setMessages(
              List.of(
                  new Message(Kind.ASSISTANT, "", raw, "spec-1", content),
                  new Message(Kind.DEBUG, "Tool verify_specification", "PASS", "tool-2")));
          assertSame(row, view.getComponent(0));
          layoutAtWidth(view, 860);
          assertEquals(80, content.getHeight());
          assertEquals(824, content.getWidth());
          assertContainedBounds(view);
          view.setMessages(List.of(new Message(Kind.ASSISTANT, "", raw, "spec-1", content)));
          layoutAtWidth(view, 340);
          assertEquals(narrowHeight, view.getPreferredSize().height);
          assertSame(row, view.getComponent(0));
          assertContainedBounds(view);
        });
  }

  private static void invokeKeyAction(JComponent component, KeyStroke key) {
    final var name = component.getInputMap().get(key);
    assertTrue(name != null, "Keyboard binding missing: " + key);
    final var action = component.getActionMap().get(name);
    assertTrue(action != null, "Keyboard action missing: " + name);
    action.actionPerformed(
        new ActionEvent(component, ActionEvent.ACTION_PERFORMED, name.toString()));
  }

  private static JToggleButton disclosure(Container container) {
    return (JToggleButton)
        descendants(container).stream()
            .filter(c -> "messageDisclosure".equals(c.getName()))
            .findFirst()
            .orElseThrow();
  }

  private static JTextArea messageText(Container container) {
    return (JTextArea)
        descendants(container).stream()
            .filter(c -> "messageText".equals(c.getName()))
            .findFirst()
            .orElseThrow();
  }

  private static List<Component> descendants(Container container) {
    final var result = new ArrayList<Component>();
    for (final var component : container.getComponents()) {
      result.add(component);
      if (component instanceof Container child) result.addAll(descendants(child));
    }
    return result;
  }

  private static void layoutAtWidth(ChatTranscriptView view, int width) {
    view.setSize(width, 1);
    view.setSize(width, view.getPreferredSize().height);
    layoutChildren(view);
  }

  private static void layoutChildren(Container container) {
    container.doLayout();
    for (final var component : container.getComponents()) {
      if (component instanceof Container child) layoutChildren(child);
    }
  }

  @Test
  void assistantMarkdownIsDisplayedAsCleanTextWhileRawTextIsRetained() throws Exception {
    SwingExecutor.run(
        () -> {
          final var view = new ChatTranscriptView();
          final var raw =
              "## 修改完成（方案 A）\n\n"
                  + "已把最后一个门替换为**与非门**。\n\n"
                  + "- 当前逻辑：`O1 = ~(A*B)`\n"
                  + "* **验证结果**：通过";
          view.setMessages(List.of(new Message(Kind.ASSISTANT, "", raw)));

          assertEquals(
              "修改完成（方案 A）\n\n"
                  + "已把最后一个门替换为与非门。\n\n"
                  + "• 当前逻辑：O1 = ~(A*B)\n"
                  + "• 验证结果：通过",
              messageText(view).getText());
          assertEquals(raw, view.plainText());
        });
  }

  private static void assertContainedBounds(Container container) {
    for (final var component : container.getComponents()) {
      assertTrue(component.getX() >= 0);
      assertTrue(component.getY() >= 0);
      assertTrue(component.getX() + component.getWidth() <= container.getWidth());
      assertTrue(component.getY() + component.getHeight() <= container.getHeight());
      if (component instanceof Container child) assertContainedBounds(child);
    }
  }
}
