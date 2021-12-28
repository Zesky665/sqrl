/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.dataeng.sqml.tree;

import java.util.List;
import java.util.Optional;

public final class ScriptNode
    extends Expression {

  private final List<Node> statements;

  public ScriptNode(NodeLocation location,
      List<Node> statements) {
    this(Optional.ofNullable(location), statements);
  }

  public ScriptNode(Optional<NodeLocation> location,
      List<Node> statements) {
    super(location);
    this.statements = statements;
  }

  @Override
  public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
    return visitor.visitScript(this, context);
  }

  @Override
  public List<? extends Node> getChildren() {
    return statements;
  }

  public List<Node> getStatements() {
    return statements;
  }

  @Override
  public int hashCode() {
    return 0;
  }

  @Override
  public boolean equals(Object obj) {
    return false;
  }
}