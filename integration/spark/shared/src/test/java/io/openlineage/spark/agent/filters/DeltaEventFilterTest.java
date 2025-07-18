/*
/* Copyright 2018-2025 contributors to the OpenLineage project
/* SPDX-License-Identifier: Apache-2.0
*/

package io.openlineage.spark.agent.filters;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import io.openlineage.spark.agent.util.ScalaConversionUtils;
import io.openlineage.spark.api.OpenLineageContext;
import java.util.Arrays;
import java.util.Collections;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.apache.spark.SparkConf;
import org.apache.spark.SparkContext;
import org.apache.spark.scheduler.SparkListenerEvent;
import org.apache.spark.scheduler.SparkListenerJobEnd;
import org.apache.spark.scheduler.SparkListenerJobStart;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.plans.logical.Filter;
import org.apache.spark.sql.catalyst.plans.logical.LocalRelation;
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan;
import org.apache.spark.sql.catalyst.plans.logical.Project;
import org.apache.spark.sql.catalyst.plans.logical.SerializeFromObject;
import org.apache.spark.sql.execution.LogicalRDD;
import org.apache.spark.sql.execution.QueryExecution;
import org.apache.spark.sql.execution.ui.SparkListenerSQLExecutionEnd;
import org.apache.spark.sql.execution.ui.SparkListenerSQLExecutionStart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scala.collection.immutable.Seq;

@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class DeltaEventFilterTest {

  public static final String SPARK_SQL_EXTENSIONS = "spark.sql.extensions";
  OpenLineageContext context = mock(OpenLineageContext.class);
  DeltaEventFilter filter = new DeltaEventFilter(context);
  SparkSession sparkSession = mock(SparkSession.class);
  SparkContext sparkContext = mock(SparkContext.class);
  SparkConf sparkConf = mock(SparkConf.class);
  SparkListenerEvent sparkListenerEvent = mock(SparkListenerEvent.class);
  QueryExecution queryExecution = mock(QueryExecution.class);

  @BeforeEach
  public void setup() {
    when(sparkSession.sparkContext()).thenReturn(sparkContext);
    when(sparkContext.conf()).thenReturn(sparkConf);
    when(context.getQueryExecution()).thenReturn(Optional.of(queryExecution));
  }

  @Test
  void testNotDeltaIsNotDisabled() {
    try (MockedStatic mocked = mockStatic(SparkSession.class)) {
      when(SparkSession.active()).thenReturn(sparkSession);
      when(sparkConf.get(SPARK_SQL_EXTENSIONS)).thenReturn("non-delta-extension");

      assertFalse(filter.isDisabled(sparkListenerEvent));
    }
  }

  @Test
  void testIsNotDisabledWhenSparkConfEntryMissing() {
    try (MockedStatic mocked = mockStatic(SparkSession.class)) {
      when(SparkSession.active()).thenReturn(sparkSession);
      when(sparkConf.get("spark.sql.extensions"))
          .thenThrow(new NoSuchElementException("spark.sql.extensions"));

      assertFalse(filter.isDisabled(sparkListenerEvent));
    }
  }

  @Test
  void testIsDisabledWhenQueryExecutionIsNull() {
    try (MockedStatic mocked = mockStatic(SparkSession.class)) {
      when(SparkSession.active()).thenReturn(sparkSession);
      when(sparkConf.get("spark.sql.extensions", ""))
          .thenReturn("io.delta.sql.DeltaSparkSessionExtension");
      when(context.getQueryExecution()).thenReturn(Optional.empty());

      assertFalse(filter.isDisabled(sparkListenerEvent));
    }
  }

  @Test
  void testDisabledForLocalRelationOnly() {
    try (MockedStatic mocked = mockStatic(SparkSession.class)) {
      LocalRelation localRelation = mock(LocalRelation.class);
      when(localRelation.children()).thenReturn(ScalaConversionUtils.asScalaSeqEmpty());
      when(SparkSession.active()).thenReturn(sparkSession);
      when(sparkConf.get("spark.sql.extensions", ""))
          .thenReturn("io.delta.sql.DeltaSparkSessionExtension");
      when(queryExecution.optimizedPlan()).thenReturn(localRelation);

      assertTrue(filter.isDisabled(sparkListenerEvent));
    }
  }

  @Test
  void testDisabledForFilterRoot() {
    try (MockedStatic mocked = mockStatic(SparkSession.class)) {
      when(SparkSession.active()).thenReturn(sparkSession);
      when(sparkConf.get("spark.sql.extensions", ""))
          .thenReturn("io.delta.sql.DeltaSparkSessionExtension");
      when(queryExecution.optimizedPlan()).thenReturn(mock(Filter.class));

      assertTrue(filter.isDisabled(sparkListenerEvent));
    }
  }

  @Test
  void testDisabledOnJobStartAndEnd() {
    try (MockedStatic mocked = mockStatic(SparkSession.class)) {
      when(SparkSession.active()).thenReturn(sparkSession);
      when(sparkConf.get("spark.sql.extensions", ""))
          .thenReturn("io.delta.sql.DeltaSparkSessionExtension");
      when(queryExecution.optimizedPlan()).thenReturn(mock(Filter.class));

      assertTrue(filter.isDisabled(mock(SparkListenerJobStart.class)));
      assertTrue(filter.isDisabled(mock(SparkListenerJobEnd.class)));
    }
  }

  @Test
  void testDisabledForLogicalRDDWithDeltaColumns() {
    try (MockedStatic mocked = mockStatic(SparkSession.class)) {
      LogicalPlan logicalPlan = mock(LogicalPlan.class);
      LogicalRDD logicalRDD = mock(LogicalRDD.class);
      when(logicalPlan.collectLeaves())
          .thenReturn(ScalaConversionUtils.fromList(Collections.singletonList(logicalRDD)));
      Seq<Attribute> outputs =
          ScalaConversionUtils.fromList(
              Arrays.asList(
                  attributeWithName("txn"),
                  attributeWithName("add"),
                  attributeWithName("remove"),
                  attributeWithName("metaData"),
                  attributeWithName("cdc"),
                  attributeWithName("protocol"),
                  attributeWithName("commitInfo")));
      when(logicalRDD.output()).thenReturn(outputs);

      when(SparkSession.active()).thenReturn(sparkSession);
      when(sparkConf.get("spark.sql.extensions", ""))
          .thenReturn("io.delta.sql.DeltaSparkSessionExtension");
      when(queryExecution.optimizedPlan()).thenReturn(logicalPlan);

      assertTrue(filter.isDisabled(sparkListenerEvent));
    }
  }

  @Test
  void testDisabledForDeltaLogFileProject() {
    try (MockedStatic mocked = mockStatic(SparkSession.class)) {
      LogicalPlan project = mock(Project.class);
      Seq<Attribute> attributeSeq =
          ScalaConversionUtils.fromList(
              Arrays.asList(
                  attributeWithName("protocol"),
                  attributeWithName("metaData"),
                  attributeWithName("action_sort_column")));
      when(project.output()).thenReturn(attributeSeq);
      when(project.collectLeaves()).thenReturn(ScalaConversionUtils.asScalaSeqEmpty());

      when(SparkSession.active()).thenReturn(sparkSession);
      when(sparkConf.get("spark.sql.extensions", ""))
          .thenReturn("io.delta.sql.DeltaSparkSessionExtension");
      when(queryExecution.optimizedPlan()).thenReturn(project);

      assertTrue(filter.isDisabled(sparkListenerEvent));
    }
  }

  @Test
  void testDisabledForSerializeFromObject() {
    try (MockedStatic mocked = mockStatic(SparkSession.class)) {
      LocalRelation localRelation = mock(LocalRelation.class);
      when(localRelation.children()).thenReturn(ScalaConversionUtils.asScalaSeqEmpty());
      when(SparkSession.active()).thenReturn(sparkSession);
      when(sparkConf.get("spark.sql.extensions", ""))
          .thenReturn("io.delta.sql.DeltaSparkSessionExtension");
      SerializeFromObject serializeFromObject = mock(SerializeFromObject.class);
      when(serializeFromObject.collectLeaves()).thenReturn(ScalaConversionUtils.asScalaSeqEmpty());
      when(queryExecution.optimizedPlan()).thenReturn(serializeFromObject);

      assertTrue(filter.isDisabled(sparkListenerEvent));
    }
  }

  @Test
  void testDisabledForStagedDeltaTable() {
    try (MockedStatic mocked = mockStatic(SparkSession.class)) {
      when(SparkSession.active()).thenReturn(sparkSession);
      when(sparkConf.get("spark.sql.extensions", ""))
          .thenReturn("io.delta.sql.DeltaSparkSessionExtension");
      when(queryExecution.optimizedPlan()).thenReturn(null); // Placeholder for actual plan

      // Simulate a staged delta table
      SparkListenerSQLExecutionStart sparkListenerSQLExecutionStart =
          mock(SparkListenerSQLExecutionStart.class, RETURNS_DEEP_STUBS);
      when(sparkListenerSQLExecutionStart.sparkPlanInfo().simpleString())
          .thenReturn(
              "AppendDataExecV1 org.apache.spark.sql.delta.catalog.DeltaCatalog$StagedDeltaTableV2...");
      assertTrue(filter.isDisabled(sparkListenerSQLExecutionStart));

      // Simulate a staged delta table
      SparkListenerSQLExecutionEnd sparkListenerSQLExecutionEnd =
          mock(SparkListenerSQLExecutionEnd.class, RETURNS_DEEP_STUBS);
      when(sparkListenerSQLExecutionEnd.qe().executedPlan().toString())
          .thenReturn(
              "AppendDataExecV1 org.apache.spark.sql.delta.catalog.DeltaCatalog$StagedDeltaTableV2...");
      assertTrue(filter.isDisabled(sparkListenerSQLExecutionStart));
    }
  }

  private Attribute attributeWithName(String name) {
    Attribute attr = mock(Attribute.class);
    when(attr.name()).thenReturn(name);
    return attr;
  }
}
