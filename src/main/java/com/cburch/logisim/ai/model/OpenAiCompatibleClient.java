/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.model;

import com.cburch.logisim.ai.util.SimpleJson;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal OpenAI-compatible Chat Completions transport with function/tool calling. */
public final class OpenAiCompatibleClient implements ModelClient {
  private final HttpClient httpClient;

  public OpenAiCompatibleClient() {
    this(HttpClient.newBuilder().build());
  }

  OpenAiCompatibleClient(HttpClient httpClient) {
    this.httpClient = httpClient;
  }

  @Override
  public ModelTurn complete(
      ModelConfig config,
      List<Map<String, Object>> messages,
      List<Map<String, Object>> toolDefinitions)
      throws Exception {
    final var body = new LinkedHashMap<String, Object>();
    body.put("model", config.model());
    body.put("messages", messages);
    if (toolDefinitions != null && !toolDefinitions.isEmpty()) {
      body.put("tools", toolDefinitions);
      body.put("tool_choice", "auto");
    }
    final var builder =
        HttpRequest.newBuilder(config.endpoint())
            .timeout(config.timeout())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(SimpleJson.stringify(body)));
    if (!config.apiKey().isBlank()) builder.header("Authorization", "Bearer " + config.apiKey());
    final var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      // Provider error bodies may echo credentials. Do not expose them to logs or reports.
      throw new IllegalStateException("Model API returned HTTP " + response.statusCode());
    }
    final var root = SimpleJson.parseObject(response.body());
    final var choices = list(root.get("choices"), "choices");
    if (choices.isEmpty()) throw new IllegalStateException("Model API returned no choices");
    final var choice = object(choices.get(0), "choice");
    final var message = object(choice.get("message"), "message");
    final var content = message.get("content") instanceof String s ? s : "";
    final var calls = new ArrayList<ModelToolCall>();
    if (message.get("tool_calls") instanceof List<?> toolCalls) {
      for (final var item : toolCalls) {
        final var call = object(item, "tool_call");
        final var function = object(call.get("function"), "tool_call.function");
        final var id = string(call.get("id"), "tool_call.id");
        final var name = string(function.get("name"), "tool_call.function.name");
        final var argumentsText = function.get("arguments") instanceof String s ? s : "{}";
        calls.add(new ModelToolCall(id, name, SimpleJson.parseObject(argumentsText)));
      }
    }
    final var assistantMessage = new LinkedHashMap<String, Object>();
    assistantMessage.put("role", "assistant");
    assistantMessage.put("content", content);
    if (message.get("tool_calls") != null)
      assistantMessage.put("tool_calls", message.get("tool_calls"));
    final var finishReason =
        choice.get("finish_reason") == null ? "" : String.valueOf(choice.get("finish_reason"));
    return new ModelTurn(content, List.copyOf(calls), finishReason, assistantMessage);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value, String name) {
    if (!(value instanceof Map<?, ?> map))
      throw new IllegalStateException("Expected JSON object: " + name);
    final var result = new LinkedHashMap<String, Object>();
    for (final var entry : map.entrySet())
      result.put(String.valueOf(entry.getKey()), entry.getValue());
    return result;
  }

  private static List<?> list(Object value, String name) {
    if (!(value instanceof List<?> list))
      throw new IllegalStateException("Expected JSON array: " + name);
    return list;
  }

  private static String string(Object value, String name) {
    if (!(value instanceof String s) || s.isBlank())
      throw new IllegalStateException("Expected JSON string: " + name);
    return s;
  }
}
