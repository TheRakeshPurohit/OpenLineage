/*
/* Copyright 2018-2025 contributors to the OpenLineage project
/* SPDX-License-Identifier: Apache-2.0
*/

package io.openlineage.spark3.agent.lifecycle.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import io.openlineage.client.OpenLineage;
import io.openlineage.client.OpenLineage.DatasetFacetsBuilder;
import io.openlineage.client.dataset.DatasetCompositeFacetsBuilder;
import io.openlineage.spark.api.DatasetFactory;
import io.openlineage.spark.api.OpenLineageContext;
import io.openlineage.spark3.agent.lifecycle.plan.catalog.CatalogUtils3;
import io.openlineage.spark3.agent.utils.DataSourceV2RelationDatasetExtractor;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.apache.spark.sql.catalyst.plans.logical.DeleteFromTable;
import org.apache.spark.sql.catalyst.plans.logical.InsertIntoStatement;
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan;
import org.apache.spark.sql.catalyst.plans.logical.MergeIntoTable;
import org.apache.spark.sql.catalyst.plans.logical.OverwriteByExpression;
import org.apache.spark.sql.catalyst.plans.logical.OverwritePartitionsDynamic;
import org.apache.spark.sql.catalyst.plans.logical.ReplaceData;
import org.apache.spark.sql.catalyst.plans.logical.UpdateTable;
import org.apache.spark.sql.connector.catalog.Identifier;
import org.apache.spark.sql.connector.catalog.Table;
import org.apache.spark.sql.connector.catalog.TableCatalog;
import org.apache.spark.sql.execution.datasources.v2.DataSourceV2Relation;
import org.apache.spark.sql.execution.ui.SparkListenerSQLExecutionEnd;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import scala.Option;

class TableContentChangeDatasetBuilderTest {

  OpenLineageContext openLineageContext = mock(OpenLineageContext.class);
  DataSourceV2Relation dataSourceV2Relation = mock(DataSourceV2Relation.class);
  Identifier identifier = mock(Identifier.class);
  TableCatalog tableCatalog = mock(TableCatalog.class);
  Table table = mock(Table.class);
  Map<String, String> tableProperties = new HashMap<>();
  OpenLineage openLineage;
  TableContentChangeDatasetBuilder builder;
  DatasetCompositeFacetsBuilder compositeFacetsBuilder = mock(DatasetCompositeFacetsBuilder.class);

  @BeforeEach
  @SneakyThrows
  public void setUp() {
    openLineage = mock(OpenLineage.class);
    when(openLineage.newDatasetFacetsBuilder()).thenReturn(new DatasetFacetsBuilder());
    when(openLineageContext.getOpenLineage()).thenReturn(openLineage);
    DatasetFactory datasetFactory = mock(DatasetFactory.class);
    when(datasetFactory.createCompositeFacetBuilder()).thenReturn(compositeFacetsBuilder);
    builder = new TableContentChangeDatasetBuilder(openLineageContext, datasetFactory);
  }

  @Test
  void testApplyForOverwriteByExpression() {
    OverwriteByExpression logicalPlan = mock(OverwriteByExpression.class);
    when(logicalPlan.table()).thenReturn(dataSourceV2Relation);
    verify(
        logicalPlan, OpenLineage.LifecycleStateChangeDatasetFacet.LifecycleStateChange.OVERWRITE);
  }

  @Test
  void testApplyForOverwritePartitionsDynamic() {
    OverwritePartitionsDynamic logicalPlan = mock(OverwritePartitionsDynamic.class);
    when(logicalPlan.table()).thenReturn(dataSourceV2Relation);
    verify(
        logicalPlan, OpenLineage.LifecycleStateChangeDatasetFacet.LifecycleStateChange.OVERWRITE);
  }

  @Test
  void testApplyForInsertIntoStatement() {
    InsertIntoStatement logicalPlan = mock(InsertIntoStatement.class);
    when(logicalPlan.table()).thenReturn(dataSourceV2Relation);
    when(logicalPlan.overwrite()).thenReturn(true);
    verify(
        logicalPlan, OpenLineage.LifecycleStateChangeDatasetFacet.LifecycleStateChange.OVERWRITE);
  }

  @Test
  void testApplyForDeleteFromTable() {
    DeleteFromTable logicalPlan = mock(DeleteFromTable.class);
    when(logicalPlan.table()).thenReturn(dataSourceV2Relation);
    verify(logicalPlan, null);
  }

  @Test
  void testApplyForUpdateTable() {
    UpdateTable logicalPlan = mock(UpdateTable.class);
    when(logicalPlan.table()).thenReturn(dataSourceV2Relation);
    verify(logicalPlan, null);
  }

  @Test
  void testApplyForReplaceData() {
    ReplaceData logicalPlan = mock(ReplaceData.class);
    when(logicalPlan.table()).thenReturn(dataSourceV2Relation);
    verify(logicalPlan, null);
  }

  @Test
  void testApplyForMergeIntoTable() {
    MergeIntoTable logicalPlan = mock(MergeIntoTable.class);
    when(logicalPlan.targetTable()).thenReturn(dataSourceV2Relation);
    verify(logicalPlan, null);
  }

  @Test
  void testApplyForInsertIntoStatementWithOverwriteDisabled() {
    InsertIntoStatement logicalPlan = mock(InsertIntoStatement.class);
    when(logicalPlan.table()).thenReturn(dataSourceV2Relation);
    when(logicalPlan.overwrite()).thenReturn(false);
    verify(logicalPlan, null);
  }

  @Test
  void testIsDefinedAtLogicalPlan() {
    assertTrue(builder.isDefinedAtLogicalPlan(mock(OverwriteByExpression.class)));
    assertTrue(builder.isDefinedAtLogicalPlan(mock(OverwritePartitionsDynamic.class)));
    assertTrue(builder.isDefinedAtLogicalPlan(mock(InsertIntoStatement.class)));
    assertTrue(builder.isDefinedAtLogicalPlan(mock(DeleteFromTable.class)));
    assertTrue(builder.isDefinedAtLogicalPlan(mock(UpdateTable.class)));
    assertTrue(builder.isDefinedAtLogicalPlan(mock(ReplaceData.class)));
    assertFalse(builder.isDefinedAtLogicalPlan(mock(LogicalPlan.class)));
  }

  private void verify(
      LogicalPlan logicalPlan,
      OpenLineage.LifecycleStateChangeDatasetFacet.LifecycleStateChange lifecycleStateChange) {
    try (MockedStatic mockedPlanUtils3 = mockStatic(DataSourceV2RelationDatasetExtractor.class)) {
      try (MockedStatic mockedVersions = mockStatic(CatalogUtils3.class)) {
        OpenLineage.Dataset dataset = mock(OpenLineage.OutputDataset.class);
        DatasetFacetsBuilder datasetFacetsBuilder = mock(DatasetFacetsBuilder.class);
        when(compositeFacetsBuilder.getFacets()).thenReturn(datasetFacetsBuilder);
        OpenLineage.LifecycleStateChangeDatasetFacet lifecycleStateChangeDatasetFacet =
            mock(OpenLineage.LifecycleStateChangeDatasetFacet.class);
        OpenLineage.DatasetVersionDatasetFacet datasetVersionDatasetFacet =
            mock(OpenLineage.DatasetVersionDatasetFacet.class);

        when(dataSourceV2Relation.identifier()).thenReturn(Option.apply(identifier));
        when(dataSourceV2Relation.catalog()).thenReturn(Option.apply(tableCatalog));
        when(dataSourceV2Relation.table()).thenReturn(table);
        when(table.properties()).thenReturn(tableProperties);

        when(openLineage.newLifecycleStateChangeDatasetFacet(lifecycleStateChange, null))
            .thenReturn(lifecycleStateChangeDatasetFacet);
        when(openLineage.newDatasetVersionDatasetFacet("v2"))
            .thenReturn(datasetVersionDatasetFacet);

        when(DataSourceV2RelationDatasetExtractor.extract(
                any(), eq(openLineageContext), eq(dataSourceV2Relation), any(), any(Boolean.class)))
            .thenReturn(Collections.singletonList(dataset));

        List<OpenLineage.OutputDataset> datasetList =
            builder.apply(new SparkListenerSQLExecutionEnd(1L, 1L), logicalPlan);

        assertEquals(1, datasetList.size());
        assertEquals(dataset, datasetList.get(0));

        if (lifecycleStateChange != null) {
          Mockito.verify(datasetFacetsBuilder)
              .lifecycleStateChange(eq(lifecycleStateChangeDatasetFacet));
        }
      }
    }
  }
}
