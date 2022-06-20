package ai.datasqrl.plan.local.operations;

import ai.datasqrl.parse.tree.Node;
import ai.datasqrl.parse.tree.name.Name;
import ai.datasqrl.schema.Relationship;
import ai.datasqrl.schema.Table;
import lombok.Value;

@Value
public class AddNestedTableOp implements SchemaUpdateOp {
  Table table;
  Node node;

  @Override
  public <T> T accept(SchemaOpVisitor visitor) {
    return visitor.visit(this);
  }
}