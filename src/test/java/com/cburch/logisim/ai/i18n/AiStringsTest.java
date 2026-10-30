/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.i18n;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.ai.agent.AgentState;
import com.cburch.logisim.ai.util.SwingExecutor;
import com.cburch.logisim.ai.validation.TaskSpec;
import com.cburch.logisim.ai.validation.ValidationStatus;
import com.cburch.logisim.util.LocaleManager;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiStringsTest {
  private Locale original;

  @BeforeEach
  void saveLocale() {
    original = LocaleManager.getLocale();
  }

  @AfterEach
  void restoreLocale() {
    setLocale(original);
  }

  private static void setLocale(Locale locale) {
    SwingExecutor.run(() -> LocaleManager.setLocale(locale));
  }

  private static Properties bundle(String language) throws Exception {
    final var file = "ai" + (language.equals("en") ? "" : "_" + language) + ".properties";
    final var stream =
        AiStringsTest.class.getResourceAsStream("/resources/logisim/strings/ai/" + file);
    assertNotNull(stream, file);
    final var properties = new Properties();
    try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    return properties;
  }

  @Test
  void everySoftwareLocaleHasCompleteKeysAndCompatibleFormatArguments() throws Exception {
    final var base = bundle("en");
    final var locales = com.cburch.logisim.util.Strings.S.getLocaleOptions();
    assertEquals(13, locales.length);
    final var placeholder = Pattern.compile("%(?:\\d+\\$)?[sdf]");
    for (var locale : locales) {
      final var translated = bundle(locale.getLanguage());
      assertEquals(
          base.stringPropertyNames(), translated.stringPropertyNames(), locale.toLanguageTag());
      setLocale(locale);
      for (var key : base.stringPropertyNames()) {
        final var expectedTypes =
            placeholder
                .matcher(base.getProperty(key))
                .results()
                .map(m -> m.group().substring(m.group().length() - 1))
                .toList();
        final var actualTypes =
            placeholder
                .matcher(translated.getProperty(key))
                .results()
                .map(m -> m.group().substring(m.group().length() - 1))
                .toList();
        assertEquals(expectedTypes, actualTypes, locale + ": " + key);
        final var args =
            expectedTypes.stream()
                .map(t -> t.equals("d") ? 2 : t.equals("f") ? 2.0 : "A")
                .toArray();
        assertDoesNotThrow(() -> AiStrings.S.get(key, args), locale + ": " + key);
      }
      for (var state : AgentState.values())
        assertNotEquals("aiState_" + state, AiStrings.state(state));
      for (var status : ValidationStatus.values())
        assertNotEquals("aiValidation_" + status, AiStrings.validationStatus(status));
    }
  }

  @Test
  void realVerificationAndApplyMessagesUseTheCurrentUiLanguage() {
    setLocale(Locale.CHINESE);
    assertEquals(
        "全部 256 组输入均符合已确认规格。",
        AiStrings.displayMessage("All 256 exhaustive rows matched the confirmed specification."));
    assertTrue(
        AiStrings.displayMessage("Edit permission has been revoked for this session.")
            .contains("编辑权限"));
    assertTrue(AiStrings.displayMessage("Model API returned HTTP 401").contains("HTTP 401"));
    assertEquals("通过", AiStrings.validationStatus(ValidationStatus.PASS));
    setLocale(Locale.ENGLISH);
    assertTrue(
        AiStrings.displayMessage("All 256 exhaustive rows matched the confirmed specification.")
            .startsWith("All 256"));
  }

  @Test
  void alreadyRenderedDetailsAndNestedReasonsRefreshAfterSwitchingLocale() {
    setLocale(Locale.ENGLISH);
    final var detail = AiStrings.S.get("aiControllerDecisionRound", 3, 20);
    final var error =
        AiStrings.S.get(
            "aiControllerExecutionFailed", AiStrings.displayMessage("Task time budget exhausted"));
    setLocale(Locale.CHINESE);
    assertTrue(AiStrings.displayMessage(detail).contains("3/20"));
    assertFalse(AiStrings.displayMessage(detail).contains("Model decision"));
    assertTrue(AiStrings.displayMessage(error).contains("任务执行时间预算已耗尽"));
    assertFalse(AiStrings.displayMessage(error).contains("Task execution"));
  }

  @Test
  void confirmationTextCanRefreshWithoutChangingContractJsonOrDigest() {
    final var spec =
        TaskSpec.fromArguments(
            Map.of(
                "kind",
                "UNSIGNED_MULTIPLICATION",
                "inputs",
                List.of(Map.of("name", "A", "width", 4), Map.of("name", "B", "width", 4)),
                "outputs",
                List.of(Map.of("name", "P", "width", 8))));
    final var digest = spec.digest();
    final var contract = spec.toMap();
    setLocale(Locale.ENGLISH);
    final var originalText =
        AiStrings.compose(
            spec.describe(),
            AiStrings.S.get("aiControllerSpecConfirmHelp"),
            AiStrings.S.get(
                "aiControllerPermissionStatus", AiStrings.S.get("aiControllerPermissionOn")));
    setLocale(Locale.CHINESE);
    final var translated = AiStrings.displayMessage(originalText);
    assertTrue(translated.startsWith("编辑前，请确认以下功能规格："));
    assertTrue(translated.contains("已开启"));
    assertTrue(translated.contains("乘积 = A × B（无符号）"));
    assertTrue(translated.contains("P = 乘积位 [7:0]"));
    assertTrue(translated.contains("穷举全部 256 组"));
    assertFalse(translated.contains("\"kind\""));
    assertEquals(digest, spec.digest());
    assertEquals(contract, spec.toMap());
    setLocale(Locale.ENGLISH);
    assertEquals(originalText, AiStrings.displayMessage(translated));
  }

  @Test
  void identifiersAndDiagnosticPayloadsStayUntranslated() {
    setLocale(Locale.ENGLISH);
    AiStrings.state(AgentState.READY);
    setLocale(Locale.CHINESE);
    assertTrue(
        AiStrings.displayMessage("Unsupported component for combinational acceptance: Ready")
            .endsWith("Ready。"));
    final var data = "{\"tool\":\"connect\",\"componentId\":\"cmp_001\",\"status\":\"PASS\"}";
    assertEquals(data, AiStrings.displayMessage(data));
    assertEquals("A ^ B", AiStrings.displayMessage("A ^ B"));
  }

  @Test
  void localeConfirmationIsExplicitAndQuestionsOrCorrectionsAreRejected() throws Exception {
    for (var locale : com.cburch.logisim.util.Strings.S.getLocaleOptions()) {
      setLocale(locale);
      final var reply = bundle(locale.getLanguage()).getProperty("aiConfirmReplies").split(";")[0];
      assertTrue(AiStrings.isConfirmationReply(reply), locale.toString());
      assertTrue(AiStrings.isConfirmationReply("confirm"), locale.toString());
      assertTrue(AiStrings.isConfirmationReply("没问题，就这样设计即可"), locale.toString());
      assertTrue(AiStrings.isConfirmationReply(reply.toUpperCase(locale)), locale.toString());
      assertFalse(AiStrings.isConfirmationReply(reply + "?"), locale.toString());
      assertFalse(
          AiStrings.isConfirmationReply(reply + " but change the output"), locale.toString());
    }
    setLocale(Locale.forLanguageTag("el"));
    assertFalse(AiStrings.isConfirmationReply("ναι;"));
    assertTrue(AiStrings.isQuestionReply("ναι;"));
    setLocale(Locale.CHINESE);
    assertFalse(AiStrings.isConfirmationReply("同意吗"));
    assertTrue(AiStrings.isConfirmationReply("没问题，就这样设计即可"));
  }
}
