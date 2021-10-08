package ai.dataeng.sqml.physical;

import ai.dataeng.sqml.logical.RelationDefinition;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public abstract class PhysicalPlanNode {
  protected RelationDefinition relationDefinition;
  public abstract List<Column> getColumns();

  public <R, C> R accept(PhysicalPlanVisitor<R, C> visitor, C context) {
    return visitor.visit(this, context);
  }

}