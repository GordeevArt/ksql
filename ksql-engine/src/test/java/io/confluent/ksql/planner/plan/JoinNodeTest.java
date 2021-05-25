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

package io.confluent.ksql.planner.plan;

import static io.confluent.ksql.metastore.model.DataSource.DataSourceType;
import static io.confluent.ksql.planner.plan.PlanTestUtil.MAPVALUES_NODE;
import static io.confluent.ksql.planner.plan.PlanTestUtil.SOURCE_NODE;
import static io.confluent.ksql.planner.plan.PlanTestUtil.getNodeByName;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.confluent.ksql.function.FunctionRegistry;
import io.confluent.ksql.function.InternalFunctionRegistry;
import io.confluent.ksql.metastore.MetaStore;
import io.confluent.ksql.metastore.model.DataSource;
import io.confluent.ksql.metastore.model.KeyField;
import io.confluent.ksql.metastore.model.KsqlTopic;
import io.confluent.ksql.parser.tree.WithinExpression;
import io.confluent.ksql.physical.KsqlQueryBuilder;
import io.confluent.ksql.planner.plan.JoinNode.JoinType;
import io.confluent.ksql.query.QueryId;
import io.confluent.ksql.schema.ksql.KsqlSchema;
import io.confluent.ksql.serde.KsqlSerdeFactory;
import io.confluent.ksql.services.KafkaTopicClient;
import io.confluent.ksql.services.ServiceContext;
import io.confluent.ksql.structured.QueryContext;
import io.confluent.ksql.structured.SchemaKStream;
import io.confluent.ksql.structured.SchemaKTable;
import io.confluent.ksql.testutils.AnalysisTestUtil;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.KsqlException;
import io.confluent.ksql.util.MetaStoreFixture;
import io.confluent.ksql.util.SchemaUtil;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyDescription;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;


@SuppressWarnings({"SameParameterValue", "OptionalGetWithoutIsPresent"})
@RunWith(MockitoJUnitRunner.class)
public class JoinNodeTest {

  private static final Optional<String> NO_KEY_FIELD = Optional.empty();
  private final KsqlConfig ksqlConfig = new KsqlConfig(new HashMap<>());
  private StreamsBuilder builder;
  private SchemaKStream stream;
  private JoinNode joinNode;

  @Mock
  private KafkaTopicClient mockKafkaTopicClient;

  private static final String leftAlias = "left";
  private static final String rightAlias = "right";

  private final KsqlSchema leftSchema = createSchema(leftAlias);
  private final KsqlSchema rightSchema = createSchema(rightAlias);
  private final KsqlSchema joinSchema = joinSchema();

  private static final String LEFT_JOIN_FIELD_NAME = leftAlias + ".COL0";
  private static final String RIGHT_JOIN_FIELD_NAME = rightAlias + ".COL1";
  private static final KeyField leftJoinField = KeyField
      .of(LEFT_JOIN_FIELD_NAME, new Field(LEFT_JOIN_FIELD_NAME, 1, Schema.OPTIONAL_STRING_SCHEMA));
  private static final KeyField rightJoinField = KeyField
      .of(RIGHT_JOIN_FIELD_NAME,
          new Field(RIGHT_JOIN_FIELD_NAME, 1, Schema.OPTIONAL_STRING_SCHEMA));

  private static final WithinExpression WITHIN_EXPRESSION =
      new WithinExpression(10, TimeUnit.SECONDS);

  private static final PlanNodeId nodeId = new PlanNodeId("join");
  private static final QueryId queryId = new QueryId("join-query");
  private static final QueryContext.Stacker CONTEXT_STACKER =
      new QueryContext.Stacker(queryId).push(nodeId.toString());

  @Mock
  private DataSourceNode left;
  @Mock
  private DataSourceNode right;
  @Mock
  private SchemaKStream<String> leftSchemaKStream;
  @Mock
  private SchemaKStream<String> rightSchemaKStream;
  @Mock
  private SchemaKTable<String> leftSchemaKTable;
  @Mock
  private SchemaKTable<String> rightSchemaKTable;
  @Mock
  private KsqlQueryBuilder ksqlStreamBuilder;
  @Mock
  private FunctionRegistry functionRegistry;

  @Before
  public void setUp() {
    builder = new StreamsBuilder();

    final ServiceContext serviceContext = mock(ServiceContext.class);
    when(serviceContext.getTopicClient())
        .thenReturn(mockKafkaTopicClient);

    when(ksqlStreamBuilder.getKsqlConfig()).thenReturn(ksqlConfig);
    when(ksqlStreamBuilder.getStreamsBuilder()).thenReturn(builder);
    when(ksqlStreamBuilder.getServiceContext()).thenReturn(serviceContext);
    when(ksqlStreamBuilder.withKsqlConfig(any())).thenReturn(ksqlStreamBuilder);
    when(ksqlStreamBuilder.getFunctionRegistry()).thenReturn(functionRegistry);
    when(ksqlStreamBuilder.buildNodeContext(any())).thenAnswer(inv ->
        new QueryContext.Stacker(queryId)
            .push(inv.getArgument(0).toString()));

    when(left.getSchema()).thenReturn(leftSchema);
    when(right.getSchema()).thenReturn(rightSchema);

    when(left.getPartitions(mockKafkaTopicClient)).thenReturn(2);
    when(right.getPartitions(mockKafkaTopicClient)).thenReturn(2);

    setUpSource(left, "Foobar1");
    setUpSource(right, "Foobar2");

    when(leftSchemaKStream.getKeyField()).thenReturn(leftJoinField);
    when(leftSchemaKTable.getKeyField()).thenReturn(leftJoinField);
    when(rightSchemaKTable.getKeyField()).thenReturn(rightJoinField);
  }

  @Test
  public void shouldThrowIfLeftKeyFieldNotInLeftSchema() {
    // When:
    final IllegalArgumentException e = assertThrows(
        (IllegalArgumentException.class),
        () -> new JoinNode(
            nodeId,
            JoinType.LEFT,
            left,
            right,
            "won't find me",
            RIGHT_JOIN_FIELD_NAME,
            leftAlias,
            rightAlias,
            null,
            DataSourceType.KSTREAM,
            DataSourceType.KSTREAM)
    );

    // Then:
    assertThat(e.getMessage(), containsString("Invalid join field"));
  }

  @Test
  public void shouldThrowIfRightKeyFieldNotInRightSchema() {
    // When:
    final IllegalArgumentException e = assertThrows(
        (IllegalArgumentException.class),
        () -> new JoinNode(
            nodeId,
            JoinType.LEFT,
            left,
            right,
            LEFT_JOIN_FIELD_NAME,
            "won't find me",
            leftAlias,
            rightAlias,
            null,
            DataSourceType.KSTREAM,
            DataSourceType.KSTREAM)
    );

    // Then:
    assertThat(e.getMessage(), containsString("Invalid join field"));
  }

  @Test
  public void shouldReturnLeftJoinKeyAsKeyField() {
    // When:
    final JoinNode joinNode = new JoinNode(
        nodeId,
        JoinType.LEFT,
        left,
        right,
        LEFT_JOIN_FIELD_NAME,
        RIGHT_JOIN_FIELD_NAME,
        leftAlias,
        rightAlias,
        null,
        DataSourceType.KSTREAM,
        DataSourceType.KSTREAM);

    // Then:
    assertThat(joinNode.getKeyField().name(), is(Optional.of(LEFT_JOIN_FIELD_NAME)));
  }

  @Test
  public void shouldReturnJoinKeyNames() {
    // When:
    final JoinNode joinNode = new JoinNode(
        nodeId,
        JoinType.LEFT,
        left,
        right,
        LEFT_JOIN_FIELD_NAME,
        RIGHT_JOIN_FIELD_NAME,
        leftAlias,
        rightAlias,
        null,
        DataSourceType.KSTREAM,
        DataSourceType.KSTREAM);

    // Then:
    assertThat(joinNode.getLeftJoinFieldName(), is(LEFT_JOIN_FIELD_NAME));
    assertThat(joinNode.getRightJoinFieldName(), is(RIGHT_JOIN_FIELD_NAME));
  }

  @Test
  public void shouldReturnAliases() {
    // When:
    final JoinNode joinNode = new JoinNode(
        nodeId,
        JoinType.LEFT,
        left,
        right,
        LEFT_JOIN_FIELD_NAME,
        RIGHT_JOIN_FIELD_NAME,
        leftAlias,
        rightAlias,
        null,
        DataSourceType.KSTREAM,
        DataSourceType.KSTREAM);

    // Then:
    assertThat(joinNode.getLeftAlias(), is(leftAlias));
    assertThat(joinNode.getRightAlias(), is(rightAlias));
  }

  @Test
  public void shouldReturnJoinType() {
    // When:
    final JoinNode joinNode = new JoinNode(
        nodeId,
        JoinType.LEFT,
        left,
        right,
        LEFT_JOIN_FIELD_NAME,
        RIGHT_JOIN_FIELD_NAME,
        leftAlias,
        rightAlias,
        null,
        DataSourceType.KSTREAM,
        DataSourceType.KSTREAM);

    // Then:
    assertThat(joinNode.getJoinType(), is(JoinType.LEFT));
  }

  @Test
  public void shouldBuildSourceNode() {
    setupTopicClientExpectations(1, 1);
    buildJoin();
    final TopologyDescription.Source node = (TopologyDescription.Source) getNodeByName(
        builder.build(), SOURCE_NODE);
    final List<String> successors = node.successors().stream().map(TopologyDescription.Node::name)
        .collect(Collectors.toList());
    assertThat(node.predecessors(), equalTo(Collections.emptySet()));
    assertThat(successors, equalTo(Collections.singletonList(MAPVALUES_NODE)));
    assertThat(node.topicSet(), equalTo(ImmutableSet.of("test2")));
  }

  @Test
  public void shouldUseLegacyNameForReduceTopicIfOptimizationsOff() {
    setupTopicClientExpectations(1, 1);
    when(ksqlStreamBuilder.getKsqlConfig()).thenReturn(
        ksqlConfig.overrideBreakingConfigsWithOriginalValues(
            ImmutableMap.of(
                KsqlConfig.KSQL_USE_NAMED_INTERNAL_TOPICS,
                KsqlConfig.KSQL_USE_NAMED_INTERNAL_TOPICS_OFF)
        )
    );

    buildJoin();

    final Topology topology = builder.build();
    final TopologyDescription.Processor leftJoin
        = (TopologyDescription.Processor) getNodeByName(topology, "KSTREAM-LEFTJOIN-0000000015");
    assertThat(
        leftJoin.stores(),
        equalTo(Utils.mkSet("KSTREAM-AGGREGATE-STATE-STORE-0000000004")));
  }

  @Test
  public void shouldHaveLeftJoin() {
    setupTopicClientExpectations(1, 1);
    buildJoin();
    final Topology topology = builder.build();
    final TopologyDescription.Processor leftJoin
        = (TopologyDescription.Processor) getNodeByName(topology, "KSTREAM-LEFTJOIN-0000000014");
    final List<String> predecessors = leftJoin.predecessors().stream()
        .map(TopologyDescription.Node::name).collect(Collectors.toList());
    assertThat(leftJoin.stores(), equalTo(Utils.mkSet("KafkaTopic_Right-reduce")));
    assertThat(predecessors, equalTo(Collections.singletonList("KSTREAM-SOURCE-0000000013")));
  }

  @Test
  public void shouldThrowOnPartitionMismatch() {
    // Given:
    setupTopicClientExpectations(1, 2);

    // When:
    final Exception e = assertThrows(
        KsqlException.class,
        () -> buildJoin(
              "SELECT t1.col0, t2.col0, t2.col1 "
                  + "FROM test1 t1 LEFT JOIN test2 t2 ON t1.col0 = t2.col0;"
        )
    );

    // Then:
    assertThat(e.getMessage(), containsString(
        "Can't join TEST1 with TEST2 since the number of partitions don't match. TEST1 "
            + "partitions = 1; TEST2 partitions = 2. Please repartition either one so that the "
            + "number of partitions match."
    ));
  }

  @Test
  public void shouldHaveAllFieldsFromJoinedInputs() {
    setupTopicClientExpectations(1, 1);
    buildJoin();
    final MetaStore metaStore = MetaStoreFixture.getNewMetaStore(new InternalFunctionRegistry());
    final DataSource<?> source1
        = metaStore.getSource("TEST1");
    final DataSource<?> source2 = metaStore.getSource("TEST2");
    final Set<String> expected = source1.getSchema()
        .fields().stream()
        .map(field -> "T1." + field.name()).collect(Collectors.toSet());

    expected.addAll(source2.getSchema().fields().stream().map(field -> "T2." + field.name())
        .collect(Collectors.toSet()));
    final Set<String> fields = stream.getSchema().fields().stream().map(Field::name)
        .collect(Collectors.toSet());
    assertThat(fields, equalTo(expected));
  }

  @Test
  public void shouldPerformStreamToStreamLeftJoin() {
    // Given:
    setupStream(left, leftSchemaKStream, leftSchema);
    setupStream(right, rightSchemaKStream, rightSchema);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        JoinNode.JoinType.LEFT,
        left,
        right,
        LEFT_JOIN_FIELD_NAME,
        RIGHT_JOIN_FIELD_NAME,
        leftAlias,
        rightAlias,
        WITHIN_EXPRESSION,
        DataSourceType.KSTREAM,
        DataSourceType.KSTREAM);

    // When:
    joinNode.buildStream(ksqlStreamBuilder);

    // Then:
    verify(leftSchemaKStream).leftJoin(
        eq(rightSchemaKStream),
        eq(joinSchema),
        eq(leftJoinField),
        eq(WITHIN_EXPRESSION.joinWindow()),
        any(),
        any(),
        eq(CONTEXT_STACKER));
  }

  @Test
  public void shouldPerformStreamToStreamInnerJoin() {
    // Given:
    setupStream(left, leftSchemaKStream, leftSchema);
    setupStream(right, rightSchemaKStream, rightSchema);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        JoinNode.JoinType.INNER,
        left,
        right,
        LEFT_JOIN_FIELD_NAME,
        RIGHT_JOIN_FIELD_NAME,
        leftAlias,
        rightAlias,
        WITHIN_EXPRESSION,
        DataSourceType.KSTREAM,
        DataSourceType.KSTREAM);

    // When:
    joinNode.buildStream(ksqlStreamBuilder);

    // Then:
    verify(leftSchemaKStream).join(
        eq(rightSchemaKStream),
        eq(joinSchema),
        eq(leftJoinField),
        eq(WITHIN_EXPRESSION.joinWindow()),
        any(),
        any(),
        eq(CONTEXT_STACKER));
  }

  @Test
  public void shouldPerformStreamToStreamOuterJoin() {
    // Given:
    setupStream(left, leftSchemaKStream, leftSchema);
    setupStream(right, rightSchemaKStream, rightSchema);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        JoinNode.JoinType.OUTER,
        left,
        right,
        LEFT_JOIN_FIELD_NAME,
        RIGHT_JOIN_FIELD_NAME,
        leftAlias,
        rightAlias,
        WITHIN_EXPRESSION,
        DataSourceType.KSTREAM,
        DataSourceType.KSTREAM);

    // When:
    joinNode.buildStream(ksqlStreamBuilder);

    // Then:
    verify(leftSchemaKStream).outerJoin(
        eq(rightSchemaKStream),
        eq(joinSchema),
        eq(leftJoinField.withName(Optional.empty())),
        eq(WITHIN_EXPRESSION.joinWindow()),
        any(),
        any(),
        eq(CONTEXT_STACKER));
  }

  @Test
  public void shouldNotPerformStreamStreamJoinWithoutJoinWindow() {
    // Given:
    final JoinNode joinNode = new JoinNode(
        nodeId,
        JoinNode.JoinType.INNER,
        left,
        right,
        LEFT_JOIN_FIELD_NAME,
        RIGHT_JOIN_FIELD_NAME,
        leftAlias,
        rightAlias,
        null,
        DataSourceType.KSTREAM,
        DataSourceType.KSTREAM);

    // When:
    final KsqlException e = assertThrows(
        (KsqlException.class),
        () -> joinNode.buildStream(ksqlStreamBuilder)
    );

    // Then:
    assertThat(e.getMessage(), containsString(
        "Stream-Stream joins must have a WITHIN clause specified. None was provided."
    ));
  }

  @Test
  public void shouldNotPerformJoinIfInputPartitionsMisMatch() {
    // Given:
    when(left.getPartitions(mockKafkaTopicClient)).thenReturn(3);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        JoinNode.JoinType.OUTER,
        left,
        right,
        LEFT_JOIN_FIELD_NAME,
        RIGHT_JOIN_FIELD_NAME,
        leftAlias,
        rightAlias,
        WITHIN_EXPRESSION,
        DataSourceType.KSTREAM,
        DataSourceType.KSTREAM);

    // When:
    final KsqlException e = assertThrows(
        (KsqlException.class),
        () -> joinNode.buildStream(ksqlStreamBuilder)
    );

    // Then:
    assertThat(e.getMessage(), containsString(
        "Can't join Foobar1 with Foobar2 since the number of partitions don't match."
    ));
  }

  @Test
  public void shouldFailJoinIfTableCriteriaColumnIsNotKey() {
    // Given:
    setupStream(left, leftSchemaKStream, leftSchema);
    setupTable(right, rightSchemaKTable, rightSchema);

    final String rightCriteriaColumn =
        getNonKeyColumn(rightSchema, rightAlias, RIGHT_JOIN_FIELD_NAME);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        JoinNode.JoinType.LEFT,
        left,
        right,
        LEFT_JOIN_FIELD_NAME,
        rightCriteriaColumn,
        leftAlias,
        rightAlias,
        null,
        DataSourceType.KSTREAM,
        DataSourceType.KTABLE);

    // When:
    final KsqlException e = assertThrows(
        (KsqlException.class),
        () -> joinNode.buildStream(ksqlStreamBuilder)
    );

    // Then:
    assertThat(e.getMessage(), containsString(String.format(
        "Source table (%s) key column (%s) is not the column used in the join criteria (%s). "
            + "Only the table's key column or 'ROWKEY' is supported in the join criteria.",
        rightAlias,
        RIGHT_JOIN_FIELD_NAME,
        rightCriteriaColumn
    )));
  }

  @Test
  public void shouldFailJoinIfTableHasNoKeyAndJoinFieldIsNotRowKey() {
    // Given:
    setupStream(left, leftSchemaKStream, leftSchema);
    setupTable(right, rightSchemaKTable, rightSchema, NO_KEY_FIELD);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        JoinNode.JoinType.LEFT,
        left,
        right,
        LEFT_JOIN_FIELD_NAME,
        RIGHT_JOIN_FIELD_NAME,
        leftAlias,
        rightAlias,
        null,
        DataSource.DataSourceType.KSTREAM,
        DataSource.DataSourceType.KTABLE);

    // When:
    final KsqlException e = assertThrows(
        (KsqlException.class),
        () -> joinNode.buildStream(ksqlStreamBuilder)
    );

    // Then:
    assertThat(e.getMessage(), containsString(
        "Source table (" + rightAlias +") has no key column defined. "
            + "Only 'ROWKEY' is supported in the join criteria."
    ));
  }

  @Test
  public void shouldHandleJoinIfTableHasNoKeyAndJoinFieldIsRowKey() {
    // Given:
    setupStream(left, leftSchemaKStream, leftSchema);
    setupTable(right, rightSchemaKTable, rightSchema, NO_KEY_FIELD);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        JoinNode.JoinType.LEFT,
        left,
        right,
        LEFT_JOIN_FIELD_NAME,
        "right.ROWKEY",
        leftAlias,
        rightAlias,
        null,
        DataSource.DataSourceType.KSTREAM,
        DataSource.DataSourceType.KTABLE);

    // When:
    joinNode.buildStream(ksqlStreamBuilder);

    // Then:
    verify(leftSchemaKStream).leftJoin(
        eq(rightSchemaKTable),
        eq(joinSchema),
        eq(leftJoinField),
        any(),
        eq(CONTEXT_STACKER));
  }

  @Test
  public void shouldPerformStreamToTableLeftJoin() {
    // Given:
    setupStream(left, leftSchemaKStream, leftSchema);
    setupTable(right, rightSchemaKTable, rightSchema);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        JoinNode.JoinType.LEFT,
        left,
        right,
        LEFT_JOIN_FIELD_NAME,
        RIGHT_JOIN_FIELD_NAME,
        leftAlias,
        rightAlias,
        null,
        DataSourceType.KSTREAM,
        DataSourceType.KTABLE);

    // When:
    joinNode.buildStream(ksqlStreamBuilder);

    // Then:
    verify(leftSchemaKStream).leftJoin(
        eq(rightSchemaKTable),
        eq(joinSchema),
        eq(leftJoinField),
        any(),
        eq(CONTEXT_STACKER));
  }

  @Test
  public void shouldPerformStreamToTableInnerJoin() {
    // Given:
    setupStream(left, leftSchemaKStream, leftSchema);
    setupTable(right, rightSchemaKTable, rightSchema);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        JoinNode.JoinType.INNER,
        left,
        right,
        LEFT_JOIN_FIELD_NAME,
        RIGHT_JOIN_FIELD_NAME,
        leftAlias,
        rightAlias,
        null,
        DataSourceType.KSTREAM,
        DataSourceType.KTABLE);

    // When:
    joinNode.buildStream(ksqlStreamBuilder);

    // Then:
    verify(leftSchemaKStream).join(
        eq(rightSchemaKTable),
        eq(joinSchema),
        eq(leftJoinField),
        any(),
        eq(CONTEXT_STACKER));
  }

  @Test
  public void shouldNotAllowStreamToTableOuterJoin() {
    // Given:
    setupStream(left, leftSchemaKStream, leftSchema);
    setupTable(right, rightSchemaKTable, rightSchema);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        JoinNode.JoinType.OUTER,
        left,
        right,
        LEFT_JOIN_FIELD_NAME,
        RIGHT_JOIN_FIELD_NAME,
        leftAlias,
        rightAlias,
        null,
        DataSourceType.KSTREAM,
        DataSourceType.KTABLE);

    // When:
    final KsqlException e = assertThrows(
        (KsqlException.class),
        () -> joinNode.buildStream(ksqlStreamBuilder)
    );

    // Then:
    assertThat(e.getMessage(), containsString(
        "Full outer joins between streams and tables are not supported."
    ));
  }

  @Test
  public void shouldNotPerformStreamToTableJoinIfJoinWindowIsSpecified() {
    // Given:
    final WithinExpression withinExpression = new WithinExpression(10, TimeUnit.SECONDS);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        JoinNode.JoinType.OUTER,
        left,
        right,
        LEFT_JOIN_FIELD_NAME,
        RIGHT_JOIN_FIELD_NAME,
        leftAlias,
        rightAlias,
        withinExpression,
        DataSourceType.KSTREAM,
        DataSourceType.KTABLE);

    // When:
    final KsqlException e = assertThrows(
        (KsqlException.class),
        () -> joinNode.buildStream(ksqlStreamBuilder)
    );

    // Then:
    assertThat(e.getMessage(), containsString(
        "A window definition was provided for a Stream-Table join."
    ));
  }

  @Test
  public void shouldFailTableTableJoinIfLeftCriteriaColumnIsNotKey() {
    // Given:
    setupTable(left, leftSchemaKTable, leftSchema);
    setupTable(right, rightSchemaKTable, rightSchema);

    final String leftCriteriaColumn = getNonKeyColumn(leftSchema, leftAlias, LEFT_JOIN_FIELD_NAME);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        JoinNode.JoinType.LEFT,
        left,
        right,
        leftCriteriaColumn,
        RIGHT_JOIN_FIELD_NAME,
        leftAlias,
        rightAlias,
        null,
        DataSourceType.KTABLE,
        DataSourceType.KTABLE);

    // When:
    final KsqlException e = assertThrows(
        (KsqlException.class),
        () -> joinNode.buildStream(ksqlStreamBuilder)
    );

    // Then:
    assertThat(e.getMessage(), containsString(String.format(
        "Source table (%s) key column (%s) is not the column used in the join criteria (%s). "
            + "Only the table's key column or 'ROWKEY' is supported in the join criteria.",
        leftAlias,
        LEFT_JOIN_FIELD_NAME,
        leftCriteriaColumn
    )));
  }

  @Test
  public void shouldFailTableTableJoinIfRightCriteriaColumnIsNotKey() {
    // Given:
    setupTable(left, leftSchemaKTable, leftSchema);
    setupTable(right, rightSchemaKTable, rightSchema);

    final String rightCriteriaColumn =
        getNonKeyColumn(rightSchema, rightAlias, RIGHT_JOIN_FIELD_NAME);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        JoinNode.JoinType.LEFT,
        left,
        right,
        LEFT_JOIN_FIELD_NAME,
        rightCriteriaColumn,
        leftAlias,
        rightAlias,
        null,
        DataSourceType.KTABLE,
        DataSourceType.KTABLE);

    // When:
    final KsqlException e = assertThrows(
        (KsqlException.class),
        () -> joinNode.buildStream(ksqlStreamBuilder)
    );

    // Then:
    assertThat(e.getMessage(), containsString(String.format(
        "Source table (%s) key column (%s) is not the column used in the join criteria (%s). "
            + "Only the table's key column or 'ROWKEY' is supported in the join criteria.",
        rightAlias,
        RIGHT_JOIN_FIELD_NAME,
        rightCriteriaColumn
    )));
  }

  @Test
  public void shouldPerformTableToTableInnerJoin() {
    // Given:
    setupTable(left, leftSchemaKTable, leftSchema);
    setupTable(right, rightSchemaKTable, rightSchema);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        JoinNode.JoinType.INNER,
        left,
        right,
        LEFT_JOIN_FIELD_NAME,
        RIGHT_JOIN_FIELD_NAME,
        leftAlias,
        rightAlias,
        null,
        DataSourceType.KTABLE,
        DataSourceType.KTABLE);

    // When:
    joinNode.buildStream(ksqlStreamBuilder);

    // Then:
    verify(leftSchemaKTable).join(
        eq(rightSchemaKTable),
        eq(joinSchema),
        eq(leftJoinField),
        eq(CONTEXT_STACKER));
  }

  @Test
  public void shouldPerformTableToTableLeftJoin() {
    // Given:
    setupTable(left, leftSchemaKTable, leftSchema);
    setupTable(right, rightSchemaKTable, rightSchema);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        JoinNode.JoinType.LEFT,
        left,
        right,
        LEFT_JOIN_FIELD_NAME,
        RIGHT_JOIN_FIELD_NAME,
        leftAlias,
        rightAlias,
        null,
        DataSourceType.KTABLE,
        DataSourceType.KTABLE
    );

    // When:
    joinNode.buildStream(ksqlStreamBuilder);

    // Then:
    verify(leftSchemaKTable).leftJoin(
        eq(rightSchemaKTable),
        eq(joinSchema),
        eq(leftJoinField),
        eq(CONTEXT_STACKER));
  }

  @Test
  public void shouldPerformTableToTableOuterJoin() {
    // Given:
    setupTable(left, leftSchemaKTable, leftSchema);
    setupTable(right, rightSchemaKTable, rightSchema);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        JoinNode.JoinType.OUTER,
        left,
        right,
        LEFT_JOIN_FIELD_NAME,
        RIGHT_JOIN_FIELD_NAME,
        leftAlias,
        rightAlias,
        null,
        DataSourceType.KTABLE,
        DataSourceType.KTABLE
    );

    // When:
    joinNode.buildStream(ksqlStreamBuilder);

    // Then:
    verify(leftSchemaKTable).outerJoin(
        eq(rightSchemaKTable),
        eq(joinSchema),
        eq(leftJoinField.withName(Optional.empty())),
        eq(CONTEXT_STACKER));
  }

  @Test
  public void shouldNotPerformTableToTableJoinIfJoinWindowIsSpecified() {
    // Given:
    final WithinExpression withinExpression = new WithinExpression(10, TimeUnit.SECONDS);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        JoinNode.JoinType.OUTER,
        left,
        right,
        LEFT_JOIN_FIELD_NAME,
        RIGHT_JOIN_FIELD_NAME,
        leftAlias,
        rightAlias,
        withinExpression,
        DataSourceType.KTABLE,
        DataSourceType.KTABLE);

    // When:
    final KsqlException e = assertThrows(
        (KsqlException.class),
        () -> joinNode.buildStream(ksqlStreamBuilder)
    );

    // Then:
    assertThat(e.getMessage(), containsString(
        "A window definition was provided for a Table-Table join."
    ));
  }

  @Test
  public void shouldHaveFullyQualifiedJoinSchema() {
    // When:
    final JoinNode joinNode = new JoinNode(
        nodeId,
        JoinNode.JoinType.OUTER,
        left,
        right,
        LEFT_JOIN_FIELD_NAME,
        RIGHT_JOIN_FIELD_NAME,
        leftAlias,
        rightAlias,
        null,
        DataSourceType.KTABLE,
        DataSourceType.KTABLE
    );

    // When:
    assertThat(joinNode.getSchema(), is(KsqlSchema.of(
        SchemaBuilder.struct()
            .field(leftAlias + ".ROWTIME", SchemaBuilder.OPTIONAL_INT64_SCHEMA)
            .field(leftAlias + ".ROWKEY", SchemaBuilder.OPTIONAL_INT64_SCHEMA)
            .field(leftAlias + ".COL0", SchemaBuilder.OPTIONAL_INT64_SCHEMA)
            .field(leftAlias + ".COL1", SchemaBuilder.OPTIONAL_STRING_SCHEMA)
            .field(rightAlias + ".ROWTIME", SchemaBuilder.OPTIONAL_INT64_SCHEMA)
            .field(rightAlias + ".ROWKEY", SchemaBuilder.OPTIONAL_INT64_SCHEMA)
            .field(rightAlias + ".COL0", SchemaBuilder.OPTIONAL_INT64_SCHEMA)
            .field(rightAlias + ".COL1", SchemaBuilder.OPTIONAL_STRING_SCHEMA)
            .build()
    )));
  }

  @Test
  public void shouldSelectLeftKeyField() {
    // Given:
    setupStream(left, leftSchemaKStream, leftSchema);
    setupStream(right, rightSchemaKStream, rightSchema);

    final JoinNode joinNode = new JoinNode(
        nodeId,
        JoinNode.JoinType.OUTER,
        left,
        right,
        LEFT_JOIN_FIELD_NAME,
        RIGHT_JOIN_FIELD_NAME,
        leftAlias,
        rightAlias,
        WITHIN_EXPRESSION,
        DataSourceType.KSTREAM,
        DataSourceType.KSTREAM
    );

    // When:
    joinNode.buildStream(ksqlStreamBuilder);

    // Then:
    verify(leftSchemaKStream).selectKey(
        eq(LEFT_JOIN_FIELD_NAME),
        anyBoolean(),
        any()
    );
  }

  @SuppressWarnings("unchecked")
  private void setupTable(
      final DataSourceNode node,
      final SchemaKTable table,
      final KsqlSchema schema
  ) {
    when(node.buildStream(ksqlStreamBuilder)).thenReturn(table);
    when(table.getSchema()).thenReturn(schema);
  }

  private void setupTable(
      final DataSourceNode node,
      final SchemaKTable table,
      final KsqlSchema schema,
      final Optional<String> keyFieldName
  ) {
    setupTable(node, table, schema);

    final Optional<Field> keyField = keyFieldName
        .map(key -> schema.findField(key).orElseThrow(AssertionError::new));

    when(table.getKeyField()).thenReturn(KeyField.of(keyFieldName, keyField));
  }

  @SuppressWarnings("unchecked")
  private void setupStream(
      final DataSourceNode node,
      final SchemaKStream stream,
      final KsqlSchema schema
  ) {
    when(node.buildStream(ksqlStreamBuilder)).thenReturn(stream);
    when(stream.getSchema()).thenReturn(schema);
    when(stream.selectKey(any(), eq(true), any())).thenReturn(stream);
  }

  @SuppressWarnings("Duplicates")
  private KsqlSchema joinSchema() {
    final SchemaBuilder schemaBuilder = SchemaBuilder.struct();

    for (final Field field : leftSchema.fields()) {
      schemaBuilder.field(field.name(), field.schema());
    }

    for (final Field field : rightSchema.fields()) {
      schemaBuilder.field(field.name(), field.schema());
    }

    return KsqlSchema.of(schemaBuilder.build());
  }

  private void buildJoin() {
    buildJoin(
        "SELECT t1.col1, t2.col1, t2.col4, col5, t2.col2 "
            + "FROM test1 t1 LEFT JOIN test2 t2 "
            + "ON t1.col1 = t2.col0;"
    );
  }

  private void buildJoin(final String queryString) {
    buildJoinNode(queryString);
    stream = joinNode.buildStream(ksqlStreamBuilder);
  }

  private void buildJoinNode(final String queryString) {
    final MetaStore metaStore = MetaStoreFixture.getNewMetaStore(new InternalFunctionRegistry());

    final KsqlBareOutputNode planNode =
        (KsqlBareOutputNode) AnalysisTestUtil.buildLogicalPlan(ksqlConfig, queryString, metaStore);

    joinNode = (JoinNode) ((ProjectNode) planNode.getSource()).getSource();
  }

  private void setupTopicClientExpectations(final int streamPartitions, final int tablePartitions) {
    final Node node = new Node(0, "localhost", 9091);

    final List<TopicPartitionInfo> streamPartitionInfoList =
        IntStream.range(0, streamPartitions)
            .mapToObj(
                p -> new TopicPartitionInfo(p, node, Collections.emptyList(),
                    Collections.emptyList()))
            .collect(Collectors.toList());

    when(mockKafkaTopicClient.describeTopic("test1"))
        .thenReturn(new TopicDescription("test1", false, streamPartitionInfoList));

    final List<TopicPartitionInfo> tablePartitionInfoList =
        IntStream.range(0, tablePartitions)
            .mapToObj(
                p -> new TopicPartitionInfo(p, node, Collections.emptyList(),
                    Collections.emptyList()))
            .collect(Collectors.toList());

    when(mockKafkaTopicClient.describeTopic("test2"))
        .thenReturn(new TopicDescription("test2", false, tablePartitionInfoList));
  }

  private static Optional<String> getColumn(final KsqlSchema schema, final Predicate<String> filter) {
    return schema.fields().stream()
        .map(Field::name)
        .filter(filter)
        .findFirst();
  }

  private static String getNonKeyColumn(
      final KsqlSchema schema,
      final String alias,
      final String keyName
  ) {
    final String prefix = alias + ".";
    final ImmutableList<String> blackList = ImmutableList.of(
        prefix + SchemaUtil.ROWKEY_NAME,
        prefix + SchemaUtil.ROWTIME_NAME,
        keyName
    );

    final String column =
        getColumn(schema, s -> !blackList.contains(s))
            .orElseThrow(AssertionError::new);

    final Field field = schema.findField(column).get();
    return field.name();
  }

  @SuppressWarnings("unchecked")
  private static void setUpSource(final DataSourceNode node, final String name) {
    final DataSource<?> dataSource = mock(DataSource.class);
    when(dataSource.getName()).thenReturn(name);
    when(node.getDataSource()).thenReturn((DataSource)dataSource);
    final KsqlSchema schema = node.getSchema();
    when(dataSource.getSchema()).thenReturn(schema);

    final KsqlTopic ksqlTopic = mock(KsqlTopic.class);
    when(dataSource.getKsqlTopic()).thenReturn(ksqlTopic);

    final KsqlSerdeFactory valueSerdeFactory = mock(KsqlSerdeFactory.class);
    when(ksqlTopic.getValueSerdeFactory()).thenReturn(valueSerdeFactory);
  }

  private static KsqlSchema createSchema(final String alias) {
    final SchemaBuilder schemaBuilder = SchemaBuilder.struct()
        .field(alias + ".ROWTIME", SchemaBuilder.OPTIONAL_INT64_SCHEMA)
        .field(alias + ".ROWKEY", SchemaBuilder.OPTIONAL_INT64_SCHEMA)
        .field(alias + ".COL0", SchemaBuilder.OPTIONAL_INT64_SCHEMA)
        .field(alias + ".COL1", SchemaBuilder.OPTIONAL_STRING_SCHEMA);
    return KsqlSchema.of(schemaBuilder.build());
  }
}