/*
/* Copyright 2018-2025 contributors to the OpenLineage project
/* SPDX-License-Identifier: Apache-2.0
*/
package io.openlineage.spark34.agent.lifecycle.plan.column;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.openlineage.client.OpenLineage;
import io.openlineage.client.OpenLineage.InputDataset;
import io.openlineage.spark.agent.Versions;
import io.openlineage.spark.agent.util.ScalaConversionUtils;
import io.openlineage.spark.api.OpenLineageContext;
import io.openlineage.spark.api.SparkOpenLineageConfig;
import java.util.Arrays;
import java.util.Collections;
import org.apache.spark.SparkContext;
import org.apache.spark.scheduler.SparkListenerEvent;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.analysis.ResolvedTable;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.plans.logical.CreateTable;
import org.apache.spark.sql.catalyst.plans.logical.CreateTableAsSelect;
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan;
import org.apache.spark.sql.catalyst.plans.logical.ReplaceTable;
import org.apache.spark.sql.catalyst.plans.logical.ReplaceTableAsSelect;
import org.apache.spark.sql.catalyst.plans.logical.TableSpec;
import org.apache.spark.sql.connector.catalog.Identifier;
import org.apache.spark.sql.connector.catalog.Table;
import org.apache.spark.sql.connector.catalog.TableCatalog;
import org.apache.spark.sql.connector.expressions.Transform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scala.Option;

class CreateReplaceInputDatasetBuilderTest {
  OpenLineageContext openLineageContext =
      OpenLineageContext.builder()
          .sparkSession(mock(SparkSession.class))
          .sparkContext(mock(SparkContext.class))
          .openLineage(new OpenLineage(Versions.OPEN_LINEAGE_PRODUCER_URI))
          .meterRegistry(new SimpleMeterRegistry())
          .openLineageConfig(new SparkOpenLineageConfig())
          .build();
  CreateReplaceInputDatasetBuilder builder =
      new CreateReplaceInputDatasetBuilder(openLineageContext);

  TableCatalog catalog = mock(TableCatalog.class);

  ResolvedTable namePlan =
      new ResolvedTable(
          catalog,
          mock(Identifier.class),
          mock(Table.class),
          ScalaConversionUtils.<Attribute>asScalaSeqEmpty());

  TableSpec tableSpec = mock(TableSpec.class);

  @BeforeEach
  public void setup() {}

  @Test
  void testIsDefined() {
    assertTrue(builder.isDefinedAtLogicalPlan(mock(CreateTableAsSelect.class)));
    assertTrue(builder.isDefinedAtLogicalPlan(mock(ReplaceTableAsSelect.class)));
    assertTrue(builder.isDefinedAtLogicalPlan(mock(ReplaceTable.class)));
    assertTrue(builder.isDefinedAtLogicalPlan(mock(CreateTable.class)));
    assertFalse(builder.isDefinedAtLogicalPlan(mock(LogicalPlan.class)));
  }

  @Test
  void testIsDefinedWhenNodeHasChildren() {
    CreateTableAsSelect node = mock(CreateTableAsSelect.class);
    when(node.children())
        .thenReturn(ScalaConversionUtils.fromList(Arrays.asList(mock(LogicalPlan.class))).toSeq());

    assertFalse(builder.isDefinedAtLogicalPlan(node));
  }

  @Test
  void testApply() {
    InputDataset inputDataset = mock(InputDataset.class);
    LogicalPlan query = mock(LogicalPlan.class);
    CreateTableAsSelect node =
        new CreateTableAsSelect(
            namePlan,
            ScalaConversionUtils.<Transform>asScalaSeqEmpty(),
            query,
            tableSpec,
            null,
            false,
            Option.<LogicalPlan>empty());
    when(query.collect(any()))
        .thenReturn(
            ScalaConversionUtils.fromList(
                    Arrays.asList((Object) Collections.singletonList(inputDataset)))
                .toSeq());

    assertThat(builder.apply(mock(SparkListenerEvent.class), node)).containsExactly(inputDataset);
  }
}
