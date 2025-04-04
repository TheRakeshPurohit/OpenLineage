/*
/* Copyright 2018-2025 contributors to the OpenLineage project
/* SPDX-License-Identifier: Apache-2.0
*/

package io.openlineage.proxy.api.models;

import io.openlineage.proxy.ProxyStreamConfig;
import java.util.Properties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@NoArgsConstructor
@ToString
public final class KafkaConfig implements ProxyStreamConfig {
  @Getter @Setter private String topicName;
  @Getter @Setter private String messageKey;
  @Getter @Setter private String bootstrapServerUrl;
  @Getter @Setter private Properties properties;
}
