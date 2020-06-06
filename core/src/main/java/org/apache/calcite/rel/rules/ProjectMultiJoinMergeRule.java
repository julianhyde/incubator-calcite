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

import org.apache.calcite.plan.RelOptNewRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.tools.RelBuilderFactory;

/**
 * Planner rule that pushes
 * {@link org.apache.calcite.rel.core.Project}
 * into a {@link MultiJoin},
 * creating a richer {@code MultiJoin}.
 *
 * @see org.apache.calcite.rel.rules.FilterMultiJoinMergeRule
 */
public class ProjectMultiJoinMergeRule extends RelOptNewRule
    implements TransformationRule {
  public static final ProjectMultiJoinMergeRule INSTANCE =
      Config.EMPTY
          .as(Config.class)
          .withOperandFor(LogicalProject.class, MultiJoin.class)
          .toRule();

  //~ Constructors -----------------------------------------------------------

  /** Creates a ProjectMultiJoinMergeRule. */
  protected ProjectMultiJoinMergeRule(Config config) {
    super(config);
  }

  @Deprecated
  public ProjectMultiJoinMergeRule(RelBuilderFactory relBuilderFactory) {
    this(INSTANCE.config.withRelBuilderFactory(relBuilderFactory)
        .as(Config.class));
  }

  @Deprecated
  public ProjectMultiJoinMergeRule(Class<? extends Project> projectClass,
      RelBuilderFactory relBuilderFactory) {
    this(INSTANCE.config.withRelBuilderFactory(relBuilderFactory)
        .as(Config.class)
        .withOperandFor(projectClass, MultiJoin.class));
  }

  //~ Methods ----------------------------------------------------------------

  @Override public void onMatch(RelOptRuleCall call) {
    Project project = call.rel(0);
    MultiJoin multiJoin = call.rel(1);

    // if all inputs have their projFields set, then projection information
    // has already been pushed into each input
    boolean allSet = true;
    for (int i = 0; i < multiJoin.getInputs().size(); i++) {
      if (multiJoin.getProjFields().get(i) == null) {
        allSet = false;
        break;
      }
    }
    if (allSet) {
      return;
    }

    // create a new MultiJoin that reflects the columns in the projection
    // above the MultiJoin
    final RelBuilder relBuilder = call.builder();
    MultiJoin newMultiJoin =
        RelOptUtil.projectMultiJoin(multiJoin, project);
    relBuilder.push(newMultiJoin)
        .project(project.getProjects(), project.getRowType().getFieldNames());

    call.transformTo(relBuilder.build());
  }

  /** Rule configuration. */
  public interface Config extends RelOptNewRule.Config {
    @Override default ProjectMultiJoinMergeRule toRule() {
      return new ProjectMultiJoinMergeRule(this);
    }

    /** Defines an operand tree for the given classes. */
    default Config withOperandFor(Class<? extends Project> projectClass,
        Class<MultiJoin> multiJoinClass) {
      return withOperandSupplier(b ->
          b.operand(projectClass).oneInput(b2 ->
              b2.operand(multiJoinClass).anyInputs()))
          .as(Config.class);
    }
  }
}
