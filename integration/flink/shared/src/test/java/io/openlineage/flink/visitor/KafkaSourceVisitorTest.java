/*
/* Copyright 2018-2025 contributors to the OpenLineage project
/* SPDX-License-Identifier: Apache-2.0
*/

package io.openlineage.flink.visitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import io.openlineage.client.OpenLineage;
import io.openlineage.client.OpenLineage.SchemaDatasetFacet;
import io.openlineage.flink.api.OpenLineageContext;
import io.openlineage.flink.visitor.wrapper.KafkaSourceWrapper;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import lombok.SneakyThrows;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class KafkaSourceVisitorTest {

  OpenLineageContext context = mock(OpenLineageContext.class);
  KafkaSource kafkaSource = mock(KafkaSource.class);
  KafkaSourceVisitor kafkaSourceVisitor = new KafkaSourceVisitor(context);
  KafkaSourceWrapper wrapper = mock(KafkaSourceWrapper.class);
  Properties props = new Properties();
  OpenLineage openLineage = new OpenLineage(mock(URI.class));
  SchemaDatasetFacet schema =
      openLineage.newSchemaDatasetFacet(
          Collections.singletonList(
              openLineage.newSchemaDatasetFacetFields("a", "long", null, null)));

  @BeforeEach
  @SneakyThrows
  public void setup() {
    when(context.getOpenLineage()).thenReturn(openLineage);
  }

  @Test
  void testIsDefined() {
    assertFalse(kafkaSourceVisitor.isDefinedAt(mock(Object.class)));
    assertTrue(kafkaSourceVisitor.isDefinedAt(mock(KafkaSource.class)));
  }

  @Test
  @SneakyThrows
  void testApply() {
    props.put("bootstrap.servers", "server1;server2");

    try (MockedStatic<KafkaSourceWrapper> mockedStatic = mockStatic(KafkaSourceWrapper.class)) {
      when(KafkaSourceWrapper.of(kafkaSource, context)).thenReturn(wrapper);

      when(wrapper.getTopics()).thenReturn(Arrays.asList("topic1", "topic2"));
      when(wrapper.getProps()).thenReturn(props);
      when(wrapper.getSchemaFacet()).thenReturn(Optional.of(schema));

      List<OpenLineage.InputDataset> inputDatasets = kafkaSourceVisitor.apply(kafkaSource);
      List<OpenLineage.SchemaDatasetFacetFields> fields =
          inputDatasets.get(0).getFacets().getSchema().getFields();

      assertEquals(2, inputDatasets.size());
      assertEquals("topic1", inputDatasets.get(0).getName());
      assertEquals("kafka://server1", inputDatasets.get(0).getNamespace());

      assertEquals(1, fields.size());
      assertEquals("a", fields.get(0).getName());
      assertEquals("long", fields.get(0).getType());
    }
  }

  @Test
  @SneakyThrows
  void testApplyWhenIllegalAccessExceptionThrown() {
    try (MockedStatic<KafkaSourceWrapper> mockedStatic = mockStatic(KafkaSourceWrapper.class)) {
      when(KafkaSourceWrapper.of(kafkaSource, context)).thenReturn(wrapper);

      when(wrapper.getTopics()).thenThrow(new IllegalAccessException(""));
      List<OpenLineage.InputDataset> inputDatasets = kafkaSourceVisitor.apply(kafkaSource);

      assertEquals(0, inputDatasets.size());
    }
  }
}
