<<<<<<< HEAD:integration/spark/src/main/spark3/java/io/openlineage/spark3/agent/lifecycle/plan/columnLineage/OutputFieldsCollector.java
/* Copyright 2018-2022 contributors to the OpenLineage project */

package io.openlineage.spark3.agent.lifecycle.plan.columnLineage;
=======
package io.openlineage.spark3.agent.lifecycle.plan.column;
>>>>>>> 91dc08f00207d1a9de55aa24e6a41744a58e1b2d:integration/spark/src/main/spark3/java/io/openlineage/spark3/agent/lifecycle/plan/column/OutputFieldsCollector.java

import io.openlineage.spark.agent.util.ScalaConversionUtils;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.NamedExpression;
import org.apache.spark.sql.catalyst.plans.logical.Aggregate;
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan;
import org.apache.spark.sql.catalyst.plans.logical.Project;

/** Class created to collect output fields with the corresponding ExprId from LogicalPlan. */
class OutputFieldsCollector {

  static void collect(LogicalPlan plan, ColumnLevelLineageBuilder builder) {
    List<NamedExpression> expressions =
        ScalaConversionUtils.fromSeq(plan.output()).stream()
            .filter(attr -> attr instanceof Attribute)
            .map(attr -> (Attribute) attr)
            .collect(Collectors.toList());

    if (plan instanceof Aggregate) {
      expressions.addAll(
          ScalaConversionUtils.<NamedExpression>fromSeq(((Aggregate) plan).aggregateExpressions()));
    } else if (plan instanceof Project) {
      expressions.addAll(
          ScalaConversionUtils.<NamedExpression>fromSeq(((Project) plan).projectList()));
    }

    expressions.stream().forEach(expr -> builder.addOutput(expr.exprId(), expr.name()));

    if (!builder.hasOutputs()) {
      // extract outputs from the children
      ScalaConversionUtils.<LogicalPlan>fromSeq(plan.children()).stream()
          .forEach(childPlan -> collect(childPlan, builder));
    }
  }
}
