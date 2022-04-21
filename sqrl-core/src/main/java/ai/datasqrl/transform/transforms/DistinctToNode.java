package ai.datasqrl.transform.transforms;

import ai.datasqrl.parse.tree.ComparisonExpression;
import ai.datasqrl.parse.tree.ComparisonExpression.Operator;
import ai.datasqrl.parse.tree.DistinctAssignment;
import ai.datasqrl.parse.tree.Expression;
import ai.datasqrl.parse.tree.FunctionCall;
import ai.datasqrl.parse.tree.Identifier;
import ai.datasqrl.parse.tree.Literal;
import ai.datasqrl.parse.tree.LongLiteral;
import ai.datasqrl.parse.tree.Node;
import ai.datasqrl.parse.tree.OrderBy;
import ai.datasqrl.parse.tree.QuerySpecification;
import ai.datasqrl.parse.tree.Select;
import ai.datasqrl.parse.tree.SelectItem;
import ai.datasqrl.parse.tree.SingleColumn;
import ai.datasqrl.parse.tree.SortItem;
import ai.datasqrl.parse.tree.SortItem.Ordering;
import ai.datasqrl.parse.tree.TableNode;
import ai.datasqrl.parse.tree.Window;
import ai.datasqrl.parse.tree.name.Name;
import ai.datasqrl.parse.tree.name.NamePath;
import ai.datasqrl.schema.Column;
import ai.datasqrl.schema.Table;
import ai.datasqrl.util.AliasGenerator;
import ai.datasqrl.validate.scopes.DistinctScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParser;

public class DistinctToNode {

  @SneakyThrows
  public Node transform(DistinctAssignment node, DistinctScope distinctScope) {
    List<SelectItem> selectItems = distinctScope.getTable().getFields().getElements()
        .stream().filter(f->f instanceof Column)
        .map(f->
            new SingleColumn(Optional.empty(),
              new Identifier(Optional.empty(), f.getId().toNamePath()),
              Optional.empty()))
        .collect(Collectors.toList());

    List<Expression> partition = distinctScope.getPartitionKeys().stream()
        .map(f -> new Identifier(Optional.empty(), f.getId().toNamePath()))
        .collect(Collectors.toList());

    List<SelectItem> selectPartition = new ArrayList<>(selectItems);
    //TODO: Sort items outside of the ingest time
    selectPartition.add(new SingleColumn(Optional.empty(), new FunctionCall(Optional.empty(), NamePath.of("ROW_NUMBER"), List.of(), false,
        Optional.of(new Window(partition, Optional.of(new OrderBy(List.of(new SortItem(new Identifier(Optional.empty(), NamePath.parse("_ingest_time")), Ordering.DESCENDING))))))),
            Optional.of(new Identifier(Optional.empty(), NamePath.parse("_row_num"))))
        );

//    SELECT * FROM (SELECT *, ROW_NUMBER() OVER (PARTITION BY %s %s) _row_num FROM %s) %s WHERE __row_number = 1";
    QuerySpecification outer = new QuerySpecification(
        Optional.empty(),
        new Select(false, selectItems),
        new QuerySpecification(
            Optional.empty(),
            new Select(false, selectPartition),
            new TableNode(Optional.empty(), distinctScope.getTable().getId().toNamePath(), Optional.empty()),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        ),
        Optional.of(new ComparisonExpression(Optional.empty(), Operator.EQUAL,
            new Identifier(Optional.empty(), NamePath.parse("_row_num")), new LongLiteral("1"))),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty()
    );

    return outer;
  }
}