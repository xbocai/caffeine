/*
 * Copyright 2021 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.parser.cloud_physics;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import com.github.benmanes.caffeine.cache.simulator.parser.TraceWriter;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.google.common.hash.Hashing;

/**
 * A writer for the trace format used by the authors of the LIRS2 algorithm.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CloudPhysicsTraceWriter implements TraceWriter {
  private final DataOutputStream writer;

  public CloudPhysicsTraceWriter(OutputStream output) {
    this.writer = new DataOutputStream(output);
  }

  @Override
  public void writeEvent(AccessEvent event) throws IOException {
    int key = Hashing.murmur3_128().hashLong(event.key()).asInt();
    writer.writeInt(key);
  }

  @Override
  public void close() throws IOException {
    writer.close();
  }
}
