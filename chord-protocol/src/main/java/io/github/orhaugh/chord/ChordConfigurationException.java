/*
 * Copyright 2026 Ross Haugh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.orhaugh.chord;

/**
 * The client was configured incorrectly: contradictory options, values outside permitted ranges, or
 * an insecure combination that requires an explicit opt in.
 *
 * <p>This exception is always thrown before any bytes reach the network.
 */
public class ChordConfigurationException extends ChordException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates a configuration exception.
   *
   * @param message description of the configuration problem and how to fix it
   */
  public ChordConfigurationException(String message) {
    super(message);
  }
}
