/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.ai;

import com.cburch.logisim.ai.vision.model.VisionIr;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import javax.swing.JPanel;

/** Fits the recognized source image into a panel and paints its Vision IR as an overlay. */
public final class VisionOverlayView extends JPanel {
  private static final long serialVersionUID = 1L;
  private static final double LOW_CONFIDENCE_THRESHOLD = 0.75;
  private static final Color COMPONENT_COLOR = new Color(35, 196, 116, 230);
  private static final Color LOW_CONFIDENCE_COLOR = new Color(255, 145, 40, 240);
  private static final Color WIRE_COLOR = new Color(38, 153, 255, 210);
  private static final Color JUNCTION_COLOR = new Color(224, 62, 180, 240);
  private static final Color PORT_COLOR = new Color(255, 70, 70, 235);

  private final BufferedImage image;
  private final VisionIr recognition;
  private boolean showComponents = true;
  private boolean showWires = true;
  private boolean showJunctions = true;

  public VisionOverlayView(Path sourceImage, VisionIr recognition) throws IOException {
    if (recognition == null) throw new IllegalArgumentException("Recognition is required");
    final var imageBytes = Files.readAllBytes(sourceImage);
    verifySourceHash(imageBytes, recognition.source().sha256());
    this.image = ImageIO.read(new ByteArrayInputStream(imageBytes));
    if (image == null) throw new IOException("The selected file is not a supported image");
    if (recognition.source().width() != image.getWidth()
        || recognition.source().height() != image.getHeight()) {
      throw new IOException("The source image changed after recognition");
    }
    this.recognition = recognition;
    setOpaque(true);
    setBackground(new Color(38, 42, 50));
    setPreferredSize(preferredPreviewSize(image.getWidth(), image.getHeight()));
    setMinimumSize(new Dimension(480, 320));
  }

  public void setShowComponents(boolean showComponents) {
    this.showComponents = showComponents;
    repaint();
  }

  public void setShowWires(boolean showWires) {
    this.showWires = showWires;
    repaint();
  }

  public void setShowJunctions(boolean showJunctions) {
    this.showJunctions = showJunctions;
    repaint();
  }

  public boolean isShowingComponents() {
    return showComponents;
  }

  public boolean isShowingWires() {
    return showWires;
  }

  public boolean isShowingJunctions() {
    return showJunctions;
  }

  public Dimension sourceSize() {
    return new Dimension(image.getWidth(), image.getHeight());
  }

  @Override
  protected void paintComponent(Graphics graphics) {
    super.paintComponent(graphics);
    final var canvas = (Graphics2D) graphics.create();
    try {
      canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      canvas.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      final var transform = imageTransform();
      canvas.drawImage(image, transform, null);
      canvas.transform(transform);
      if (showWires) paintWires(canvas);
      if (showJunctions) paintJunctions(canvas);
      if (showComponents) paintComponents(canvas);
    } finally {
      canvas.dispose();
    }
  }

  private void paintWires(Graphics2D canvas) {
    final var scale = imageTransform().getScaleX();
    canvas.setStroke(
        new BasicStroke(
            (float) (3.0 / scale), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    for (final var net : recognition.nets()) {
      canvas.setColor(
          net.confidence() < LOW_CONFIDENCE_THRESHOLD ? LOW_CONFIDENCE_COLOR : WIRE_COLOR);
      for (final var segment : net.segments()) {
        canvas.drawLine(
            rounded(segment.start().x()),
            rounded(segment.start().y()),
            rounded(segment.end().x()),
            rounded(segment.end().y()));
      }
    }
  }

  private void paintJunctions(Graphics2D canvas) {
    final var scale = imageTransform().getScaleX();
    final var radius = Math.max(3.5 / scale, 2.5);
    canvas.setColor(JUNCTION_COLOR);
    for (final var net : recognition.nets()) {
      for (final var junction : net.junctions()) {
        canvas.fill(
            new Ellipse2D.Double(
                junction.x() - radius,
                junction.y() - radius,
                radius * 2,
                radius * 2));
      }
    }
  }

  private void paintComponents(Graphics2D canvas) {
    final var scale = imageTransform().getScaleX();
    canvas.setStroke(new BasicStroke((float) (2.5 / scale)));
    canvas.setFont(AiTheme.font(13).deriveFont((float) (11.0 / scale)));
    for (final var component : recognition.components()) {
      final var box = component.bodyBox();
      final var color =
          component.confidence() < LOW_CONFIDENCE_THRESHOLD
              ? LOW_CONFIDENCE_COLOR
              : COMPONENT_COLOR;
      canvas.setColor(color);
      canvas.draw(
          new Rectangle2D.Double(box.x0(), box.y0(), box.x1() - box.x0(), box.y1() - box.y0()));
      paintLabel(canvas, component, scale, color);
      canvas.setColor(PORT_COLOR);
      paintPoint(canvas, component.anchor(), scale, 3.0);
      for (final var port : component.ports()) paintPoint(canvas, port.point(), scale, 2.5);
    }
  }

  private static void paintLabel(
      Graphics2D canvas, VisionIr.DetectedComponent component, double scale, Color color) {
    final var confidence = Math.round(component.confidence() * 100);
    final var label = component.kind() + " " + confidence + "%";
    final FontMetrics metrics = canvas.getFontMetrics();
    final var padding = 3.0 / scale;
    final var x = component.bodyBox().x0();
    final var baseline =
        Math.max(metrics.getAscent() + padding, component.bodyBox().y0() - 4.0 / scale);
    final var width = metrics.stringWidth(label) + padding * 2;
    final var height = metrics.getHeight() + padding;
    canvas.setColor(new Color(20, 24, 30, 205));
    canvas.fill(new Rectangle2D.Double(x, baseline - height, width, height));
    canvas.setColor(color);
    canvas.drawString(label, (float) (x + padding), (float) baseline);
  }

  private static void paintPoint(
      Graphics2D canvas, VisionIr.Point point, double scale, double screenRadius) {
    final var radius = screenRadius / scale;
    canvas.fill(
        new Rectangle2D.Double(
            point.x() - radius, point.y() - radius, radius * 2, radius * 2));
  }

  private AffineTransform imageTransform() {
    final var padding = 12.0;
    final var availableWidth = Math.max(1.0, getWidth() - padding * 2);
    final var availableHeight = Math.max(1.0, getHeight() - padding * 2);
    final var scale =
        Math.min(availableWidth / image.getWidth(), availableHeight / image.getHeight());
    final var x = (getWidth() - image.getWidth() * scale) / 2.0;
    final var y = (getHeight() - image.getHeight() * scale) / 2.0;
    final var transform = AffineTransform.getTranslateInstance(x, y);
    transform.scale(scale, scale);
    return transform;
  }

  private static Dimension preferredPreviewSize(int width, int height) {
    final var scale = Math.min(1.0, Math.min(820.0 / width, 570.0 / height));
    return new Dimension(
        Math.max(480, (int) Math.round(width * scale) + 24),
        Math.max(320, (int) Math.round(height * scale) + 24));
  }

  private static int rounded(double value) {
    return (int) Math.round(value);
  }

  private static void verifySourceHash(byte[] imageBytes, String expected) throws IOException {
    if (expected == null || !expected.matches("(?i)[0-9a-f]{64}")) return;
    try {
      final var actual =
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(imageBytes));
      if (!actual.equalsIgnoreCase(expected)) {
        throw new IOException("The source image changed after recognition");
      }
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
