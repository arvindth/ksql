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

package io.confluent.ksql.structured;

import io.confluent.ksql.GenericRow;
import io.confluent.ksql.codegen.CodeGenRunner;
import io.confluent.ksql.function.FunctionRegistry;
import io.confluent.ksql.logging.processing.ProcessingLogContext;
import io.confluent.ksql.logging.processing.ProcessingLogger;
import io.confluent.ksql.metastore.SerdeFactory;
import io.confluent.ksql.parser.tree.DereferenceExpression;
import io.confluent.ksql.parser.tree.Expression;
import io.confluent.ksql.parser.tree.QualifiedNameReference;
import io.confluent.ksql.planner.plan.OutputNode;
import io.confluent.ksql.streams.StreamsFactories;
import io.confluent.ksql.streams.StreamsUtil;
import io.confluent.ksql.structured.execution.ExecutionStep;
import io.confluent.ksql.structured.execution.ExecutionStepProperties;
import io.confluent.ksql.structured.execution.JoinType;
import io.confluent.ksql.structured.execution.StreamFilter;
import io.confluent.ksql.structured.execution.StreamGroupBy;
import io.confluent.ksql.structured.execution.StreamOverwriteSchemaAndKey;
import io.confluent.ksql.structured.execution.StreamSelect;
import io.confluent.ksql.structured.execution.StreamSelectKey;
import io.confluent.ksql.structured.execution.StreamSink;
import io.confluent.ksql.structured.execution.StreamStreamJoin;
import io.confluent.ksql.structured.execution.StreamTableJoin;
import io.confluent.ksql.util.ExpressionMetadata;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.QueryLoggerUtil;
import io.confluent.ksql.util.SchemaUtil;
import io.confluent.ksql.util.SelectExpression;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.streams.kstream.JoinWindows;
import org.apache.kafka.streams.kstream.KGroupedStream;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.ValueJoiner;
import org.apache.kafka.streams.kstream.WindowedSerdes;

// CHECKSTYLE_RULES.OFF: ClassDataAbstractionCoupling
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class SchemaKStream<K> {
  // CHECKSTYLE_RULES.ON: ClassDataAbstractionCoupling

  final KStream<K, GenericRow> kstream;
  final KsqlConfig ksqlConfig;
  final FunctionRegistry functionRegistry;
  private OutputNode output;
  final SerdeFactory<K> keySerdeFactory;
  final StreamsFactories streamsFactories;
  final QueryContext queryContext;
  final ExecutionStep executionStep;

  public SchemaKStream(
      final KStream<K, GenericRow> kstream,
      final SerdeFactory<K> keySerdeFactory,
      final KsqlConfig ksqlConfig,
      final FunctionRegistry functionRegistry,
      final QueryContext queryContext,
      final ExecutionStep executionStep
  ) {
    this(
        kstream,
        keySerdeFactory,
        ksqlConfig,
        functionRegistry,
        StreamsFactories.create(ksqlConfig),
        queryContext,
        executionStep);
  }

  SchemaKStream(
      final KStream<K, GenericRow> kstream,
      final SerdeFactory<K> keySerdeFactory,
      final KsqlConfig ksqlConfig,
      final FunctionRegistry functionRegistry,
      final StreamsFactories streamsFactories,
      final QueryContext queryContext,
      final ExecutionStep executionStep
  ) {
    this.kstream = kstream;
    this.ksqlConfig = Objects.requireNonNull(ksqlConfig, "ksqlConfig");
    this.functionRegistry = functionRegistry;
    this.keySerdeFactory = Objects.requireNonNull(keySerdeFactory, "keySerdeFactory");
    this.streamsFactories = Objects.requireNonNull(streamsFactories);
    this.queryContext = Objects.requireNonNull(queryContext);
    this.executionStep = executionStep;  // todo: require non-null
  }

  public SerdeFactory<K> getKeySerdeFactory() {
    return keySerdeFactory;
  }

  public boolean hasWindowedKey() {
    final Serde<K> keySerde = keySerdeFactory.create();
    return keySerde instanceof WindowedSerdes.SessionWindowedSerde
        || keySerde instanceof WindowedSerdes.TimeWindowedSerde;
  }

  public ExecutionStep getExecutionStep() {
    return executionStep;
  }

  public Schema getSchema() {
    return executionStep.getProperties().getSchema();
  }

  public SchemaKStream into(
      final String kafkaTopicName,
      final Serde<GenericRow> topicValueSerDe,
      final Set<Integer> rowkeyIndexes,
      final QueryContext.Stacker contextStacker
  ) {
    kstream
        .mapValues(row -> {
          if (row == null) {
            return null;
          }
          final List<Object> columns = new ArrayList<>();
          for (int i = 0; i < row.getColumns().size(); i++) {
            if (!rowkeyIndexes.contains(i)) {
              columns.add(row.getColumns().get(i));
            }
          }
          return new GenericRow(columns);
        }).to(kafkaTopicName, Produced.with(keySerdeFactory.create(), topicValueSerDe));
    return new SchemaKStream<>(
        kstream,
        keySerdeFactory,
        ksqlConfig,
        functionRegistry,
        queryContext,
        new StreamSink(
            new ExecutionStepProperties(
                QueryLoggerUtil.queryLoggerName(contextStacker.getQueryContext()),
                getSchema(),
                getKeyField()
            ),
            executionStep,
            kafkaTopicName
        )
    );
  }

  @SuppressWarnings("unchecked")
  public SchemaKStream<K> filter(
      final Expression filterExpression,
      final QueryContext.Stacker contextStacker,
      final ProcessingLogContext processingLogContext) {
    final ExecutionStep step = new StreamFilter(
        new ExecutionStepProperties(
            QueryLoggerUtil.queryLoggerName(contextStacker.getQueryContext()),
            getSchema(),
            getKeyField()
        ),
        executionStep,
        filterExpression
    );
    final SqlPredicate predicate = new SqlPredicate(
        filterExpression,
        getSchema(),
        hasWindowedKey(),
        ksqlConfig,
        functionRegistry,
        processingLogContext.getLoggerFactory().getLogger(
            QueryLoggerUtil.queryLoggerName(
                contextStacker.push(step.getType().name()).getQueryContext())
        )
    );

    final KStream<K, GenericRow> filteredKStream = kstream.filter(predicate.getPredicate());
    return new SchemaKStream<>(
        filteredKStream,
        keySerdeFactory,
        ksqlConfig,
        functionRegistry,
        contextStacker.getQueryContext(),
        step
    );
  }

  public SchemaKStream<K> select(
      final List<SelectExpression> selectExpressions,
      final QueryContext.Stacker contextStacker,
      final ProcessingLogContext processingLogContext) {
    final Selection selection = new Selection(
        selectExpressions,
        processingLogContext.getLoggerFactory().getLogger(
            QueryLoggerUtil.queryLoggerName(
                contextStacker.push("PROJECT").getQueryContext()))
    );
    return new SchemaKStream<>(
        kstream.mapValues(selection.getSelectValueMapper()),
        keySerdeFactory,
        ksqlConfig,
        functionRegistry,
        contextStacker.getQueryContext(),
        new StreamSelect(
            new ExecutionStepProperties(
                QueryLoggerUtil.queryLoggerName(contextStacker.getQueryContext()),
                selection.getProjectedSchema(),
            selection.getKey()
            ),
            executionStep,
            selectExpressions
        )
    );
  }

  class Selection {
    private final Schema schema;
    private final Field key;
    private final SelectValueMapper selectValueMapper;

    Selection(
        final List<SelectExpression> selectExpressions,
        final ProcessingLogger processingLogger) {
      key = findKeyField(selectExpressions);
      final List<ExpressionMetadata> expressionEvaluators = buildExpressions(selectExpressions);
      schema = buildSchema(selectExpressions, expressionEvaluators);
      final List<String> selectFieldNames = selectExpressions.stream()
          .map(SelectExpression::getName)
          .collect(Collectors.toList());
      selectValueMapper = new SelectValueMapper(
          selectFieldNames,
          expressionEvaluators,
          processingLogger);
    }

    private Field findKeyField(final List<SelectExpression> selectExpressions) {
      if (getKeyField() == null) {
        return null;
      }
      if (getKeyField().index() == -1) {
        // The key "field" isn't an actual field in the schema
        return getKeyField();
      }
      for (int i = 0; i < selectExpressions.size(); i++) {
        final String toName = selectExpressions.get(i).getName();
        final Expression toExpression = selectExpressions.get(i).getExpression();

        /*
         * Sometimes a column reference is a DereferenceExpression, and sometimes its
         * a QualifiedNameReference. We have an issue
         * (https://github.com/confluentinc/ksql/issues/1695)
         * to track cleaning this up and using DereferenceExpression for all column references.
         * Until then, we have to check for both here.
         */
        if (toExpression instanceof DereferenceExpression) {
          final DereferenceExpression dereferenceExpression
              = (DereferenceExpression) toExpression;
          if (SchemaUtil.matchFieldName(getKeyField(), dereferenceExpression.toString())) {
            return new Field(toName, i, getKeyField().schema());
          }
        } else if (toExpression instanceof QualifiedNameReference) {
          final QualifiedNameReference qualifiedNameReference
              = (QualifiedNameReference) toExpression;
          if (SchemaUtil.matchFieldName(
              getKeyField(),
              qualifiedNameReference.getName().getSuffix())) {
            return new Field(toName, i, getKeyField().schema());
          }
        }
      }
      return null;
    }

    private Schema buildSchema(
        final List<SelectExpression> selectExpressions,
        final List<ExpressionMetadata> expressionEvaluators) {
      final SchemaBuilder schemaBuilder = SchemaBuilder.struct();
      IntStream.range(0, selectExpressions.size()).forEach(
          i -> schemaBuilder.field(
              selectExpressions.get(i).getName(),
              expressionEvaluators.get(i).getExpressionType()));
      return schemaBuilder.build();
    }

    List<ExpressionMetadata> buildExpressions(final List<SelectExpression> selectExpressions
    ) {
      final Stream<Expression> expressions = selectExpressions.stream()
          .map(SelectExpression::getExpression);

      return CodeGenRunner.compileExpressions(
          expressions, "Select", SchemaKStream.this.getSchema(), ksqlConfig, functionRegistry);
    }

    public Schema getProjectedSchema() {
      return schema;
    }

    public Field getKey() {
      return key;
    }

    SelectValueMapper getSelectValueMapper() {
      return selectValueMapper;
    }
  }

  @SuppressWarnings("unchecked")
  public SchemaKStream<K> leftJoin(
      final SchemaKTable<K> schemaKTable,
      final Schema joinSchema,
      final Field joinKey,
      final Serde<GenericRow> leftValueSerDe,
      final QueryContext.Stacker contextStacker
  ) {

    final KStream<K, GenericRow> joinedKStream =
        kstream.leftJoin(
            schemaKTable.getKtable(),
            new KsqlValueJoiner(this.getSchema(), schemaKTable.getSchema()),
            streamsFactories.getJoinedFactory().create(
                keySerdeFactory.create(),
                leftValueSerDe,
                null,
                StreamsUtil.buildOpName(contextStacker.getQueryContext()))
        );

    return new SchemaKStream(
        joinedKStream,
        keySerdeFactory,
        ksqlConfig,
        functionRegistry,
        contextStacker.getQueryContext(),
        new StreamTableJoin(
            new ExecutionStepProperties(
                QueryLoggerUtil.queryLoggerName(contextStacker.getQueryContext()),
                joinSchema,
                joinKey
            ),
            JoinType.LEFT,
            executionStep,
            schemaKTable.executionStep
        )
    );
  }

  @SuppressWarnings("unchecked")
  public SchemaKStream<K> leftJoin(
      final SchemaKStream<K> otherSchemaKStream,
      final Schema joinSchema,
      final Field joinKey,
      final JoinWindows joinWindows,
      final Serde<GenericRow> leftSerde,
      final Serde<GenericRow> rightSerde,
      final QueryContext.Stacker contextStacker) {

    final KStream<K, GenericRow> joinStream =
        kstream
            .leftJoin(
                otherSchemaKStream.kstream,
                new KsqlValueJoiner(this.getSchema(), otherSchemaKStream.getSchema()),
                joinWindows,
                streamsFactories.getJoinedFactory().create(
                    keySerdeFactory.create(),
                    leftSerde,
                    rightSerde,
                    StreamsUtil.buildOpName(contextStacker.getQueryContext()))
            );

    return new SchemaKStream<>(
        joinStream,
        keySerdeFactory,
        ksqlConfig,
        functionRegistry,
        contextStacker.getQueryContext(),
        new StreamStreamJoin(
            new ExecutionStepProperties(
                QueryLoggerUtil.queryLoggerName(contextStacker.getQueryContext()),
                joinSchema,
                joinKey
            ),
            JoinType.LEFT,
            executionStep,
            otherSchemaKStream.executionStep
        )
    );
  }

  @SuppressWarnings("unchecked")
  public SchemaKStream<K> join(
      final SchemaKTable<K> schemaKTable,
      final Schema joinSchema,
      final Field joinKey,
      final Serde<GenericRow> joinSerDe,
      final QueryContext.Stacker contextStacker
  ) {
    final KStream<K, GenericRow> joinedKStream =
        kstream.join(
            schemaKTable.getKtable(),
            new KsqlValueJoiner(this.getSchema(), schemaKTable.getSchema()),
            streamsFactories.getJoinedFactory().create(
                keySerdeFactory.create(),
                joinSerDe,
                null,
                StreamsUtil.buildOpName(contextStacker.getQueryContext()))
        );

    return new SchemaKStream<>(
        joinedKStream,
        keySerdeFactory,
        ksqlConfig,
        functionRegistry,
        contextStacker.getQueryContext(),
        new StreamTableJoin(
            new ExecutionStepProperties(
                QueryLoggerUtil.queryLoggerName(contextStacker.getQueryContext()),
                joinSchema,
                joinKey
            ),
            JoinType.INNER,
            executionStep,
            schemaKTable.executionStep
        )
    );
  }

  @SuppressWarnings("unchecked")
  public SchemaKStream<K> join(
      final SchemaKStream<K> otherSchemaKStream,
      final Schema joinSchema,
      final Field joinKey,
      final JoinWindows joinWindows,
      final Serde<GenericRow> leftSerde,
      final Serde<GenericRow> rightSerde,
      final QueryContext.Stacker contextStacker) {
    final KStream<K, GenericRow> joinStream =
        kstream
            .join(
                otherSchemaKStream.kstream,
                new KsqlValueJoiner(this.getSchema(), otherSchemaKStream.getSchema()),
                joinWindows,
                streamsFactories.getJoinedFactory().create(
                    keySerdeFactory.create(),
                    leftSerde,
                    rightSerde,
                    StreamsUtil.buildOpName(contextStacker.getQueryContext()))
            );

    return new SchemaKStream<>(
        joinStream,
        keySerdeFactory,
        ksqlConfig,
        functionRegistry,
        contextStacker.getQueryContext(),
        new StreamStreamJoin(
            new ExecutionStepProperties(
                QueryLoggerUtil.queryLoggerName(contextStacker.getQueryContext()),
                joinSchema,
                joinKey
            ),
            JoinType.INNER,
            executionStep, otherSchemaKStream.executionStep
        )
    );
  }

  public SchemaKStream<K> outerJoin(
      final SchemaKStream<K> otherSchemaKStream,
      final Schema joinSchema,
      final Field joinKey,
      final JoinWindows joinWindows,
      final Serde<GenericRow> leftSerde,
      final Serde<GenericRow> rightSerde,
      final QueryContext.Stacker contextStacker) {
    final KStream<K, GenericRow> joinStream = kstream
        .outerJoin(
            otherSchemaKStream.kstream,
            new KsqlValueJoiner(this.getSchema(), otherSchemaKStream.getSchema()),
            joinWindows,
            streamsFactories.getJoinedFactory().create(
                keySerdeFactory.create(),
                leftSerde,
                rightSerde,
                StreamsUtil.buildOpName(contextStacker.getQueryContext()))
        );

    return new SchemaKStream<>(
        joinStream,
        keySerdeFactory,
        ksqlConfig,
        functionRegistry,
        contextStacker.getQueryContext(),
        new StreamStreamJoin(
            new ExecutionStepProperties(
                QueryLoggerUtil.queryLoggerName(contextStacker.getQueryContext()),
                joinSchema,
                joinKey
            ),
            JoinType.OUTER,
            executionStep,
            otherSchemaKStream.executionStep
        )
    );
  }


  @SuppressWarnings("unchecked")
  public SchemaKStream<?> selectKey(
      final Field newKeyField,
      final boolean updateRowKey,
      final QueryContext.Stacker contextStacker) {
    if (getKeyField() != null && getKeyField().name().equals(newKeyField.name())) {
      return this;
    }

    final KStream keyedKStream = kstream
        .filter((key, value) -> value != null
            && extractColumn(newKeyField, value) != null)
        .selectKey((key, value) -> extractColumn(newKeyField, value).toString())
        .mapValues((key, row) -> {
          if (updateRowKey) {
            row.getColumns().set(SchemaUtil.ROWKEY_NAME_INDEX, key);
          }
          return row;
        });

    return new SchemaKStream<>(
        keyedKStream,
        Serdes::String,
        ksqlConfig,
        functionRegistry,
        contextStacker.getQueryContext(),
        new StreamSelectKey(
            new ExecutionStepProperties(
                QueryLoggerUtil.queryLoggerName(contextStacker.getQueryContext()),
                getSchema(),
                newKeyField
            ),
            executionStep,
            updateRowKey
        )
    );
  }

  private Object extractColumn(final Field newKeyField, final GenericRow value) {
    return value
        .getColumns()
        .get(SchemaUtil.getFieldIndexByName(getSchema(), newKeyField.name()));
  }

  private static String fieldNameFromExpression(final Expression expression) {
    if (expression instanceof DereferenceExpression) {
      final DereferenceExpression dereferenceExpression =
          (DereferenceExpression) expression;
      return dereferenceExpression.getFieldName();
    } else if (expression instanceof QualifiedNameReference) {
      final QualifiedNameReference qualifiedNameReference = (QualifiedNameReference) expression;
      return qualifiedNameReference.getName().toString();
    }
    return null;
  }

  private boolean rekeyRequired(final List<Expression> groupByExpressions) {
    if (groupByExpressions.size() != 1) {
      return true;
    }

    final Field keyField = getKeyField();
    if (keyField == null) {
      return true;
    }

    final String groupByField = fieldNameFromExpression(groupByExpressions.get(0));
    if (groupByField == null) {
      return true;
    }

    final String keyFieldName = SchemaUtil.getFieldNameWithNoAlias(keyField);
    return !groupByField.equals(keyFieldName);
  }

  public SchemaKGroupedStream groupBy(
      final Serde<GenericRow> valSerde,
      final List<Expression> groupByExpressions,
      final QueryContext.Stacker contextStacker) {
    final boolean rekey = rekeyRequired(groupByExpressions);
    if (!rekey) {
      final KGroupedStream kgroupedStream = kstream.groupByKey(
          streamsFactories.getGroupedFactory().create(
              StreamsUtil.buildOpName(contextStacker.getQueryContext()),
              keySerdeFactory.create(),
              valSerde)
      );
      return new SchemaKGroupedStream(
          kgroupedStream,
          ksqlConfig,
          functionRegistry,
          new StreamGroupBy(
              new ExecutionStepProperties(
                  QueryLoggerUtil.queryLoggerName(contextStacker.getQueryContext()),
                  getSchema(),
                  getKeyField()
              ),
              executionStep,
              groupByExpressions
          )
      );
    }

    final GroupBy groupBy = new GroupBy(groupByExpressions);

    final KGroupedStream kgroupedStream = kstream
        .filter((key, value) -> value != null)
        .groupBy(
            groupBy.mapper,
            streamsFactories.getGroupedFactory().create(
                StreamsUtil.buildOpName(contextStacker.getQueryContext()),
                Serdes.String(),
                valSerde)
        );

    // TODO: if the key is a prefix of the grouping columns then we can
    //       use the repartition reflection hack to tell streams not to
    //       repartition.
    final Field newKeyField = new Field(
        groupBy.aggregateKeyName, -1, Schema.OPTIONAL_STRING_SCHEMA);
    return new SchemaKGroupedStream(
        kgroupedStream,
        ksqlConfig,
        functionRegistry,
        new StreamGroupBy(
            new ExecutionStepProperties(
                QueryLoggerUtil.queryLoggerName(contextStacker.getQueryContext()),
                getSchema(),
                newKeyField
            ),
            executionStep,
            groupByExpressions
        )
    );
  }

  public SchemaKStream overwriteSchema(
      final Schema schema,
      final Field keyField,
      final QueryContext.Stacker contextStacker) {
    return new SchemaKStream<>(
        kstream,
        keySerdeFactory,
        ksqlConfig,
        functionRegistry,
        contextStacker.getQueryContext(),
        new StreamOverwriteSchemaAndKey(
            new ExecutionStepProperties(
                QueryLoggerUtil.queryLoggerName(contextStacker.getQueryContext()),
                schema,
                keyField
            ),
            executionStep
        )
    );
  }

  public Field getKeyField() {
    return executionStep.getProperties().getKey().orElse(null);
  }

  public KStream<K, GenericRow> getKstream() {
    return kstream;
  }

  public String getExecutionPlan(final String indent) {
    return getExecutionPlan(executionStep, indent);
  }

  private static String getExecutionPlan(final ExecutionStep executionStep, final String indent) {
    final StringBuilder stringBuilder = new StringBuilder();
    stringBuilder.append(indent)
        .append(" > [ ")
        .append(executionStep.getType()).append(" ] | Schema: ")
        .append(SchemaUtil.getSchemaDefinitionString(executionStep.getProperties().getSchema()))
        .append(" | Logger: ").append(executionStep.getProperties().getId())
        .append("\n");
    for (final ExecutionStep source : executionStep.getSources()) {
      stringBuilder
          .append("\t")
          .append(indent)
          .append(getExecutionPlan(source, indent + "\t"));
    }
    return stringBuilder.toString();
  }

  public OutputNode outputNode() {
    return output;
  }

  public void setOutputNode(final OutputNode output) {
    this.output = output;
  }

  public FunctionRegistry getFunctionRegistry() {
    return functionRegistry;
  }

  class GroupBy {

    final String aggregateKeyName;
    final GroupByMapper<Object> mapper;

    GroupBy(final List<Expression> expressions) {
      final List<ExpressionMetadata> groupBy = CodeGenRunner.compileExpressions(
          expressions.stream(), "Group By", getSchema(), ksqlConfig, functionRegistry);

      this.mapper = new GroupByMapper<>(groupBy);
      this.aggregateKeyName = GroupByMapper.keyNameFor(expressions);
    }
  }

  protected static class KsqlValueJoiner
      implements ValueJoiner<GenericRow, GenericRow, GenericRow> {
    private final Schema leftSchema;
    private final Schema rightSchema;

    KsqlValueJoiner(final Schema leftSchema, final Schema rightSchema) {
      this.leftSchema = leftSchema;
      this.rightSchema = rightSchema;
    }

    @Override
    public GenericRow apply(final GenericRow left, final GenericRow right) {
      final List<Object> columns = new ArrayList<>();
      if (left != null) {
        columns.addAll(left.getColumns());
      } else {
        fillWithNulls(columns, leftSchema.fields().size());
      }

      if (right != null) {
        columns.addAll(right.getColumns());
      } else {
        fillWithNulls(columns, rightSchema.fields().size());
      }

      return new GenericRow(columns);
    }

    private static void fillWithNulls(final List<Object> columns, final int numToFill) {
      for (int i = 0; i < numToFill; ++i) {
        columns.add(null);
      }
    }
  }
}
