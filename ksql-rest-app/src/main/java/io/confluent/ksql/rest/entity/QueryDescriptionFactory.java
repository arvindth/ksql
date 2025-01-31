/*
 * Copyright 2019 Confluent Inc.
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

package io.confluent.ksql.rest.entity;

import com.google.common.collect.ImmutableSet;
import io.confluent.ksql.name.SourceName;
import io.confluent.ksql.query.QueryId;
import io.confluent.ksql.rest.util.EntityUtil;
import io.confluent.ksql.util.PersistentQueryMetadata;
import io.confluent.ksql.util.QueryMetadata;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class QueryDescriptionFactory {

  private QueryDescriptionFactory() {
  }

  public static QueryDescription forQueryMetadata(final QueryMetadata queryMetadata) {
    if (queryMetadata instanceof PersistentQueryMetadata) {
      final PersistentQueryMetadata persistentQuery = (PersistentQueryMetadata) queryMetadata;
      return create(
          persistentQuery.getQueryId(),
          persistentQuery,
          ImmutableSet.of(persistentQuery.getSinkName()),
          false,
          Optional.of(persistentQuery.getState())
      );
    }

    return create(
        new QueryId(""),
        queryMetadata,
        Collections.emptySet(),
        true,
        Optional.empty()
    );
  }

  private static QueryDescription create(
      final QueryId id,
      final QueryMetadata queryMetadata,
      final Set<SourceName> sinks,
      final boolean valueSchemaOnly,
      final Optional<String> state
  ) {
    return new QueryDescription(
        id,
        queryMetadata.getStatementString(),
        EntityUtil.buildSourceSchemaEntity(queryMetadata.getLogicalSchema(), valueSchemaOnly),
        queryMetadata.getSourceNames().stream().map(SourceName::name).collect(Collectors.toSet()),
        sinks.stream().map(SourceName::name).collect(Collectors.toSet()),
        queryMetadata.getTopologyDescription(),
        queryMetadata.getExecutionPlan(),
        queryMetadata.getOverriddenProperties(),
        state
    );
  }
}
