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

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.mock;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.same;
import static org.easymock.EasyMock.verify;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.ksql.GenericRow;
import io.confluent.ksql.function.InternalFunctionRegistry;
import io.confluent.ksql.logging.processing.ProcessingLogContext;
import io.confluent.ksql.metastore.MetaStore;
import io.confluent.ksql.metastore.model.KsqlTable;
import io.confluent.ksql.metastore.model.KsqlTopic;
import io.confluent.ksql.parser.tree.DereferenceExpression;
import io.confluent.ksql.parser.tree.Expression;
import io.confluent.ksql.parser.tree.QualifiedName;
import io.confluent.ksql.parser.tree.QualifiedNameReference;
import io.confluent.ksql.planner.plan.FilterNode;
import io.confluent.ksql.planner.plan.PlanNode;
import io.confluent.ksql.planner.plan.ProjectNode;
import io.confluent.ksql.query.QueryId;
import io.confluent.ksql.serde.KsqlTopicSerDe;
import io.confluent.ksql.serde.json.KsqlJsonTopicSerDe;
import io.confluent.ksql.streams.GroupedFactory;
import io.confluent.ksql.streams.JoinedFactory;
import io.confluent.ksql.streams.MaterializedFactory;
import io.confluent.ksql.streams.StreamsFactories;
import io.confluent.ksql.streams.StreamsUtil;
import io.confluent.ksql.structured.execution.ExecutionStepProperties;
import io.confluent.ksql.structured.execution.ExecutionStep;
import io.confluent.ksql.structured.execution.JoinType;
import io.confluent.ksql.structured.execution.TableFilter;
import io.confluent.ksql.structured.execution.TableGroupBy;
import io.confluent.ksql.structured.execution.TableSelect;
import io.confluent.ksql.structured.execution.TableSink;
import io.confluent.ksql.structured.execution.TableSource;
import io.confluent.ksql.structured.execution.TableTableJoin;
import io.confluent.ksql.testutils.AnalysisTestUtil;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.MetaStoreFixture;
import io.confluent.ksql.util.SchemaUtil;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KGroupedTable;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.KeyValueMapper;
import org.apache.kafka.streams.kstream.Predicate;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

@SuppressWarnings("unchecked")
public class SchemaKTableTest {
  private final KsqlConfig ksqlConfig = new KsqlConfig(Collections.emptyMap());
  private final MetaStore metaStore = MetaStoreFixture.getNewMetaStore(new InternalFunctionRegistry());
  private final GroupedFactory groupedFactory = mock(GroupedFactory.class);
  private final Grouped grouped = Grouped.with(
      "group", Serdes.String(), Serdes.String());

  private SchemaKTable initialSchemaKTable;
  private KTable kTable;
  private KsqlTable<?> ksqlTable;
  private InternalFunctionRegistry functionRegistry;
  private KTable mockKTable;
  private SchemaKTable firstSchemaKTable;
  private SchemaKTable secondSchemaKTable;
  private Schema joinSchema;
  private final QueryContext.Stacker queryContext
      = new QueryContext.Stacker(new QueryId("query")).push("node");
  private final QueryContext parentContext = queryContext.push("parent").getQueryContext();
  private final QueryContext.Stacker childContextStacker = queryContext.push("child");
  private final ProcessingLogContext processingLogContext = ProcessingLogContext.create();

  @Before
  public void init() {
    functionRegistry = new InternalFunctionRegistry();
    ksqlTable = (KsqlTable) metaStore.getSource("TEST2");
    final StreamsBuilder builder = new StreamsBuilder();
    kTable = builder.table(ksqlTable.getKsqlTopic().getKafkaTopicName(),
                           Consumed.with(Serdes.String(),
                                         getRowSerde(ksqlTable.getKsqlTopic(),
                                                     ksqlTable.getSchema())));

    final KsqlTable secondKsqlTable = (KsqlTable) metaStore.getSource("TEST3");
    final KTable secondKTable = builder.table(secondKsqlTable.getKsqlTopic().getKafkaTopicName(),
        Consumed.with(Serdes.String(),
            getRowSerde(secondKsqlTable.getKsqlTopic(),
                secondKsqlTable.getSchema())));

    mockKTable = EasyMock.niceMock(KTable.class);
    firstSchemaKTable = buildSchemaKTableForJoin(ksqlTable, mockKTable);
    secondSchemaKTable = buildSchemaKTableForJoin(secondKsqlTable, secondKTable);
    joinSchema = getJoinSchema(ksqlTable.getSchema(), secondKsqlTable.getSchema());
  }

  private ExecutionStep buildSourceStep(final Schema schema, final KsqlTable ksqlTable) {
    return new TableSource(
        new ExecutionStepProperties(
            "source",
            schema,
            (Field) ksqlTable.getKeyField().orElse(null)
        ),
        ksqlTable.getKafkaTopicName()
    );
  }

  private Serde<GenericRow> buildSerdeForTable(final SchemaKTable table) {
    return new KsqlJsonTopicSerDe().getGenericRowSerde(
        table.getSchema(),
        null,
        false,
        () -> null,
        "test",
        processingLogContext);
  }

  private SchemaKTable buildSchemaKTable(
      final KsqlTable<?> ksqlTable,
      final Schema schema,
      final KTable kTable,
      final GroupedFactory groupedFactory) {
    return new SchemaKTable(
        kTable,
        Serdes::String,
        ksqlConfig,
        functionRegistry,
        new StreamsFactories(
            groupedFactory,
            JoinedFactory.create(ksqlConfig),
            MaterializedFactory.create(ksqlConfig)),
        parentContext,
        buildSourceStep(schema, ksqlTable));
  }

  private SchemaKTable buildSchemaKTable(
      final KsqlTable ksqlTable,
      final KTable kTable,
      final GroupedFactory groupedFactory) {
    return buildSchemaKTable(
        ksqlTable,
        SchemaUtil.buildSchemaWithAlias(ksqlTable.getSchema(), ksqlTable.getName()),
        kTable,
        groupedFactory);
  }

  private SchemaKTable buildSchemaKTableForJoin(final KsqlTable ksqlTable, final KTable kTable) {
    return buildSchemaKTable(
        ksqlTable, ksqlTable.getSchema(), kTable, GroupedFactory.create(ksqlConfig));
  }

  private Serde<GenericRow> getRowSerde(final KsqlTopic topic, final Schema schema) {
    return topic.getKsqlTopicSerDe().getGenericRowSerde(
        schema,
        new KsqlConfig(Collections.emptyMap()),
        false,
        MockSchemaRegistryClient::new,
        "test",
        processingLogContext);
  }

  private void givenInitialTable(final PlanNode logicalPlan) {
    initialSchemaKTable = new SchemaKTable<>(
        kTable,
        Serdes::String,
        ksqlConfig,
        functionRegistry,
        parentContext,
        buildSourceStep(logicalPlan.getTheSourceNode().getSchema(), ksqlTable));
  }

  @Test
  public void testSelectSchemaKStream() {
    final PlanNode logicalPlan =
        buildLogicalPlan("SELECT col0, col2, col3 FROM test2 WHERE col0 > 100;");
    givenInitialTable(logicalPlan);
    final ProjectNode projectNode = (ProjectNode) logicalPlan.getSources().get(0);
    final SchemaKTable projectedSchemaKStream = initialSchemaKTable.select(
        projectNode.getProjectSelectExpressions(),
        childContextStacker,
        processingLogContext
    );
    Assert.assertTrue(projectedSchemaKStream.getSchema().fields().size() == 3);
    Assert.assertTrue(projectedSchemaKStream.getSchema().field("COL0") ==
                      projectedSchemaKStream.getSchema().fields().get(0));
    Assert.assertTrue(projectedSchemaKStream.getSchema().field("COL2") ==
                      projectedSchemaKStream.getSchema().fields().get(1));
    Assert.assertTrue(projectedSchemaKStream.getSchema().field("COL3") ==
                      projectedSchemaKStream.getSchema().fields().get(2));

    Assert.assertTrue(projectedSchemaKStream.getSchema()
                          .field("COL0").schema().type() == Schema.Type.INT64);
    Assert.assertTrue(projectedSchemaKStream.getSchema()
                          .field("COL2").schema().type() == Schema.Type.STRING);
    Assert.assertTrue(projectedSchemaKStream.getSchema()
                          .field("COL3").schema().type() == Schema.Type.FLOAT64);

    Assert.assertThat(
        projectedSchemaKStream.getExecutionStep().getSources().get(0),
        is(initialSchemaKTable.getExecutionStep()));
  }

  @Test
  public void shouldBuildStepForSelect() {
    // Given:
    final PlanNode logicalPlan =
        buildLogicalPlan("SELECT col0, col2, col3 FROM test2 WHERE col0 > 100;");
    givenInitialTable(logicalPlan);
    final ProjectNode projectNode = (ProjectNode) logicalPlan.getSources().get(0);

    // When:
    final SchemaKTable projectedSchemaKTable = initialSchemaKTable.select(
        projectNode.getProjectSelectExpressions(),
        childContextStacker,
        processingLogContext
    );

    // Then:
    assertThat(
        projectedSchemaKTable.getExecutionStep(),
        equalTo(
            new TableSelect(
                new ExecutionStepProperties(
                    "query.node.child",
                    projectedSchemaKTable.getSchema(),
                    projectedSchemaKTable.getKeyField()
                ),
                initialSchemaKTable.executionStep,
                projectNode.getProjectSelectExpressions()
            )
        )
    );
  }

  @Test
  public void testSelectWithExpression() {
    final String selectQuery = "SELECT col0, LEN(UCASE(col2)), col3*3+5 FROM test2 WHERE col0 > 100;";
    final PlanNode logicalPlan = buildLogicalPlan(selectQuery);
    final ProjectNode projectNode = (ProjectNode) logicalPlan.getSources().get(0);
    initialSchemaKTable = new SchemaKTable<>(
        kTable,
        Serdes::String,
        ksqlConfig,
        functionRegistry,
        parentContext,
        buildSourceStep(logicalPlan.getTheSourceNode().getSchema(), ksqlTable));
    final SchemaKTable projectedSchemaKStream = initialSchemaKTable.select(
        projectNode.getProjectSelectExpressions(),
        childContextStacker,
        processingLogContext
    );
    Assert.assertTrue(projectedSchemaKStream.getSchema().fields().size() == 3);
    Assert.assertTrue(projectedSchemaKStream.getSchema().field("COL0") ==
        projectedSchemaKStream.getSchema().fields().get(0));
    Assert.assertTrue(projectedSchemaKStream.getSchema()
        .field("KSQL_COL_1") ==
        projectedSchemaKStream.getSchema().fields().get(1));
    Assert.assertTrue(projectedSchemaKStream.getSchema()
        .field("KSQL_COL_2") ==
        projectedSchemaKStream.getSchema().fields().get(2));

    Assert.assertTrue(projectedSchemaKStream.getSchema()
        .field("COL0").schema().type() == Schema.Type.INT64);
    Assert.assertTrue(projectedSchemaKStream.getSchema().fields().get(1).schema() == Schema
        .OPTIONAL_INT32_SCHEMA);
    Assert.assertTrue(projectedSchemaKStream.getSchema().fields().get(2).schema() == Schema
        .OPTIONAL_FLOAT64_SCHEMA);
    Assert.assertThat(
        projectedSchemaKStream.getExecutionStep().getSources().get(0),
        is(initialSchemaKTable.getExecutionStep()));
  }

  @Test
  public void testFilter() {
    final String selectQuery = "SELECT col0, col2, col3 FROM test2 WHERE col0 > 100;";
    final PlanNode logicalPlan = buildLogicalPlan(selectQuery);
    final FilterNode filterNode = (FilterNode) logicalPlan.getSources().get(0).getSources().get(0);

    initialSchemaKTable = new SchemaKTable<>(
        kTable,
        Serdes::String,
        ksqlConfig,
        functionRegistry,
        parentContext,
        buildSourceStep(logicalPlan.getTheSourceNode().getSchema(), ksqlTable));
    final SchemaKTable filteredSchemaKStream = initialSchemaKTable.filter(
        filterNode.getPredicate(),
        childContextStacker,
        processingLogContext
    );

    Assert.assertTrue(filteredSchemaKStream.getSchema().fields().size() == 7);
    Assert.assertTrue(filteredSchemaKStream.getSchema().field("TEST2.COL0") ==
                      filteredSchemaKStream.getSchema().fields().get(2));
    Assert.assertTrue(filteredSchemaKStream.getSchema().field("TEST2.COL1") ==
                      filteredSchemaKStream.getSchema().fields().get(3));
    Assert.assertTrue(filteredSchemaKStream.getSchema().field("TEST2.COL2") ==
                      filteredSchemaKStream.getSchema().fields().get(4));
    Assert.assertTrue(filteredSchemaKStream.getSchema().field("TEST2.COL3") ==
                      filteredSchemaKStream.getSchema().fields().get(5));

    Assert.assertTrue(filteredSchemaKStream.getSchema()
                          .field("TEST2.COL0").schema().type() == Schema.Type.INT64);
    Assert.assertTrue(filteredSchemaKStream.getSchema()
                          .field("TEST2.COL1").schema().type() == Schema.Type.STRING);
    Assert.assertTrue(filteredSchemaKStream.getSchema()
                          .field("TEST2.COL2").schema().type() == Schema.Type.STRING);
    Assert.assertTrue(filteredSchemaKStream.getSchema()
                          .field("TEST2.COL3").schema().type() == Schema.Type.FLOAT64);
    Assert.assertThat(
        filteredSchemaKStream.getExecutionStep().getSources().get(0),
        is(initialSchemaKTable.getExecutionStep()));
  }

  @Test
  public void shouldBuildStepForFilter() {
    // Given:
    final String selectQuery = "SELECT col0, col2, col3 FROM test2 WHERE col0 > 100;";
    final PlanNode logicalPlan = buildLogicalPlan(selectQuery);
    final FilterNode filterNode = (FilterNode) logicalPlan.getSources().get(0).getSources().get(0);
    givenInitialTable(logicalPlan);

    // When:
    final SchemaKTable filteredSchemaKStream = initialSchemaKTable.filter(
        filterNode.getPredicate(),
        childContextStacker,
        processingLogContext
    );

    // Then:
    assertThat(
        filteredSchemaKStream.getExecutionStep(),
        equalTo(
            new TableFilter(
                new ExecutionStepProperties(
                    "query.node.child",
                    initialSchemaKTable.getSchema(),
                    initialSchemaKTable.getKeyField()
                ),
                initialSchemaKTable.executionStep,
                filterNode.getPredicate()
            )
        )
    );
  }

  @Test
  public void shouldBuildStepForSink() {
    // Given:
    final PlanNode logicalPlan =
        buildLogicalPlan("SELECT col0, col2, col3 FROM test2 WHERE col0 > 100;");
    givenInitialTable(logicalPlan);

    // When:
    final SchemaKTable sinkKTable = initialSchemaKTable.into(
        "topic",
        getRowSerde(ksqlTable.getKsqlTopic(), ksqlTable.getSchema()),
        Collections.emptySet(),
        childContextStacker
    );

    // Then:
    assertThat(
        sinkKTable.getExecutionStep(),
        equalTo(
            new TableSink(
                new ExecutionStepProperties(
                    "query.node.child",
                    initialSchemaKTable.getSchema(),
                    initialSchemaKTable.getKeyField()
                ),
                initialSchemaKTable.executionStep,
                "topic"
            )
        )
    );
  }

  @Test
  public void testGroupBy() {
    final PlanNode logicalPlan = buildLogicalPlan("SELECT col0, col1, col2 FROM test2;");
    givenInitialTable(logicalPlan);

    final Expression col1Expression = new DereferenceExpression(
        new QualifiedNameReference(QualifiedName.of("TEST2")), "COL1");
    final Expression col2Expression = new DereferenceExpression(
        new QualifiedNameReference(QualifiedName.of("TEST2")), "COL2");
    final List<Expression> groupByExpressions = Arrays.asList(col2Expression, col1Expression);
    final SchemaKGroupedStream groupedSchemaKTable = initialSchemaKTable.groupBy(
        buildSerdeForTable(initialSchemaKTable),
        groupByExpressions,
        childContextStacker);

    assertThat(groupedSchemaKTable, instanceOf(SchemaKGroupedTable.class));
    assertThat(groupedSchemaKTable.getKeyField().name(), equalTo("TEST2.COL2|+|TEST2.COL1"));
  }

  @Test
  public void shouldBuildStepForGroupBy() {
    // Given:
    final PlanNode logicalPlan = buildLogicalPlan("SELECT col0, col1, col2 FROM test2;");
    givenInitialTable(logicalPlan);
    final Expression col1Expression = new DereferenceExpression(
        new QualifiedNameReference(QualifiedName.of("TEST2")), "COL1");
    final Expression col2Expression = new DereferenceExpression(
        new QualifiedNameReference(QualifiedName.of("TEST2")), "COL2");
    final List<Expression> groupByExpressions = Arrays.asList(col2Expression, col1Expression);

    // When:
    final SchemaKGroupedStream groupedSchemaKTable = initialSchemaKTable.groupBy(
        buildSerdeForTable(initialSchemaKTable),
        groupByExpressions,
        childContextStacker);

    // Then;
    assertThat(
        groupedSchemaKTable.getExecutionStep(),
        equalTo(
            new TableGroupBy(
                new ExecutionStepProperties(
                    "query.node.child",
                    initialSchemaKTable.getSchema(),
                    groupedSchemaKTable.getKeyField()
                ),
                initialSchemaKTable.executionStep,
                groupByExpressions
            )
        )
    );
  }

  @Test
  public void shouldUseOpNameForGrouped() {
    // Given:
    final Serde<GenericRow> valSerde = getRowSerde(ksqlTable.getKsqlTopic(), ksqlTable.getSchema());
    expect(
        groupedFactory.create(
            eq(StreamsUtil.buildOpName(childContextStacker.getQueryContext())),
            anyObject(Serdes.String().getClass()),
            same(valSerde))
    ).andReturn(grouped);
    expect(mockKTable.filter(anyObject(Predicate.class))).andReturn(mockKTable);
    final KGroupedTable groupedTable = mock(KGroupedTable.class);
    expect(mockKTable.groupBy(anyObject(), same(grouped))).andReturn(groupedTable);
    replay(groupedFactory, mockKTable);
    final Expression col1Expression = new DereferenceExpression(
        new QualifiedNameReference(QualifiedName.of(ksqlTable.getName())), "COL1");
    final List<Expression> groupByExpressions = Collections.singletonList(col1Expression);
    final SchemaKTable schemaKTable = buildSchemaKTable(ksqlTable, mockKTable, groupedFactory);

    // When:
    schemaKTable.groupBy(valSerde, groupByExpressions, childContextStacker);

    // Then:
    verify(mockKTable, groupedFactory);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void shouldGroupKeysCorrectly() {
    // set up a mock KTable and KGroupedTable for the test. Set up the KTable to
    // capture the mapper that is passed in to produce new keys
    final KTable mockKTable = mock(KTable.class);
    final KGroupedTable mockKGroupedTable = mock(KGroupedTable.class);
    final Capture<KeyValueMapper> capturedKeySelector = Capture.newInstance();
    expect(mockKTable.filter(anyObject(Predicate.class))).andReturn(mockKTable);
    expect(mockKTable.groupBy(capture(capturedKeySelector), anyObject(Grouped.class)))
        .andReturn(mockKGroupedTable);
    replay(mockKTable, mockKGroupedTable);

    // Build our test object from the mocks
    final String selectQuery = "SELECT col0, col1, col2 FROM test2;";
    final PlanNode logicalPlan = buildLogicalPlan(selectQuery);
    initialSchemaKTable = new SchemaKTable<>(
        mockKTable,
        Serdes::String,
        ksqlConfig,
        functionRegistry,
        parentContext,
        buildSourceStep(logicalPlan.getTheSourceNode().getSchema(), ksqlTable));

    // Given a grouping expression comprising COL1 and COL2
    final Expression col1Expression = new DereferenceExpression(
        new QualifiedNameReference(QualifiedName.of("TEST2")), "COL1");
    final Expression col2Expression = new DereferenceExpression(
        new QualifiedNameReference(QualifiedName.of("TEST2")), "COL2");
    final List<Expression> groupByExpressions = Arrays.asList(col2Expression, col1Expression);

    // Call groupBy and extract the captured mapper
    initialSchemaKTable.groupBy(
        buildSerdeForTable(initialSchemaKTable),
        groupByExpressions,
        childContextStacker);
    verify(mockKTable, mockKGroupedTable);
    final KeyValueMapper keySelector = capturedKeySelector.getValue();
    final GenericRow value = new GenericRow(Arrays.asList("key", 0, 100, "foo", "bar"));
    final KeyValue<String, GenericRow> keyValue =
        (KeyValue<String, GenericRow>) keySelector.apply("key", value);

    // Validate that the captured mapper produces the correct key
    assertThat(keyValue.key, equalTo("bar|+|foo"));
    assertThat(keyValue.value, equalTo(value));
  }

  private void givenTableTableLeftJoin() {
    expect(mockKTable.leftJoin(eq(secondSchemaKTable.getKtable()),
        anyObject(SchemaKStream.KsqlValueJoiner.class)))
        .andReturn(mockKTable);
    replay(mockKTable);
  }

  private SchemaKStream whenTableTableLeftJoin() {
    return firstSchemaKTable.leftJoin(
        secondSchemaKTable,
        joinSchema,
        joinSchema.fields().get(0),
        childContextStacker);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void shouldPerformTableToTableLeftJoin() {
    givenTableTableLeftJoin();

    final SchemaKStream joinedKStream = whenTableTableLeftJoin();

    verify(mockKTable);

    assertThat(joinedKStream, instanceOf(SchemaKTable.class));
    assertEquals(ExecutionStep.Type.JOIN, joinedKStream.getExecutionStep().getType());
    assertEquals(joinSchema, joinedKStream.getSchema());
    assertEquals(joinSchema.fields().get(0), joinedKStream.getKeyField());
    assertEquals(
        Arrays.asList(
            firstSchemaKTable.getExecutionStep(),
            secondSchemaKTable.getExecutionStep()),
        joinedKStream.getExecutionStep().getSources()
    );
  }

  @Test
  public void shouldBuildStepForTableTableLeftJoin() {
    // Given:
    givenTableTableLeftJoin();

    // When:
    final SchemaKStream joinedKStream = whenTableTableLeftJoin();

    // Then:
    assertThat(
        joinedKStream.getExecutionStep(),
        equalTo(
            new TableTableJoin(
                new ExecutionStepProperties(
                    "query.node.child",
                    joinSchema,
                    joinSchema.fields().get(0)
                ),
                JoinType.LEFT,
                firstSchemaKTable.getExecutionStep(),
                secondSchemaKTable.getExecutionStep()
            )
        )
    );
  }

  private void givenTableTableJoin() {
    expect(mockKTable.join(eq(secondSchemaKTable.getKtable()),
        anyObject(SchemaKStream.KsqlValueJoiner.class)))
        .andReturn(EasyMock.niceMock(KTable.class));
    replay(mockKTable);
  }

  private SchemaKStream whenTableTableJoin() {
    return firstSchemaKTable.join(
        secondSchemaKTable, joinSchema, joinSchema.fields().get(0), childContextStacker);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void shouldPerformTableToTableInnerJoin() {
    givenTableTableJoin();

    final SchemaKStream joinedKStream = whenTableTableJoin();

    verify(mockKTable);

    assertThat(joinedKStream, instanceOf(SchemaKTable.class));
    assertEquals(ExecutionStep.Type.JOIN, joinedKStream.getExecutionStep().getType());
    assertEquals(joinSchema, joinedKStream.getSchema());
    assertEquals(joinSchema.fields().get(0), joinedKStream.getKeyField());
    assertEquals(
        Arrays.asList(
            firstSchemaKTable.getExecutionStep(),
            secondSchemaKTable.getExecutionStep()),
        joinedKStream.getExecutionStep().getSources()
    );
  }

  @Test
  public void shouldBuildStepForTableTableJoin() {
    // Given:
    givenTableTableJoin();

    // When:
    final SchemaKStream joinedKStream = whenTableTableJoin();

    // Then:
    assertThat(
        joinedKStream.getExecutionStep(),
        equalTo(
            new TableTableJoin(
                new ExecutionStepProperties(
                    "query.node.child",
                    joinSchema,
                    joinSchema.fields().get(0)
                ),
                JoinType.INNER,
                firstSchemaKTable.getExecutionStep(),
                secondSchemaKTable.getExecutionStep()
            )
        )
    );
  }

  private void givenTableTableOuterJoin() {
    expect(mockKTable.outerJoin(eq(secondSchemaKTable.getKtable()),
        anyObject(SchemaKStream.KsqlValueJoiner.class)))
        .andReturn(EasyMock.niceMock(KTable.class));
    replay(mockKTable);
  }

  private SchemaKStream whenTableTableOuterJoin() {
    return firstSchemaKTable.outerJoin(
        secondSchemaKTable, joinSchema, joinSchema.fields().get(0), childContextStacker);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void shouldPerformTableToTableOuterJoin() {
    givenTableTableOuterJoin();

    final SchemaKStream joinedKStream = whenTableTableOuterJoin();

    verify(mockKTable);

    assertThat(joinedKStream, instanceOf(SchemaKTable.class));
    assertEquals(
        ExecutionStep.Type.JOIN,
        joinedKStream.getExecutionStep().getType());
    assertEquals(joinSchema, joinedKStream.getSchema());
    assertEquals(joinSchema.fields().get(0), joinedKStream.getKeyField());
    assertEquals(
        Arrays.asList(
            firstSchemaKTable.getExecutionStep(),
            secondSchemaKTable.getExecutionStep()),
        joinedKStream.getExecutionStep().getSources()
    );
  }

  @Test
  public void shouldBuildStepForTableTableOuterJoin() {
    // Given:
    givenTableTableJoin();

    // When:
    final SchemaKStream joinedKStream = whenTableTableOuterJoin();

    // Then:
    assertThat(
        joinedKStream.getExecutionStep(),
        equalTo(
            new TableTableJoin(
                new ExecutionStepProperties(
                    "query.node.child",
                    joinSchema,
                    joinSchema.fields().get(0)
                ),
                JoinType.OUTER,
                firstSchemaKTable.getExecutionStep(),
                secondSchemaKTable.getExecutionStep()
            )
        )
    );
  }

  private static Schema getJoinSchema(final Schema leftSchema, final Schema rightSchema) {
    final SchemaBuilder schemaBuilder = SchemaBuilder.struct();
    final String leftAlias = "left";
    final String rightAlias = "right";
    for (final Field field : leftSchema.fields()) {
      final String fieldName = leftAlias + "." + field.name();
      schemaBuilder.field(fieldName, field.schema());
    }

    for (final Field field : rightSchema.fields()) {
      final String fieldName = rightAlias + "." + field.name();
      schemaBuilder.field(fieldName, field.schema());
    }
    return schemaBuilder.build();
  }

  private PlanNode buildLogicalPlan(final String query) {
    return AnalysisTestUtil.buildLogicalPlan(query, metaStore);
  }
}
