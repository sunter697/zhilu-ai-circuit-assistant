/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.vision.client;

/** Stable failure category for messages shown by the image-import UI. */
public final class VisionServiceException extends RuntimeException {
  public enum Reason {
    SERVICE_UNAVAILABLE,
    TIMEOUT,
    CANCELLED,
    INVALID_RESPONSE,
    REJECTED,
    IO_ERROR
  }

  private final Reason reason;
  private final String serviceCode;

  public VisionServiceException(Reason reason, String message) {
    this(reason, "", message, null);
  }

  public VisionServiceException(Reason reason, String message, Throwable cause) {
    this(reason, "", message, cause);
  }

  public VisionServiceException(
      Reason reason, String serviceCode, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
    this.serviceCode = serviceCode == null ? "" : serviceCode;
  }

  public Reason reason() {
    return reason;
  }

  public String serviceCode() {
    return serviceCode;
  }
}
