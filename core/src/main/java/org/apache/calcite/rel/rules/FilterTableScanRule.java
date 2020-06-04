/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.rel.rules;

import org.apache.calcite.adapter.enumerable.EnumerableInterpreter;
import org.apache.calcite.interpreter.Bindables;
import org.apache.calcite.plan.RelOptNewRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptRuleOperand;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.schema.FilterableTable;
import org.apache.calcite.schema.ProjectableFilterableTable;
import org.apache.calcite.tools.RelBuilderFactory;
import org.apache.calcite.util.ImmutableIntList;
import org.apache.calcite.util.mapping.Mapping;
import org.apache.calcite.util.mapping.Mappings;

import com.google.common.collect.ImmutableList;

/**
 * Planner rule that converts
 * a {@link org.apache.calcite.rel.core.Filter}
 * on a {@link org.apache.calcite.rel.core.TableScan}
 * of a {@link org.apache.calcite.schema.FilterableTable}
 * or a {@link org.apache.calcite.schema.ProjectableFilterableTable}
 * to a {@link org.apache.calcite.interpreter.Bindables.BindableTableScan}.
 *
 * <p>The {@link #INTERPRETER} variant allows an intervening
 * {@link org.apache.calcite.adapter.enumerable.EnumerableInterpreter}.
 *
 * @see org.apache.calcite.rel.rules.ProjectTableScanRule
 */
public class FilterTableScanRule extends RelOptNewRule {
  @SuppressWarnings("Guava")
  @Deprecated // to be removed before 2.0
  public static final com.google.common.base.Predicate<TableScan> PREDICATE =
      FilterTableScanRule::test;

  /** Rule that matches Filter on TableScan. */
  public static final FilterTableScanRule INSTANCE =
      Config.EMPTY
          .withOperandSupplier(b ->
              b.operand(Filter.class).oneInput(b2 ->
                  b2.operand(TableScan.class)
                      .predicate(FilterTableScanRule::test).noInputs()))
          .as(Config.class)
          .toRule();

  /** Rule that matches Filter on EnumerableInterpreter on TableScan. */
  public static final FilterTableScanRule INTERPRETER =
      Config.EMPTY
          .withOperandSupplier(b ->
              b.operand(Filter.class).oneInput(b2 ->
                  b2.operand(EnumerableInterpreter.class).oneInput(b3 ->
                      b3.operand(TableScan.class)
                          .predicate(FilterTableScanRule::test).noInputs())))
          .withDescription("FilterTableScanRule:interpreter")
          .as(Config.class)
          .toRule();

  //~ Constructors -----------------------------------------------------------

  /** Creates a FilterTableScanRule. */
  protected FilterTableScanRule(Config config) {
    super(config);
  }

  @Deprecated // to be removed before 2.0
  protected FilterTableScanRule(RelOptRuleOperand operand, String description) {
    this(Config.EMPTY.as(Config.class));
    throw new UnsupportedOperationException();
  }

  @Deprecated // to be removed before 2.0
  protected FilterTableScanRule(RelOptRuleOperand operand,
      RelBuilderFactory relBuilderFactory, String description) {
    this(Config.EMPTY.as(Config.class));
    throw new UnsupportedOperationException();
  }

  //~ Methods ----------------------------------------------------------------

  public static boolean test(TableScan scan) {
    // We can only push filters into a FilterableTable or
    // ProjectableFilterableTable.
    final RelOptTable table = scan.getTable();
    return table.unwrap(FilterableTable.class) != null
        || table.unwrap(ProjectableFilterableTable.class) != null;
  }

  @Override public void onMatch(RelOptRuleCall call) {
    final Filter filter;
    final TableScan scan;
    switch (call.rels.length) {
    case 2:
      // the ordinary variant
      filter = call.rel(0);
      scan = call.rel(1);
      break;
    case 3:
      // the variant with intervening EnumerableInterpreter
      filter = call.rel(0);
      scan = call.rel(2);
      break;
    default:
      throw new AssertionError();
    }
    apply(call, filter, scan);
  }

  protected void apply(RelOptRuleCall call, Filter filter, TableScan scan) {
    final ImmutableIntList projects;
    final ImmutableList.Builder<RexNode> filters = ImmutableList.builder();
    if (scan instanceof Bindables.BindableTableScan) {
      final Bindables.BindableTableScan bindableScan =
          (Bindables.BindableTableScan) scan;
      filters.addAll(bindableScan.filters);
      projects = bindableScan.projects;
    } else {
      projects = scan.identity();
    }

    final Mapping mapping = Mappings.target(projects,
        scan.getTable().getRowType().getFieldCount());
    filters.add(
        RexUtil.apply(mapping.inverse(), filter.getCondition()));

    call.transformTo(
        Bindables.BindableTableScan.create(scan.getCluster(), scan.getTable(),
            filters.build(), projects));
  }

  /** Rule configuration. */
  public interface Config extends RelOptNewRule.Config {
    @Override default FilterTableScanRule toRule() {
      return new FilterTableScanRule(this);
    }
  }
}
