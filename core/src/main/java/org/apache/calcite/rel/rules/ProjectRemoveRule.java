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
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.tools.RelBuilderFactory;

/**
 * Planner rule that,
 * given a {@link org.apache.calcite.rel.core.Project} node that
 * merely returns its input, converts the node into its child.
 *
 * <p>For example, <code>Project(ArrayReader(a), {$input0})</code> becomes
 * <code>ArrayReader(a)</code>.</p>
 *
 * @see CalcRemoveRule
 * @see ProjectMergeRule
 * @see CoreRules#PROJECT_REMOVE
 */
public class ProjectRemoveRule
    extends RelOptNewRule<ProjectRemoveRule.Config>
    implements SubstitutionRule {
  /** @deprecated Use {@link CoreRules#PROJECT_REMOVE}. */
  @Deprecated // to be removed before 1.25
  public static final ProjectRemoveRule INSTANCE = Config.DEFAULT.toRule();

  //~ Constructors -----------------------------------------------------------

  /** Creates a ProjectRemoveRule. */
  protected ProjectRemoveRule(Config config) {
    super(config);
  }

  @Deprecated // to be removed before 2.0
  public ProjectRemoveRule(RelBuilderFactory relBuilderFactory) {
    this(Config.DEFAULT.withRelBuilderFactory(relBuilderFactory)
        .as(Config.class));
  }

  //~ Methods ----------------------------------------------------------------

  @Override public void onMatch(RelOptRuleCall call) {
    Project project = call.rel(0);
    assert isTrivial(project);
    RelNode stripped = project.getInput();
    if (stripped instanceof Project) {
      // Rename columns of child projection if desired field names are given.
      Project childProject = (Project) stripped;
      stripped = childProject.copy(childProject.getTraitSet(),
          childProject.getInput(), childProject.getProjects(),
          project.getRowType());
    }
    stripped = convert(stripped, project.getConvention());
    call.transformTo(stripped);
  }

  /**
   * Returns the child of a project if the project is trivial, otherwise
   * the project itself.
   */
  public static RelNode strip(Project project) {
    return isTrivial(project) ? project.getInput() : project;
  }

  public static boolean isTrivial(Project project) {
    return RexUtil.isIdentity(project.getProjects(),
        project.getInput().getRowType());
  }

  @Override public boolean autoPruneOld() {
    return true;
  }

  /** Rule configuration. */
  public interface Config extends RelOptNewRule.Config {
    Config DEFAULT = EMPTY
        .withOperandSupplier(b ->
            b.operand(Project.class)
                // Use a predicate to detect non-matches early.
                // This keeps the rule queue short.
                .predicate(ProjectRemoveRule::isTrivial)
                .anyInputs())
        .as(Config.class);

    @Override default ProjectRemoveRule toRule() {
      return new ProjectRemoveRule(this);
    }
  }
}
