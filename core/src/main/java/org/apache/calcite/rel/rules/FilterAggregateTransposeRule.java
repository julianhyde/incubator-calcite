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

import org.apache.calcite.plan.Contexts;
import org.apache.calcite.plan.RelOptNewRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptRuleOperand;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Aggregate.Group;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.tools.RelBuilderFactory;
import org.apache.calcite.util.ImmutableBitSet;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;

/**
 * Planner rule that pushes a {@link org.apache.calcite.rel.core.Filter}
 * past a {@link org.apache.calcite.rel.core.Aggregate}.
 *
 * @see org.apache.calcite.rel.rules.AggregateFilterTransposeRule
 */
public class FilterAggregateTransposeRule extends RelOptNewRule
    implements TransformationRule {
  /** The default instance of
   * {@link FilterAggregateTransposeRule}.
   *
   * <p>It matches any kind of agg. or filter */
  public static final FilterAggregateTransposeRule INSTANCE =
      Config.EMPTY
          .as(Config.class)
          .withOperandFor(Filter.class, Aggregate.class)
          .toRule();

  //~ Constructors -----------------------------------------------------------

  /** Creates a FilterAggregateTransposeRule. */
  protected FilterAggregateTransposeRule(Config config) {
    super(config);
  }

  @Deprecated
  public FilterAggregateTransposeRule(
      Class<? extends Filter> filterClass,
      RelBuilderFactory relBuilderFactory,
      Class<? extends Aggregate> aggregateClass) {
    this(INSTANCE.config
        .withRelBuilderFactory(relBuilderFactory)
        .as(Config.class)
        .withOperandFor(filterClass, aggregateClass));
  }

  @Deprecated
  protected FilterAggregateTransposeRule(RelOptRuleOperand operand,
      RelBuilderFactory relBuilderFactory) {
    this(INSTANCE.config
        .withRelBuilderFactory(relBuilderFactory)
        .withOperandSupplier(b -> b.exactly(operand))
        .as(Config.class));
  }

  @Deprecated // to be removed before 2.0
  public FilterAggregateTransposeRule(
      Class<? extends Filter> filterClass,
      RelFactories.FilterFactory filterFactory,
      Class<? extends Aggregate> aggregateClass) {
    this(filterClass, RelBuilder.proto(Contexts.of(filterFactory)),
        aggregateClass);
  }

  //~ Methods ----------------------------------------------------------------

  @Override public Config config() {
    return (Config) config;
  }

  @Override public void onMatch(RelOptRuleCall call) {
    final Filter filterRel = call.rel(0);
    final Aggregate aggRel = call.rel(1);

    final List<RexNode> conditions =
        RelOptUtil.conjunctions(filterRel.getCondition());
    final RexBuilder rexBuilder = filterRel.getCluster().getRexBuilder();
    final List<RelDataTypeField> origFields =
        aggRel.getRowType().getFieldList();
    final int[] adjustments = new int[origFields.size()];
    int j = 0;
    for (int i : aggRel.getGroupSet()) {
      adjustments[j] = i - j;
      j++;
    }
    final List<RexNode> pushedConditions = new ArrayList<>();
    final List<RexNode> remainingConditions = new ArrayList<>();

    for (RexNode condition : conditions) {
      ImmutableBitSet rCols = RelOptUtil.InputFinder.bits(condition);
      if (canPush(aggRel, rCols)) {
        pushedConditions.add(
            condition.accept(
                new RelOptUtil.RexInputConverter(rexBuilder, origFields,
                    aggRel.getInput(0).getRowType().getFieldList(),
                    adjustments)));
      } else {
        remainingConditions.add(condition);
      }
    }

    final RelBuilder builder = call.builder();
    RelNode rel =
        builder.push(aggRel.getInput()).filter(pushedConditions).build();
    if (rel == aggRel.getInput(0)) {
      return;
    }
    rel = aggRel.copy(aggRel.getTraitSet(), ImmutableList.of(rel));
    rel = builder.push(rel).filter(remainingConditions).build();
    call.transformTo(rel);
  }

  private boolean canPush(Aggregate aggregate, ImmutableBitSet rCols) {
    // If the filter references columns not in the group key, we cannot push
    final ImmutableBitSet groupKeys =
        ImmutableBitSet.range(0, aggregate.getGroupSet().cardinality());
    if (!groupKeys.contains(rCols)) {
      return false;
    }

    if (aggregate.getGroupType() != Group.SIMPLE) {
      // If grouping sets are used, the filter can be pushed if
      // the columns referenced in the predicate are present in
      // all the grouping sets.
      for (ImmutableBitSet groupingSet : aggregate.getGroupSets()) {
        if (!groupingSet.contains(rCols)) {
          return false;
        }
      }
    }
    return true;
  }

  /** Rule configuration. */
  public interface Config extends RelOptNewRule.Config {
    @Override default FilterAggregateTransposeRule toRule() {
      return new FilterAggregateTransposeRule(this);
    }

    /** Defines an operand tree for the given classes. */
    default Config withOperandFor(Class<? extends Filter> filterClass,
        Class<? extends Aggregate> aggregateClass) {
      return withOperandSupplier(b ->
          b.operand(filterClass).oneInput(b2 ->
              b2.operand(aggregateClass).anyInputs()))
          .as(Config.class);
    }
  }
}
