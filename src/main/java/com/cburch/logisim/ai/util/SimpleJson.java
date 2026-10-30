/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small dependency-free JSON reader/writer used by the optional AI integration. */
public final class SimpleJson {
  private SimpleJson() {}

  public static Object parse(String text) {
    if (text == null) throw new IllegalArgumentException("JSON text must not be null");
    final var parser = new Parser(text);
    final var value = parser.readValue();
    parser.skipWhitespace();
    if (!parser.atEnd()) throw parser.error("Unexpected trailing characters");
    return value;
  }

  public static Map<String, Object> parseObject(String text) {
    final var value = parse(text);
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("Expected a JSON object");
    }
    final var result = new LinkedHashMap<String, Object>();
    for (final var entry : map.entrySet()) {
      result.put(String.valueOf(entry.getKey()), entry.getValue());
    }
    return result;
  }

  public static String stringify(Object value) {
    final var out = new StringBuilder();
    writeValue(out, value);
    return out.toString();
  }

  private static void writeValue(StringBuilder out, Object value) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof String s) {
      writeString(out, s);
    } else if (value instanceof Boolean || value instanceof Number) {
      out.append(value);
    } else if (value instanceof Map<?, ?> map) {
      out.append('{');
      var first = true;
      for (final var entry : map.entrySet()) {
        if (!first) out.append(',');
        first = false;
        writeString(out, String.valueOf(entry.getKey()));
        out.append(':');
        writeValue(out, entry.getValue());
      }
      out.append('}');
    } else if (value instanceof Iterable<?> iterable) {
      out.append('[');
      var first = true;
      for (final var item : iterable) {
        if (!first) out.append(',');
        first = false;
        writeValue(out, item);
      }
      out.append(']');
    } else if (value.getClass().isArray()) {
      out.append('[');
      final var length = java.lang.reflect.Array.getLength(value);
      for (var i = 0; i < length; i++) {
        if (i > 0) out.append(',');
        writeValue(out, java.lang.reflect.Array.get(value, i));
      }
      out.append(']');
    } else {
      writeString(out, String.valueOf(value));
    }
  }

  private static void writeString(StringBuilder out, String value) {
    out.append('"');
    for (var i = 0; i < value.length(); i++) {
      final var c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
          else out.append(c);
        }
      }
    }
    out.append('"');
  }

  private static final class Parser {
    private final String text;
    private int index;

    Parser(String text) {
      this.text = text;
    }

    boolean atEnd() {
      return index >= text.length();
    }

    RuntimeException error(String message) {
      return new IllegalArgumentException(message + " at character " + index);
    }

    void skipWhitespace() {
      while (!atEnd() && Character.isWhitespace(text.charAt(index))) index++;
    }

    Object readValue() {
      skipWhitespace();
      if (atEnd()) throw error("Unexpected end of JSON");
      return switch (text.charAt(index)) {
        case '{' -> readObject();
        case '[' -> readArray();
        case '"' -> readString();
        case 't' -> readLiteral("true", Boolean.TRUE);
        case 'f' -> readLiteral("false", Boolean.FALSE);
        case 'n' -> readLiteral("null", null);
        default -> readNumber();
      };
    }

    Object readLiteral(String literal, Object value) {
      if (!text.startsWith(literal, index)) throw error("Expected " + literal);
      index += literal.length();
      return value;
    }

    Map<String, Object> readObject() {
      index++;
      final var result = new LinkedHashMap<String, Object>();
      skipWhitespace();
      if (!atEnd() && text.charAt(index) == '}') {
        index++;
        return result;
      }
      while (true) {
        skipWhitespace();
        if (atEnd() || text.charAt(index) != '"') throw error("Expected object key");
        final var key = readString();
        skipWhitespace();
        if (atEnd() || text.charAt(index) != ':') throw error("Expected ':'");
        index++;
        result.put(key, readValue());
        skipWhitespace();
        if (atEnd()) throw error("Unterminated object");
        final var c = text.charAt(index++);
        if (c == '}') return result;
        if (c != ',') throw error("Expected ',' or '}'");
      }
    }

    List<Object> readArray() {
      index++;
      final var result = new ArrayList<Object>();
      skipWhitespace();
      if (!atEnd() && text.charAt(index) == ']') {
        index++;
        return result;
      }
      while (true) {
        result.add(readValue());
        skipWhitespace();
        if (atEnd()) throw error("Unterminated array");
        final var c = text.charAt(index++);
        if (c == ']') return result;
        if (c != ',') throw error("Expected ',' or ']'");
      }
    }

    String readString() {
      if (text.charAt(index) != '"') throw error("Expected string");
      index++;
      final var out = new StringBuilder();
      while (!atEnd()) {
        final var c = text.charAt(index++);
        if (c == '"') return out.toString();
        if (c != '\\') {
          out.append(c);
          continue;
        }
        if (atEnd()) throw error("Unterminated escape sequence");
        final var escaped = text.charAt(index++);
        switch (escaped) {
          case '"', '\\', '/' -> out.append(escaped);
          case 'b' -> out.append('\b');
          case 'f' -> out.append('\f');
          case 'n' -> out.append('\n');
          case 'r' -> out.append('\r');
          case 't' -> out.append('\t');
          case 'u' -> {
            if (index + 4 > text.length()) throw error("Invalid unicode escape");
            try {
              out.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
            } catch (NumberFormatException e) {
              throw error("Invalid unicode escape");
            }
            index += 4;
          }
          default -> throw error("Invalid escape sequence");
        }
      }
      throw error("Unterminated string");
    }

    Number readNumber() {
      final var start = index;
      if (text.charAt(index) == '-') index++;
      if (atEnd()) throw error("Invalid number");
      if (text.charAt(index) == '0') {
        index++;
      } else {
        if (!Character.isDigit(text.charAt(index))) throw error("Expected JSON value");
        while (!atEnd() && Character.isDigit(text.charAt(index))) index++;
      }
      var floating = false;
      if (!atEnd() && text.charAt(index) == '.') {
        floating = true;
        index++;
        if (atEnd() || !Character.isDigit(text.charAt(index))) throw error("Invalid number");
        while (!atEnd() && Character.isDigit(text.charAt(index))) index++;
      }
      if (!atEnd() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
        floating = true;
        index++;
        if (!atEnd() && (text.charAt(index) == '+' || text.charAt(index) == '-')) index++;
        if (atEnd() || !Character.isDigit(text.charAt(index))) throw error("Invalid exponent");
        while (!atEnd() && Character.isDigit(text.charAt(index))) index++;
      }
      final var token = text.substring(start, index);
      try {
        if (floating) return Double.parseDouble(token);
        return Long.parseLong(token);
      } catch (NumberFormatException e) {
        throw error("Invalid number");
      }
    }
  }
}
