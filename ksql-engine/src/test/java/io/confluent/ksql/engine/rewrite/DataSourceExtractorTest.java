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

package io.confluent.ksql.engine.rewrite;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;

import io.confluent.ksql.function.FunctionRegistry;
import io.confluent.ksql.metastore.MetaStore;
import io.confluent.ksql.name.SourceName;
import io.confluent.ksql.parser.AstBuilder;
import io.confluent.ksql.parser.DefaultKsqlParser;
import io.confluent.ksql.parser.KsqlParser.ParsedStatement;
import io.confluent.ksql.parser.tree.AstNode;
import io.confluent.ksql.util.KsqlException;
import io.confluent.ksql.util.MetaStoreFixture;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class DataSourceExtractorTest {

  private static final SourceName TEST1 = SourceName.of("TEST1");
  private static final SourceName TEST2 = SourceName.of("TEST2");

  private static final SourceName T1 = SourceName.of("T1");
  private static final SourceName T2 = SourceName.of("T2");

  private static final MetaStore META_STORE = MetaStoreFixture
      .getNewMetaStore(mock(FunctionRegistry.class));

  private DataSourceExtractor extractor;

  @Rule
  public final ExpectedException expectedException = ExpectedException.none();

  @Before
  public void setUp() {
    extractor = new DataSourceExtractor(META_STORE);
  }

  @Test
  public void shouldExtractUnaliasedDataSources() {
    // Given:
    final AstNode stmt = givenQuery("SELECT * FROM TEST1;");

    // When:
    extractor.extractDataSources(stmt);

    // Then:
    assertThat(extractor.getFromName(), is(TEST1));
    assertThat(extractor.getFromAlias(), is(TEST1));
  }

  @Test
  public void shouldHandleAliasedDataSources() {
    // Given:
    final AstNode stmt = givenQuery("SELECT * FROM TEST1 t;");

    // When:
    extractor.extractDataSources(stmt);

    // Then:
    assertThat(extractor.getFromName(), is(TEST1));
    assertThat(extractor.getFromAlias(), is(SourceName.of("T")));
  }

  @Test
  public void shouldExtractAsAliasedDataSources() {
    // Given:
    final AstNode stmt = givenQuery("SELECT * FROM TEST1 AS t;");

    // When:
    extractor.extractDataSources(stmt);

    // Then:
    assertThat(extractor.getFromName(), is(TEST1));
    assertThat(extractor.getFromAlias(), is(SourceName.of("T")));
  }

  @Test
  public void shouldThrowIfSourceDoesNotExist() {
    // Given:
    final AstNode stmt = givenQuery("SELECT * FROM UNKNOWN;");

    // Then:
    expectedException.expect(KsqlException.class);
    expectedException.expectMessage("UNKNOWN does not exist.");

    // When:
    extractor.extractDataSources(stmt);
  }

  @Test
  public void shouldExtractUnaliasedJoinDataSources() {
    // Given:
    final AstNode stmt = givenQuery("SELECT * FROM TEST1 JOIN TEST2"
        + " ON test1.col1 = test2.col1;");

    // When:
    extractor.extractDataSources(stmt);

    // Then:
    assertThat(extractor.getLeftName(), is(TEST1));
    assertThat(extractor.getLeftAlias(), is(TEST1));
    assertThat(extractor.getRightName(), is(TEST2));
    assertThat(extractor.getRightAlias(), is(TEST2));
  }

  @Test
  public void shouldHandleAliasedJoinDataSources() {
    // Given:
    final AstNode stmt = givenQuery("SELECT * FROM TEST1 t1 JOIN TEST2 t2"
        + " ON test1.col1 = test2.col1;");

    // When:
    extractor.extractDataSources(stmt);

    // Then:
    assertThat(extractor.getLeftName(), is(TEST1));
    assertThat(extractor.getLeftAlias(), is(T1));
    assertThat(extractor.getRightName(), is(TEST2));
    assertThat(extractor.getRightAlias(), is(T2));
  }

  @Test
  public void shouldExtractAsAliasedJoinDataSources() {
    // Given:
    final AstNode stmt = givenQuery("SELECT * FROM TEST1 AS t1 JOIN TEST2 AS t2"
        + " ON t1.col1 = t2.col1;");

    // When:
    extractor.extractDataSources(stmt);

    // Then:
    assertThat(extractor.getLeftName(), is(TEST1));
    assertThat(extractor.getLeftAlias(), is(T1));
    assertThat(extractor.getRightName(), is(TEST2));
    assertThat(extractor.getRightAlias(), is(T2));
  }

  @Test
  public void shouldThrowIfLeftJoinSourceDoesNotExist() {
    // Given:
    final AstNode stmt = givenQuery("SELECT * FROM UNKNOWN JOIN TEST2"
        + " ON UNKNOWN.col1 = test2.col1;");
    // Then:
    expectedException.expect(KsqlException.class);
    expectedException.expectMessage("UNKNOWN does not exist.");

    // When:
    extractor.extractDataSources(stmt);
  }

  @Test
  public void shouldThrowIfRightJoinSourceDoesNotExist() {
    // Given:
    final AstNode stmt = givenQuery("SELECT * FROM TEST1 JOIN UNKNOWN"
        + " ON test1.col1 = UNKNOWN.col1;");

    // Then:
    expectedException.expect(KsqlException.class);
    expectedException.expectMessage("UNKNOWN does not exist.");

    // When:
    extractor.extractDataSources(stmt);
  }

  private static AstNode givenQuery(final String sql) {
    final List<ParsedStatement> statements = new DefaultKsqlParser().parse(sql);
    assertThat(statements, hasSize(1));
    return new AstBuilder(META_STORE).build(statements.get(0).getStatement());
  }
}