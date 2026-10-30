/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.ai;

import java.util.regex.Pattern;

/** Converts the model's lightweight Markdown into clean selectable text for the Swing transcript. */
final class AssistantTextFormatter {
  private static final Pattern FENCE = Pattern.compile("^\\s{0,3}(?:`{3,}|~{3,}).*$");
  private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s+");
  private static final Pattern CLOSING_HEADING = Pattern.compile("\\s+#{1,6}\\s*$");
  private static final Pattern QUOTE = Pattern.compile("^\\s{0,3}>\\s?");
  private static final Pattern BULLET = Pattern.compile("^(\\s*)[-+*]\\s+");
  private static final Pattern RULE =
      Pattern.compile("^\\s{0,3}([-*_])(?:\\s*\\1){2,}\\s*$");
  private static final Pattern IMAGE = Pattern.compile("!\\[([^]\\n]*)]\\([^)]*\\)");
  private static final Pattern LINK = Pattern.compile("\\[([^]\\n]+)]\\([^)]*\\)");
  private static final Pattern CODE = Pattern.compile("`([^`\\n]+)`");
  private static final Pattern STRIKE = Pattern.compile("~~(\\S(?:.*?\\S)?)~~");
  private static final Pattern STAR_EMPHASIS =
      Pattern.compile(
          "(?<![\\p{Alnum}_*])(\\*{1,3})(\\S(?:.*?\\S)?)\\1(?![\\p{Alnum}_*])");
  private static final Pattern UNDERSCORE_EMPHASIS =
      Pattern.compile(
          "(?<![\\p{Alnum}_])(_{1,3})(\\S(?:.*?\\S)?)\\1(?![\\p{Alnum}_])");

  private AssistantTextFormatter() {}

  static String toDisplayText(String markdown) {
    if (markdown == null || markdown.isEmpty()) return "";
    final var normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
    final var result = new StringBuilder(normalized.length());
    final var lines = normalized.split("\\n", -1);
    for (int index = 0; index < lines.length; index++) {
      var line = lines[index];
      if (FENCE.matcher(line).matches()) {
        if (index + 1 < lines.length
            && !result.isEmpty()
            && result.charAt(result.length() - 1) != '\n') {
          result.append('\n');
        }
        continue;
      }
      if (RULE.matcher(line).matches()) line = "";
      final var heading = HEADING.matcher(line);
      if (heading.find()) {
        line = heading.replaceFirst("");
        line = CLOSING_HEADING.matcher(line).replaceFirst("");
      }
      var quote = QUOTE.matcher(line);
      while (quote.find()) {
        line = quote.replaceFirst("");
        quote = QUOTE.matcher(line);
      }
      line = BULLET.matcher(line).replaceFirst("$1• ");
      line = IMAGE.matcher(line).replaceAll("$1");
      line = LINK.matcher(line).replaceAll("$1");
      line = CODE.matcher(line).replaceAll("$1");
      line = STRIKE.matcher(line).replaceAll("$1");
      line = STAR_EMPHASIS.matcher(line).replaceAll("$2");
      line = UNDERSCORE_EMPHASIS.matcher(line).replaceAll("$2");
      line =
          line.replace("\\`", "`")
              .replace("\\*", "*")
              .replace("\\_", "_")
              .replace("\\#", "#")
              .replace("\\[", "[")
              .replace("\\]", "]");
      result.append(line);
      if (index + 1 < lines.length) result.append('\n');
    }
    return result.toString();
  }
}
