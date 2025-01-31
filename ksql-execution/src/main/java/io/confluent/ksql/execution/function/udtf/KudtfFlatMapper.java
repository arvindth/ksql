/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.execution.function.udtf;

import com.google.errorprone.annotations.Immutable;
import io.confluent.ksql.GenericRow;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import org.apache.kafka.streams.kstream.ValueMapper;

/**
 * Implements the actual flat-mapping logic - this is called by Kafka Streams
 */
@Immutable
public class KudtfFlatMapper implements ValueMapper<GenericRow, Iterable<GenericRow>> {

  private final List<TableFunctionApplier> tableFunctionAppliers;

  public KudtfFlatMapper(List<TableFunctionApplier> tableFunctionAppliers) {
    this.tableFunctionAppliers = Objects.requireNonNull(tableFunctionAppliers);
  }

  /*
  This function zips results from multiple table functions together as described in KLIP-9
  in the design-proposals directory.
   */
  @Override
  public Iterable<GenericRow> apply(GenericRow row) {
    List<Iterator<?>> iters = new ArrayList<>(tableFunctionAppliers.size());
    int maxLength = 0;
    for (TableFunctionApplier applier : tableFunctionAppliers) {
      List<?> exploded = applier.apply(row);
      iters.add(exploded.iterator());
      maxLength = Math.max(maxLength, exploded.size());
    }
    List<GenericRow> rows = new ArrayList<>(maxLength);
    for (int i = 0; i < maxLength; i++) {
      List<Object> newRow = new ArrayList<>(row.getColumns());
      for (Iterator<?> iter : iters) {
        if (iter.hasNext()) {
          newRow.add(iter.next());
        } else {
          newRow.add(null);
        }
      }
      rows.add(new GenericRow(newRow));
    }
    return rows;
  }
}
