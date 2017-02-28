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
package org.apache.calcite.adapter.pig;

import org.apache.calcite.plan.Context;
import org.apache.calcite.plan.Contexts;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.ImmutableBitSet;

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Set;

/**
 */
public class PigRelFactories {

  public static final Context ALL_PIG_REL_FACTORIES = Contexts.of(PigTableScanFactory.INSTANCE,
      PigFilterFactory.INSTANCE, PigAggregateFactory.INSTANCE, PigJoinFactory.INSTANCE);

  // prevent instantiation
  private PigRelFactories() {
  }

  /**
   * Implementation of {@link RelFactories.TableScanFactory} that
   * returns a {@link PigTableScan}.
   */
  public static class PigTableScanFactory implements RelFactories.TableScanFactory {

    public static final PigTableScanFactory INSTANCE = new PigTableScanFactory();

    @Override public RelNode createScan(RelOptCluster cluster, RelOptTable table) {
      return new PigTableScan(cluster, cluster.traitSetOf(PigRel.CONVENTION), table);
    }
  }

  /**
   * Implementation of {@link RelFactories.FilterFactory} that
   * returns a {@link PigFilter}.
   */
  public static class PigFilterFactory implements RelFactories.FilterFactory {

    public static final PigFilterFactory INSTANCE = new PigFilterFactory();

    @Override public RelNode createFilter(RelNode input, RexNode condition) {
      return new PigFilter(input.getCluster(), input.getTraitSet().replace(PigRel.CONVENTION),
          input, condition);
    }
  }

  /**
   * Implementation of {@link RelFactories.AggregateFactory} that
   * returns a {@link PigAggregate}.
   */
  public static class PigAggregateFactory implements RelFactories.AggregateFactory {

    public static final PigAggregateFactory INSTANCE = new PigAggregateFactory();

    @Override public RelNode createAggregate(RelNode input, boolean indicator,
        ImmutableBitSet groupSet, ImmutableList<ImmutableBitSet> groupSets,
        List<AggregateCall> aggCalls) {
      return new PigAggregate(input.getCluster(), input.getTraitSet(), input, indicator, groupSet,
          groupSets, aggCalls);
    }
  }

  /**
   * Implementation of {@link RelFactories.JoinFactory} that
   * returns a {@link PigJoin}.
   */
  public static class PigJoinFactory implements RelFactories.JoinFactory {

    public static final PigJoinFactory INSTANCE = new PigJoinFactory();

    @Override public RelNode createJoin(RelNode left, RelNode right, RexNode condition,
        Set<CorrelationId> variablesSet, JoinRelType joinType, boolean semiJoinDone) {
      return new PigJoin(left.getCluster(), left.getTraitSet(), left, right, condition, joinType);
    }

    @Override public RelNode createJoin(RelNode left, RelNode right, RexNode condition,
        JoinRelType joinType, Set<String> variablesStopped, boolean semiJoinDone) {
      return new PigJoin(left.getCluster(), left.getTraitSet(), left, right, condition, joinType);
    }
  }
}

// End PigRelFactories.java
