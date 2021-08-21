package ai.dataeng.sqml.analyzer;

import static ai.dataeng.sqml.analyzer.AggregationAnalyzer.verifyOrderByAggregations;
import static ai.dataeng.sqml.analyzer.AggregationAnalyzer.verifySourceAggregations;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Predicates.alwaysTrue;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getLast;
import static java.util.Collections.emptyList;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

import ai.dataeng.sqml.OperatorType.QualifiedObjectName;
import ai.dataeng.sqml.function.FunctionProvider;
import ai.dataeng.sqml.function.SqmlFunction;
import ai.dataeng.sqml.metadata.Metadata;
import ai.dataeng.sqml.tree.AliasedRelation;
import ai.dataeng.sqml.tree.AllColumns;
import ai.dataeng.sqml.tree.ArithmeticBinaryExpression;
import ai.dataeng.sqml.tree.AstVisitor;
import ai.dataeng.sqml.tree.DefaultExpressionTraversalVisitor;
import ai.dataeng.sqml.tree.DereferenceExpression;
import ai.dataeng.sqml.tree.Except;
import ai.dataeng.sqml.tree.Expression;
import ai.dataeng.sqml.tree.FunctionCall;
import ai.dataeng.sqml.tree.GroupingElement;
import ai.dataeng.sqml.tree.GroupingOperation;
import ai.dataeng.sqml.tree.Identifier;
import ai.dataeng.sqml.tree.Intersect;
import ai.dataeng.sqml.tree.Join;
import ai.dataeng.sqml.tree.JoinCriteria;
import ai.dataeng.sqml.tree.JoinOn;
import ai.dataeng.sqml.tree.Node;
import ai.dataeng.sqml.tree.OrderBy;
import ai.dataeng.sqml.tree.QualifiedName;
import ai.dataeng.sqml.tree.Query;
import ai.dataeng.sqml.tree.QuerySpecification;
import ai.dataeng.sqml.tree.Relation;
import ai.dataeng.sqml.tree.SelectItem;
import ai.dataeng.sqml.tree.SetOperation;
import ai.dataeng.sqml.tree.SingleColumn;
import ai.dataeng.sqml.tree.SortItem;
import ai.dataeng.sqml.tree.Statement;
import ai.dataeng.sqml.tree.Table;
import ai.dataeng.sqml.tree.TableSubquery;
import ai.dataeng.sqml.tree.Union;
import ai.dataeng.sqml.type.SqmlType;
import ai.dataeng.sqml.type.SqmlType.BooleanSqmlType;
import ai.dataeng.sqml.type.SqmlType.RelationSqmlType;
import ai.dataeng.sqml.type.SqmlType.UnknownSqmlType;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

public class StatementAnalyzer {

  private final Metadata metadata;
  private final Analysis analysis;

  public StatementAnalyzer(Metadata metadata, Analysis analysis) {

    this.metadata = metadata;
    this.analysis = analysis;
  }

  public Scope analyze(Statement statement,
      Scope scope) {
    Visitor visitor = new Visitor();
    return statement.accept(visitor, scope);
  }

  public class Visitor extends AstVisitor<Scope, Scope> {

    @Override
    protected Scope visitNode(Node node, Scope context) {
      throw new RuntimeException(String.format("Could not process node %s : %s", node.getClass().getName(), node));
    }

    @Override
    protected Scope visitQuery(Query node, Scope scope)
    {
      Scope queryBodyScope = node.getQueryBody().accept(this, scope);

      List<Expression> orderByExpressions = emptyList();
      if (node.getOrderBy().isPresent()) {
        orderByExpressions = analyzeOrderBy(node, getSortItemsFromOrderBy(node.getOrderBy()), queryBodyScope);
      }
      analysis.setOrderByExpressions(node, orderByExpressions);

      Scope queryScope = Scope.builder()
          .withParent(queryBodyScope)
          .withRelationType(node, queryBodyScope.getRelationType())
          .build();

      analysis.setScope(node, queryScope);
      return queryScope;
    }

    @Override
    protected Scope visitQuerySpecification(QuerySpecification node, Scope scope)
    {
      Scope sourceScope = analyzeFrom(node, scope);

      node.getWhere().ifPresent(where -> analyzeWhere(node, sourceScope, where));

      List<Expression> outputExpressions = analyzeSelect(node, sourceScope);
      List<Expression> groupByExpressions = analyzeGroupBy(node, sourceScope, outputExpressions);
      analyzeHaving(node, sourceScope);
      Scope outputScope = computeAndAssignOutputScope(node, scope, sourceScope);

      List<Expression> orderByExpressions = emptyList();
      Optional<Scope> orderByScope = Optional.empty();
      if (node.getOrderBy().isPresent()) {
        if (node.getSelect().isDistinct()) {
          verifySelectDistinct(node, outputExpressions);
        }

        OrderBy orderBy = node.getOrderBy().get();
        orderByScope = Optional.of(computeAndAssignOrderByScope(orderBy, sourceScope, outputScope));

        orderByExpressions = analyzeOrderBy(node, orderBy.getSortItems(), orderByScope.get());

//        if (sourceScope.getOuterQueryParent().isPresent() && !node.getLimit().isPresent()) {
//          // not the root scope and ORDER BY is ineffective
//          analysis.markRedundantOrderBy(orderBy);
//          warningCollector.add(new PrestoWarning(REDUNDANT_ORDER_BY, "ORDER BY in subquery may have no effect"));
//        }
      }
      analysis.setOrderByExpressions(node, orderByExpressions);

      List<Expression> sourceExpressions = new ArrayList<>(outputExpressions);
      node.getHaving().ifPresent(sourceExpressions::add);

      analyzeGroupingOperations(node, sourceExpressions, orderByExpressions);
      List<FunctionCall> aggregates = analyzeAggregations(node, sourceExpressions, orderByExpressions);

      if (!aggregates.isEmpty() && groupByExpressions.isEmpty()) {
        // Have Aggregation functions but no explicit GROUP BY clause
        analysis.setGroupByExpressions(node, ImmutableList.of());
      }

      verifyAggregations(node, sourceScope, orderByScope, groupByExpressions, sourceExpressions, orderByExpressions);

//      analyzeWindowFunctions(node, outputExpressions, orderByExpressions);

      if (analysis.isAggregation(node) && node.getOrderBy().isPresent()) {
        // Create a different scope for ORDER BY expressions when aggregation is present.
        // This is because planner requires scope in order to resolve names against fields.
        // Original ORDER BY scope "sees" FROM query fields. However, during planning
        // and when aggregation is present, ORDER BY expressions should only be resolvable against
        // output scope, group by expressions and aggregation expressions.
        List<GroupingOperation> orderByGroupingOperations = extractExpressions(orderByExpressions, GroupingOperation.class);
        List<FunctionCall> orderByAggregations = extractAggregateFunctions(orderByExpressions, metadata.getFunctionProvider());
        computeAndAssignOrderByScopeWithAggregation(node.getOrderBy().get(), sourceScope, outputScope, orderByAggregations, groupByExpressions, orderByGroupingOperations);
      }
      return outputScope;
    }

    @Override
    protected Scope visitTable(Table table, Scope scope) {
      RelationSqmlType tableHandle = scope.createRelation(Optional.of(table.getName()));
//      if (tableHandle.isEmpty()) {
        //If no Source that emits that object can be identified, throw error
//        throw new RuntimeException(String.format("Could not resolve table %s", table.getName()));
//      }

      RelationSqmlType relation = tableHandle;

      //Get all defined fields
      List<Field> fields = relation.getFields();
      return createAndAssignScope(table, scope, fields);
    }

    @Override
    protected Scope visitTableSubquery(TableSubquery node, Scope context) {
      return node.getQuery().accept(this, context);
    }

    @Override
    protected Scope visitJoin(Join node, Scope scope) {
      Scope left = node.getLeft().accept(this, scope);
      Scope right = node.getRight().accept(this, scope);

      Scope result = createAndAssignScope(node, scope, left.getRelationType()
          .join(right.getRelationType()));

      //Todo verify that an empty criteria on a join can be a valid traversal
      if (node.getType() == Join.Type.CROSS || node.getType() == Join.Type.IMPLICIT || node.getCriteria().isEmpty()) {
        return result;
      }

      JoinCriteria criteria = node.getCriteria().get();
      if (criteria instanceof JoinOn) {
        Expression expression = ((JoinOn) criteria).getExpression();

        // need to register coercions in case when join criteria requires coercion (e.g. join on char(1) = char(2))
        ExpressionAnalysis expressionAnalysis = analyzeExpression(expression, result);
        SqmlType clauseType = expressionAnalysis.getType(expression);
        if (!(clauseType instanceof BooleanSqmlType)) {
          throw new RuntimeException(String.format("JOIN ON clause must evaluate to a boolean: actual type %s", clauseType));
        }
        verifyNoAggregateGroupingFunctions(metadata.getFunctionProvider(), expression, "JOIN clause");

        //todo: restrict grouping criteria
        analysis.setJoinCriteria(node, expression);
      } else {
        throw new RuntimeException("Unsupported join");
      }

      return result;
    }

    @Override
    protected Scope visitAliasedRelation(AliasedRelation relation, Scope scope) {
      Scope relationScope = relation.getRelation().accept(this, scope);

      RelationSqmlType relationType = relationScope.getRelationType();

      RelationSqmlType descriptor = relationType.withAlias(relation.getAlias().getValue());

      return createAndAssignScope(relation, scope, descriptor);
    }

    @Override
    protected Scope visitUnion(Union node, Scope scope) {
      return visitSetOperation(node, scope);
    }

    @Override
    protected Scope visitIntersect(Intersect node, Scope scope) {
      return visitSetOperation(node, scope);
    }

    @Override
    protected Scope visitExcept(Except node, Scope scope) {
      return visitSetOperation(node, scope);
    }

    @Override
    protected Scope visitSetOperation(SetOperation node, Scope scope)
    {
      checkState(node.getRelations().size() >= 2);
      List<Scope> relationScopes = node.getRelations().stream()
          .map(relation -> {
            Scope relationScope = process(relation, scope);
            return createAndAssignScope(relation, scope, relationScope.getRelationType());
          })
          .collect(toImmutableList());

      SqmlType[] outputFieldTypes = relationScopes.get(0).getRelationType().getFields().stream()
          .map(Field::getType)
          .toArray(SqmlType[]::new);
      int outputFieldSize = outputFieldTypes.length;

      for (Scope relationScope : relationScopes) {
        RelationSqmlType relationType = relationScope.getRelationType();
        int descFieldSize = relationType.getFields().size();
        String setOperationName = node.getClass().getSimpleName().toUpperCase(ENGLISH);
        if (outputFieldSize != descFieldSize) {
          throw new RuntimeException(
              String.format(
              "%s query has different number of fields: %d, %d",
              setOperationName,
              outputFieldSize,
              descFieldSize));
        }
        for (int i = 0; i < descFieldSize; i++) {
          /*
          Type descFieldType = relationType.getFieldByIndex(i).getType();
          Optional<Type> commonSuperType = metadata.getTypeManager().getCommonSuperType(outputFieldTypes[i], descFieldType);
          if (!commonSuperType.isPresent()) {
            throw new SemanticException(
                TYPE_MISMATCH,
                node,
                "column %d in %s query has incompatible types: %s, %s",
                i + 1,
                setOperationName,
                outputFieldTypes[i].getDisplayName(),
                descFieldType.getDisplayName());
          }
          outputFieldTypes[i] = commonSuperType.get();
           */
          //Super types?
        }
      }

      Field[] outputDescriptorFields = new Field[outputFieldTypes.length];
      RelationSqmlType firstDescriptor = relationScopes.get(0).getRelationType();
      for (int i = 0; i < outputFieldTypes.length; i++) {
        Field oldField = firstDescriptor.getFields().get(i);
        outputDescriptorFields[i] = new Field(
            oldField.getRelationAlias(),
            oldField.getName(),
            outputFieldTypes[i],
            oldField.isHidden(),
            oldField.getOriginTable(),
            oldField.getOriginColumnName(),
            oldField.isAliased());
      }

      for (int i = 0; i < node.getRelations().size(); i++) {
        Relation relation = node.getRelations().get(i);
        Scope relationScope = relationScopes.get(i);
        RelationSqmlType relationType = relationScope.getRelationType();
        for (int j = 0; j < relationType.getFields().size(); j++) {
          SqmlType outputFieldType = outputFieldTypes[j];
          SqmlType descFieldType = relationType.getFields().get(j).getType();
          if (!outputFieldType.equals(descFieldType)) {
//            analysis.addRelationCoercion(relation, outputFieldTypes);
            throw new RuntimeException(String.format("Mismatched types in set operation %s", relationType.getFields().get(j)));
//            break;
          }
        }
      }

      return createAndAssignScope(node, scope, new ArrayList<>(List.of(outputDescriptorFields)));
    }

    private void verifyAggregations(
        QuerySpecification node,
        Scope sourceScope,
        Optional<Scope> orderByScope,
        List<Expression> groupByExpressions,
        List<Expression> outputExpressions,
        List<Expression> orderByExpressions)
    {
      checkState(orderByExpressions.isEmpty() || orderByScope.isPresent(), "non-empty orderByExpressions list without orderByScope provided");

      if (analysis.isAggregation(node)) {
        // ensure SELECT, ORDER BY and HAVING are constant with respect to group
        // e.g, these are all valid expressions:
        //     SELECT f(a) GROUP BY a
        //     SELECT f(a + 1) GROUP BY a + 1
        //     SELECT a + sum(b) GROUP BY a
        List<Expression> distinctGroupingColumns = groupByExpressions.stream()
            .distinct()
            .collect(toImmutableList());

        for (Expression expression : outputExpressions) {
          verifySourceAggregations(distinctGroupingColumns, sourceScope, expression, metadata, analysis);
        }

        for (Expression expression : orderByExpressions) {
          verifyOrderByAggregations(distinctGroupingColumns, sourceScope, orderByScope.get(), expression, metadata, analysis);
        }
      }
    }

    private List<FunctionCall> analyzeAggregations(
        QuerySpecification node,
        List<Expression> outputExpressions,
        List<Expression> orderByExpressions)
    {
      List<FunctionCall> aggregates = extractAggregateFunctions(Iterables.concat(outputExpressions, orderByExpressions), metadata.getFunctionProvider());
      analysis.setAggregates(node, aggregates);
      return aggregates;
    }


    private void analyzeGroupingOperations(QuerySpecification node, List<Expression> outputExpressions, List<Expression> orderByExpressions)
    {
      List<GroupingOperation> groupingOperations = extractExpressions(Iterables.concat(outputExpressions, orderByExpressions), GroupingOperation.class);
      boolean isGroupingOperationPresent = !groupingOperations.isEmpty();

      if (isGroupingOperationPresent && !node.getGroupBy().isPresent()) {
        throw new RuntimeException(
            "A GROUPING() operation can only be used with a corresponding GROUPING SET/CUBE/ROLLUP/GROUP BY clause");
      }

      analysis.setGroupingOperations(node, groupingOperations);
    }

    private void verifySelectDistinct(QuerySpecification node, List<Expression> outputExpressions)
    {
      for (SortItem item : node.getOrderBy().get().getSortItems()) {
        Expression expression = item.getSortKey();

        //TODO: Verify select distinct rules
//
//        Expression rewrittenOrderByExpression = ExpressionTreeRewriter.rewriteWith(
//            new OrderByExpressionRewriter(extractNamedOutputExpressions(node.getSelect())), expression);
//        int index = outputExpressions.indexOf(rewrittenOrderByExpression);
//        if (index == -1) {
//          throw new SemanticException(ORDER_BY_MUST_BE_IN_SELECT, node.getSelect(), "For SELECT DISTINCT, ORDER BY expressions must appear in select list");
//        }
//        if (!isDeterministic(expression)) {
//          throw new SemanticException(NONDETERMINISTIC_ORDER_BY_EXPRESSION_WITH_SELECT_DISTINCT, expression, "Non deterministic ORDER BY expression is not supported with SELECT DISTINCT");
//        }
      }
    }

    public void analyzeWhere(Node node, Scope scope, Expression predicate)
    {
      ExpressionAnalysis expressionAnalysis = analyzeExpression(predicate, scope);

      verifyNoAggregateGroupingFunctions(metadata.getFunctionProvider(), predicate, "WHERE clause");

      analysis.recordSubqueries(node, expressionAnalysis);

      SqmlType predicateType = expressionAnalysis.getType(predicate);
      if (!(predicateType instanceof BooleanSqmlType)) {
        if (!(predicateType instanceof UnknownSqmlType)) {
          throw new RuntimeException(String.format("WHERE clause must evaluate to a boolean: actual type %s", predicateType));
        }
        // coerce null to boolean
        analysis.addCoercion(predicate, BooleanSqmlType.INSTANCE, false);
      }

      analysis.setWhere(node, predicate);
    }

    private void verifyNoAggregateGroupingFunctions(FunctionProvider functionProvider, Expression predicate, String clause) {
      List<FunctionCall> aggregates = extractAggregateFunctions(ImmutableList.of(predicate), functionProvider);

      List<GroupingOperation> groupingOperations = extractExpressions(ImmutableList.of(predicate), GroupingOperation.class);

      List<Expression> found = ImmutableList.copyOf(Iterables.concat(
          aggregates,
          groupingOperations));

      if (!found.isEmpty()) {
        throw new RuntimeException(String.format(
            "%s cannot contain aggregations, window functions or grouping operations: %s", clause, found));
      }
    }

    private List<FunctionCall> extractAggregateFunctions(Iterable<? extends Node> nodes, FunctionProvider functionProvider) {
      return extractExpressions(nodes, FunctionCall.class, isAggregationPredicate(functionProvider));
    }
    private Predicate<FunctionCall> isAggregationPredicate(FunctionProvider functionProvider) {
      return functionCall -> {
        Optional<SqmlFunction> function = functionProvider.resolve(functionCall.getName());
        return function.orElseThrow(()->
            new RuntimeException(String.format("Could not find function %s", functionCall.getName())))
            .isAggregation();
      };
    }

    public <T extends Expression> List<T> extractExpressions(
        Iterable<? extends Node> nodes,
        Class<T> clazz)
    {
      return extractExpressions(nodes, clazz, alwaysTrue());
    }

    private <T extends Expression> List<T> extractExpressions(
        Iterable<? extends Node> nodes,
        Class<T> clazz,
        Predicate<T> predicate)
    {
      requireNonNull(nodes, "nodes is null");
      requireNonNull(clazz, "clazz is null");
      requireNonNull(predicate, "predicate is null");

      return ImmutableList.copyOf(nodes).stream()
          .flatMap(node -> linearizeNodes(node).stream())
          .filter(clazz::isInstance)
          .map(clazz::cast)
          .filter(predicate)
          .collect(toImmutableList());
    }


    private List<Node> linearizeNodes(Node node)
    {
      ImmutableList.Builder<Node> nodes = ImmutableList.builder();
      new DefaultExpressionTraversalVisitor<Node, Void>()
      {
        @Override
        public Node process(Node node, Void context)
        {
          Node result = super.process(node, context);
          nodes.add(node);
          return result;
        }
      }.process(node, null);
      return nodes.build();
    }

    private List<Expression> analyzeOrderBy(Node node, List<SortItem> sortItems, Scope orderByScope)
    {
      ImmutableList.Builder<Expression> orderByFieldsBuilder = ImmutableList.builder();

      for (SortItem item : sortItems) {
        Expression expression = item.getSortKey();

        ExpressionAnalysis expressionAnalysis = analyzeExpression(expression, orderByScope);
        analysis.recordSubqueries(node, expressionAnalysis);

        orderByFieldsBuilder.add(expression);
      }

      List<Expression> orderByFields = orderByFieldsBuilder.build();
      return orderByFields;
    }

    public List<SortItem> getSortItemsFromOrderBy(Optional<OrderBy> orderBy) {
      return orderBy.map(OrderBy::getSortItems).orElse(ImmutableList.of());
    }

    private Scope createScope(Scope scope) {
      return new Scope(scope.getName());
    }

    private Scope createAndAssignScope(Node node, Scope parentScope, List<Field> fields)
    {
      return createAndAssignScope(node, parentScope, new RelationSqmlType(fields));
    }

    private Scope createAndAssignScope(Node node, Scope parentScope, RelationSqmlType relationType)
    {
      Scope scope = scopeBuilder(parentScope)
          .withRelationType(node, relationType)
          .withName(parentScope.getName())
          .build();

      analysis.setScope(node, scope);


      return scope;
    }

    private Scope.Builder scopeBuilder(Scope parentScope)
    {
      Scope.Builder scopeBuilder = Scope.builder();
      scopeBuilder.withName(parentScope.getName());

      if (parentScope != null) {
        // parent scope represents local query scope hierarchy. Local query scope
        // hierarchy should have outer query scope as ancestor already.
        scopeBuilder.withParent(parentScope);
      }
//      else if (outerQueryScope.isPresent()) {
//        scopeBuilder.withOuterQueryParent(outerQueryScope.get());
//      }

      return scopeBuilder;
    }

    private Scope computeAndAssignOrderByScope(OrderBy node, Scope sourceScope, Scope outputScope)
    {
      // ORDER BY should "see" both output and FROM fields during initial analysis and non-aggregation query planning
      Scope orderByScope = Scope.builder()
          .withParent(sourceScope)
          .withRelationType(node, outputScope.getRelationType())
          .build();
      analysis.setScope(node, orderByScope);
      return orderByScope;
    }

    private Scope computeAndAssignOrderByScopeWithAggregation(OrderBy node, Scope sourceScope, Scope outputScope, List<FunctionCall> aggregations, List<Expression> groupByExpressions, List<GroupingOperation> groupingOperations)
    {
      //todo
//
//      // This scope is only used for planning. When aggregation is present then
//      // only output fields, groups and aggregation expressions should be visible from ORDER BY expression
//      ImmutableList.Builder<Expression> orderByAggregationExpressionsBuilder = ImmutableList.<Expression>builder()
//          .addAll(groupByExpressions)
//          .addAll(aggregations)
//          .addAll(groupingOperations);
//
//      // Don't add aggregate complex expressions that contains references to output column because the names would clash in TranslationMap during planning.
//      List<Expression> orderByExpressionsReferencingOutputScope = AstUtils.preOrder(node)
//          .filter(Expression.class::isInstance)
//          .map(Expression.class::cast)
//          .filter(expression -> hasReferencesToScope(expression, analysis, outputScope))
//          .collect(toImmutableList());
//      List<Expression> orderByAggregationExpressions = orderByAggregationExpressionsBuilder.build().stream()
//          .filter(expression -> !orderByExpressionsReferencingOutputScope.contains(expression) || analysis.isColumnReference(expression))
//          .collect(toImmutableList());
//
//      // generate placeholder fields
//      Set<Field> seen = new HashSet<>();
//      List<Field> orderByAggregationSourceFields = orderByAggregationExpressions.stream()
//          .map(expression -> {
//            // generate qualified placeholder field for GROUP BY expressions that are column references
//            Optional<Field> sourceField = sourceScope.tryResolveField(expression)
//                .filter(resolvedField -> seen.add(resolvedField.getField()))
//                .map(ResolvedField::getField);
//            return sourceField
//                .orElse(Field.newUnqualified(Optional.empty(), analysis.getType(expression).get()));
//          })
//          .collect(toImmutableList());
//
//      Scope orderByAggregationScope = Scope.builder()
//          .withRelationType(node, new RelationSqmlType(orderByAggregationSourceFields))
//          .build();
//
//      Scope orderByScope = Scope.builder()
//          .withParent(orderByAggregationScope)
//          .withRelationType(node, outputScope.getRelationType())
//          .build();
//      analysis.setScope(node, orderByScope);
//      analysis.setOrderByAggregates(node, orderByAggregationExpressions);
      return outputScope;
    }
    private Scope computeAndAssignOutputScope(QuerySpecification node, Scope scope,
        Scope sourceScope)
    {
      Builder<Field> outputFields = ImmutableList.builder();

      for (SelectItem item : node.getSelect().getSelectItems()) {
        if (item instanceof AllColumns) {
          // expand * and T.*
          Optional<QualifiedName> starPrefix = ((AllColumns) item).getPrefix();

          for (Field field : sourceScope.getRelationType().resolveFieldsWithPrefix(starPrefix)) {
            outputFields.add(Field.newUnqualified(field.getName(), field.getType(), field.getOriginTable(), field.getOriginColumnName(), false));
          }
        } else if (item instanceof SingleColumn) {
          SingleColumn column = (SingleColumn) item;

          Expression expression = column.getExpression();
          Optional<Identifier> field = column.getAlias();

          Optional<QualifiedObjectName> originTable = Optional.empty();
          Optional<String> originColumn = Optional.empty();
          QualifiedName name = null;

          if (expression instanceof Identifier) {
            name = QualifiedName.of(((Identifier) expression).getValue());
          }
          else if (expression instanceof DereferenceExpression) {
            name = DereferenceExpression.getQualifiedName((DereferenceExpression) expression);
          }

          if (name != null) {
            List<Field> matchingFields = sourceScope.resolveFields(name);
            if (!matchingFields.isEmpty()) {
              originTable = matchingFields.get(0).getOriginTable();
              originColumn = matchingFields.get(0).getOriginColumnName();
            }
          }

          if (field.isEmpty()) {
            if (name != null) {
              field = Optional.of(new Identifier(getLast(name.getOriginalParts())));
            }
          }

         field.ifPresent(f->analysis.addName(expression, f.getValue()));

          outputFields.add(Field.newUnqualified(field.map(Identifier::getValue),
              analysis.getType(expression).orElse(new UnknownSqmlType()), originTable, originColumn,
              column.getAlias().isPresent()));
        }
        else {
          throw new IllegalArgumentException("Unsupported SelectItem type: " + item.getClass().getName());
        }
      }

      return createAndAssignScope(node, scope, outputFields.build());
    }
    private void analyzeHaving(QuerySpecification node, Scope scope) {
      if (node.getHaving().isPresent()) {
        Expression predicate = node.getHaving().get();

        ExpressionAnalysis expressionAnalysis = analyzeExpression(predicate, scope);
//
//        expressionAnalysis.getWindowFunctions().stream()
//            .findFirst()
//            .ifPresent(function -> {
//              throw new SemanticException(NESTED_WINDOW, function.getNode(), "HAVING clause cannot contain window functions");
//            });

        analysis.recordSubqueries(node, expressionAnalysis);

        SqmlType predicateType = expressionAnalysis.getType(predicate);
        if (!(predicateType instanceof SqmlType.BooleanSqmlType) && !(predicateType instanceof SqmlType.UnknownSqmlType)) {
          throw new RuntimeException(String.format("HAVING clause must evaluate to a boolean: actual type %s", predicateType));
        }

        analysis.setHaving(node, predicate);
      }
    }

    private List<Expression> analyzeSelect(QuerySpecification node, Scope scope) {
      List<Expression> outputExpressions = new ArrayList<>();

      for (SelectItem item : node.getSelect().getSelectItems()) {
        if (item instanceof AllColumns) {
          Optional<QualifiedName> starPrefix = ((AllColumns) item).getPrefix();

          RelationSqmlType relationType = scope.getRelationType();
          List<Field> fields = relationType.resolveFieldsWithPrefix(starPrefix);

          for (Field field : fields) {
            Identifier identifier = new Identifier(field.getName().get());
            analysis.addName(identifier, field.getName().get());
            analyzeExpression(identifier, scope);
            outputExpressions.add(identifier);
          }
        } else if (item instanceof SingleColumn) {
          SingleColumn column = (SingleColumn) item;
          //creates expression to type mapping
          ExpressionAnalysis expressionAnalysis = analyzeExpression(column.getExpression(), scope);

          analysis.recordSubqueries(node, expressionAnalysis);
          outputExpressions.add(column.getExpression());

          //SqmlType type = expressionAnalysis.getType(column.getExpression());
          //if (node.getSelect().isDistinct() && !type.isComparable()) {
          //  throw new SemanticException(TYPE_MISMATCH, node.getSelect(), "DISTINCT can only be applied to comparable types (actual: %s): %s", type, column.getExpression());
          //}
        }
        else {
          throw new IllegalArgumentException(String.format("Unsupported SelectItem type: %s", item.getClass().getName()));
        }
      }

      analysis.setOutputExpressions(node, outputExpressions);

      return outputExpressions;
    }


    private List<Expression> analyzeGroupBy(QuerySpecification node, Scope scope, List<Expression> outputExpressions)
    {
      if (node.getGroupBy().isPresent()) {
        List<List<Set<Object>>> sets = new ArrayList();
        //List<Expression> complexExpressions = new ArrayList();
        List<Expression> groupingExpressions = new ArrayList();

        for (GroupingElement groupingElement : node.getGroupBy().get().getGroupingElements()) {
          for (Expression column : groupingElement.getExpressions()) {

            analyzeExpression(column, scope);

//            if (analysis.getColumnReferences().containsKey(NodeRef.of(column))) {
//              sets.add(ImmutableList.of(ImmutableSet.copyOf(analysis.getColumnReferences().get(NodeRef.of(column)))));
//            } else {
//              throw new RuntimeException("TBD complex group by expressions");
              //verifyNoAggregateWindowOrGroupingFunctions(analysis.getFunctionHandles(), metadata.getFunctionAndTypeManager(), column, "GROUP BY clause");
//              analysis.recordSubqueries(node, analyzeExpression(column, scope));
//              complexExpressions.add(column);
//            }

            groupingExpressions.add(column);
          }
        }

        analysis.setGroupByExpressions(node, groupingExpressions);
        analysis.setGroupingSets(node, sets);

        return groupingExpressions;
      }

      return ImmutableList.of();
    }

    private ExpressionAnalysis analyzeExpression(Expression expression, Scope scope) {
      ExpressionAnalyzer analyzer = new ExpressionAnalyzer(metadata);
      ExpressionAnalysis exprAnalysis = analyzer.analyze(expression, scope);

      analysis.addTypes(exprAnalysis.getExpressionTypes());

      return exprAnalysis;
    }

    private Scope analyzeFrom(QuerySpecification node, Scope scope)
    {
      if (node.getFrom().isPresent()) {
        return node.getFrom().get().accept(this, scope);
      }

      return createScope(scope);
    }
  }
}