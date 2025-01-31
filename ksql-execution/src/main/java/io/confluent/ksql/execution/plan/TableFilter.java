/*
 * Copyright 2019 Confluent Inc.
 *
 * Licensed under the Confluent Community License; you may not use this file
 * except in compliance with the License.  You may obtain a copy of the License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.execution.plan;

import com.google.errorprone.annotations.Immutable;
import io.confluent.ksql.execution.expression.tree.Expression;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Immutable
public class TableFilter<K> implements ExecutionStep<KTableHolder<K>> {
  private final ExecutionStepProperties properties;
  private final ExecutionStep<KTableHolder<K>> source;
  private final Expression filterExpression;

  public TableFilter(
      final ExecutionStepProperties properties,
      final ExecutionStep<KTableHolder<K>> source,
      final Expression filterExpression
  ) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.source = Objects.requireNonNull(source, "source");
    this.filterExpression = Objects.requireNonNull(filterExpression, "filterExpression");
  }

  @Override
  public ExecutionStepProperties getProperties() {
    return properties;
  }

  @Override
  public List<ExecutionStep<?>> getSources() {
    return Collections.singletonList(source);
  }

  public Expression getFilterExpression() {
    return filterExpression;
  }

  public ExecutionStep<KTableHolder<K>> getSource() {
    return source;
  }

  @Override
  public KTableHolder<K> build(final PlanBuilder builder) {
    return builder.visitTableFilter(this);
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final TableFilter<?> that = (TableFilter<?>) o;
    return Objects.equals(properties, that.properties)
        && Objects.equals(source, that.source)
        && Objects.equals(filterExpression, that.filterExpression);
  }

  @Override
  public int hashCode() {

    return Objects.hash(properties, source, filterExpression);
  }
}
