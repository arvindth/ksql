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

package io.confluent.ksql.function.udaf.sum;

import io.confluent.ksql.execution.function.TableAggregationFunction;
import io.confluent.ksql.function.BaseAggregateFunction;
import java.util.Collections;
import java.util.function.Function;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.streams.kstream.Merger;

public class IntegerSumKudaf
    extends BaseAggregateFunction<Integer, Integer, Integer>
    implements TableAggregationFunction<Integer, Integer, Integer> {

  IntegerSumKudaf(final String functionName, final int argIndexInValue) {
    super(functionName,
          argIndexInValue, () -> 0,
          Schema.OPTIONAL_INT32_SCHEMA, Schema.OPTIONAL_INT32_SCHEMA,
          Collections.singletonList(Schema.OPTIONAL_INT32_SCHEMA), "Computes the sum for a key."
    );
  }

  @Override
  public Integer aggregate(final Integer valueToAdd, final Integer aggregateValue) {
    if (valueToAdd == null) {
      return aggregateValue;
    }
    return aggregateValue + valueToAdd;
  }

  @Override
  public Integer undo(final Integer valueToUndo, final Integer aggregateValue) {
    if (valueToUndo == null) {
      return aggregateValue;
    }
    return aggregateValue - valueToUndo;
  }

  @Override
  public Merger<Struct, Integer> getMerger() {
    return (aggKey, aggOne, aggTwo) -> aggOne + aggTwo;
  }

  @Override
  public Function<Integer, Integer> getResultMapper() {
    return Function.identity();
  }

}