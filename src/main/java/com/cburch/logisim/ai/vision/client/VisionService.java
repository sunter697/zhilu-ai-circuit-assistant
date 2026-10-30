/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.vision.client;

import com.cburch.logisim.ai.vision.model.VisionIr;
import java.nio.file.Path;

/** Model-independent boundary between Logisim and the local recognition process. */
public interface VisionService {
  record Health(
      String status,
      String protocolVersion,
      String serviceVersion,
      String modelVersion,
      String detectorMode) {}

  Health health();

  VisionIr recognize(Path image);
}
