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

import org.apache.calcite.plan.RelOptPredicateList;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelDistribution;
import org.apache.calcite.rel.RelDistributions;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.SortExchange;
import org.apache.calcite.rel.logical.LogicalSortExchange;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexInputRef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Rule that reduces constants inside a {@link SortExchange}. */
public class SortExchangeRemoveConstantKeysRule
    extends ExchangeRemoveConstantKeysRule {
  /** Singleton rule that removes constants inside a
   * {@link LogicalSortExchange}. */
  public static final SortExchangeRemoveConstantKeysRule INSTANCE =
      Config.EMPTY
          .as(Config.class)
          .withOperandForSort(LogicalSortExchange.class)
          .toRule();

  /** Creates a SortExchangeRemoveConstantKeysRule. */
  protected SortExchangeRemoveConstantKeysRule(Config config) {
    super(config);
  }

  @Deprecated
  SortExchangeRemoveConstantKeysRule(Class<? extends RelNode> clazz,
      String description) {
    this(INSTANCE.config
        .withDescription(description)
        .as(Config.class)
        .withOperandForSort((Class) clazz));
  }

  @Override public boolean matches(RelOptRuleCall call) {
    final SortExchange sortExchange = call.rel(0);
    return  sortExchange.getDistribution().getType()
        == RelDistribution.Type.HASH_DISTRIBUTED
        || !sortExchange.getCollation().getFieldCollations().isEmpty();
  }

  @Override public void onMatch(RelOptRuleCall call) {
    final SortExchange sortExchange = call.rel(0);
    final RelMetadataQuery mq = call.getMetadataQuery();
    final RelNode input = sortExchange.getInput();
    final RelOptPredicateList predicates = mq.getPulledUpPredicates(input);
    if (predicates == null) {
      return;
    }

    final Set<Integer> constants = new HashSet<>();
    predicates.constantMap.keySet().forEach(key -> {
      if (key instanceof RexInputRef) {
        constants.add(((RexInputRef) key).getIndex());
      }
    });

    if (constants.isEmpty()) {
      return;
    }

    List<Integer> distributionKeys = new ArrayList<>();
    boolean distributionSimplified = false;
    boolean hashDistribution = sortExchange.getDistribution().getType()
        == RelDistribution.Type.HASH_DISTRIBUTED;
    if (hashDistribution) {
      distributionKeys = simplifyDistributionKeys(
          sortExchange.getDistribution(), constants);
      distributionSimplified =
          distributionKeys.size() != sortExchange.getDistribution().getKeys()
              .size();
    }

    final List<RelFieldCollation> fieldCollations = sortExchange
        .getCollation().getFieldCollations().stream().filter(
            fc -> !constants.contains(fc.getFieldIndex()))
         .collect(Collectors.toList());

    boolean collationSimplified =
         fieldCollations.size() != sortExchange.getCollation()
             .getFieldCollations().size();
    if (distributionSimplified
         || collationSimplified) {
      RelDistribution distribution = distributionSimplified
          ? (distributionKeys.isEmpty()
              ? RelDistributions.SINGLETON
              : RelDistributions.hash(distributionKeys))
          : sortExchange.getDistribution();
      RelCollation collation = collationSimplified
          ? RelCollations.of(fieldCollations)
          : sortExchange.getCollation();

      call.transformTo(call.builder()
          .push(sortExchange.getInput())
          .sortExchange(distribution, collation)
          .build());
      call.getPlanner().prune(sortExchange);
    }
  }

  /** Rule configuration. */
  public interface Config extends ExchangeRemoveConstantKeysRule.Config {
    @Override default SortExchangeRemoveConstantKeysRule toRule() {
      return new SortExchangeRemoveConstantKeysRule(this);
    }

    /** Defines an operand tree for the given classes. */
    default SortExchangeRemoveConstantKeysRule.Config withOperandForSort(
        Class<? extends SortExchange> sortExchangeClass) {
      return withOperandSupplier(b -> b.operand(sortExchangeClass).anyInputs())
          .as(Config.class);
    }
  }
}
