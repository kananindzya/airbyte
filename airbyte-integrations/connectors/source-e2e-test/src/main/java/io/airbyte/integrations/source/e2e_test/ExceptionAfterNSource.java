/*
 * MIT License
 *
 * Copyright (c) 2020 Airbyte
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.airbyte.integrations.source.e2e_test;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableMap;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.util.AutoCloseableIterator;
import io.airbyte.commons.util.AutoCloseableIterators;
import io.airbyte.integrations.BaseConnector;
import io.airbyte.integrations.base.Source;
import io.airbyte.protocol.models.AirbyteCatalog;
import io.airbyte.protocol.models.AirbyteConnectionStatus;
import io.airbyte.protocol.models.AirbyteConnectionStatus.Status;
import io.airbyte.protocol.models.AirbyteMessage;
import io.airbyte.protocol.models.AirbyteMessage.Type;
import io.airbyte.protocol.models.AirbyteRecordMessage;
import io.airbyte.protocol.models.AirbyteStateMessage;
import io.airbyte.protocol.models.CatalogHelpers;
import io.airbyte.protocol.models.ConfiguredAirbyteCatalog;
import io.airbyte.protocol.models.Field;
import io.airbyte.protocol.models.Field.JsonSchemaPrimitive;
import io.airbyte.protocol.models.SyncMode;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Throws an exception after it emits N record messages where N == throw_after_n_records. Ever 5th
 * message emitted is a state message. State messages do NOT count against N.
 */
public class ExceptionAfterNSource extends BaseConnector implements Source {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExceptionAfterNSource.class);

  private static final String STREAM_NAME = "data";
  private static final String COLUMN_NAME = "column1";
  static final AirbyteCatalog CATALOG = CatalogHelpers.createAirbyteCatalog(
      STREAM_NAME,
      Field.of(COLUMN_NAME, JsonSchemaPrimitive.STRING));
  static {
    CATALOG.getStreams().get(0).setSupportedSyncModes(List.of(SyncMode.FULL_REFRESH, SyncMode.INCREMENTAL));
    CATALOG.getStreams().get(0).setSourceDefinedCursor(true);
  }

  @Override
  public AirbyteConnectionStatus check(final JsonNode config) {
    return new AirbyteConnectionStatus().withStatus(Status.SUCCEEDED);
  }

  @Override
  public AirbyteCatalog discover(JsonNode config) {
    return Jsons.clone(CATALOG);
  }

  @Override
  public AutoCloseableIterator<AirbyteMessage> read(JsonNode config, ConfiguredAirbyteCatalog catalog, JsonNode state) {
    final long throwAfterNRecords = config.get("throw_after_n_records").asLong();

    final AtomicLong recordsEmitted = new AtomicLong();
    final AtomicLong recordValue;
    if (state != null && state.has(COLUMN_NAME)) {
      LOGGER.info("Found state: {}", state);
      recordValue = new AtomicLong(state.get(COLUMN_NAME).asLong());
    } else {
      LOGGER.info("No state found.");
      recordValue = new AtomicLong();
    }

    final AtomicBoolean hasEmittedStateAtCount = new AtomicBoolean();
    return AutoCloseableIterators.fromIterator(new AbstractIterator<>() {

      @Override
      protected AirbyteMessage computeNext() {
        if (recordsEmitted.get() % 5 == 0 && !hasEmittedStateAtCount.get()) {

          LOGGER.info("{}: emitting state record with value {}", ExceptionAfterNSource.class, recordValue.get());

          hasEmittedStateAtCount.set(true);
          return new AirbyteMessage()
              .withType(Type.STATE)
              .withState(new AirbyteStateMessage().withData(Jsons.jsonNode(ImmutableMap.of(COLUMN_NAME, recordValue.get()))));
        } else if (throwAfterNRecords > recordsEmitted.get()) {
          recordsEmitted.incrementAndGet();
          recordValue.incrementAndGet();
          hasEmittedStateAtCount.set(false);

          LOGGER.info("{} ExceptionAfterNSource: emitting record with value {}. record {} in sync.",
              ExceptionAfterNSource.class, recordValue.get(), recordsEmitted.get());

          return new AirbyteMessage()
              .withType(Type.RECORD)
              .withRecord(new AirbyteRecordMessage()
                  .withStream(STREAM_NAME)
                  .withEmittedAt(Instant.now().toEpochMilli())
                  .withData(Jsons.jsonNode(ImmutableMap.of(COLUMN_NAME, recordValue.get()))));
        } else {
          throw new IllegalStateException("Scheduled exceptional event.");
        }
      }

    });
  }

}
