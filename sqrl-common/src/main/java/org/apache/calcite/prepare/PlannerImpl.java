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
package org.apache.calcite.prepare;

import ai.datasqrl.SqrlCalciteCatalogReader;
import ai.datasqrl.plan.calcite.PlannerFactory;
import ai.datasqrl.plan.calcite.rules.EnumerableNestedLoopJoinRule;
import ai.datasqrl.plan.calcite.rules.SqrlRelMetadataProvider;
import ai.datasqrl.plan.calcite.rules.SqrlRelMetadataQuery;
import lombok.Getter;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.config.CalciteSystemProperty;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.jdbc.SqrlCalciteSchema;
import org.apache.calcite.plan.*;
import org.apache.calcite.plan.RelOptTable.ViewExpander;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexExecutor;
import org.apache.calcite.runtime.Hook;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.sql2rel.RelDecorrelator;
import org.apache.calcite.sql2rel.SqlRexConvertletTable;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.tools.*;
import org.apache.calcite.util.Pair;
import org.apache.flink.calcite.shaded.com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Reader;
import java.util.List;

import static java.util.Objects.requireNonNull;

/*
 * Copied from Calcite.
 * 
 * SQRL Changelog:
 *  - Change private modifiers to protected
 *  - Getters for all properties
 *  - remove createSqlValidator config override to allow SQRL conformance
 *  - TypeFactory to something that can handle Instants
 */
/** Implementation of {@link org.apache.calcite.tools.Planner}. */
@Getter
public class PlannerImpl implements Planner, ViewExpander {
  protected final SqlOperatorTable operatorTable;
  protected final ImmutableList<Program> programs;
  protected final @Nullable RelOptCostFactory costFactory;
  protected final Context context;
  protected final CalciteConnectionConfig connectionConfig;
  protected final RelDataTypeSystem typeSystem;

  /** Holds the trait definitions to be registered with planner. May be null. */
  protected final @Nullable ImmutableList<RelTraitDef> traitDefs;

  protected final SqlParser.Config parserConfig;
  protected final SqlValidator.Config sqlValidatorConfig;
  protected final SqlToRelConverter.Config sqlToRelConverterConfig;
  protected final SqlRexConvertletTable convertletTable;

  protected State state;

  // set in STATE_1_RESET
  @SuppressWarnings("unused")
  protected boolean open;

  // set in STATE_2_READY
  protected @Nullable SchemaPlus defaultSchema;
  protected @Nullable RelDataTypeFactory typeFactory;
  protected @Nullable RelOptPlanner planner;
  protected @Nullable RexExecutor executor;

  // set in STATE_4_VALIDATE
  protected @Nullable SqlValidator validator;
  protected @Nullable SqlNode validatedSqlNode;

  final RelOptCluster cluster;
  /** Creates a planner. Not a public API; call
   * {@link org.apache.calcite.tools.Frameworks#getPlanner} instead. */
  @SuppressWarnings("method.invocation.invalid")
  public PlannerImpl(FrameworkConfig config) {
    this.costFactory = config.getCostFactory();
    this.defaultSchema = config.getDefaultSchema();
    this.operatorTable = config.getOperatorTable();
    this.programs = config.getPrograms();
    this.parserConfig = config.getParserConfig();
    this.sqlValidatorConfig = config.getSqlValidatorConfig();
    this.sqlToRelConverterConfig = config.getSqlToRelConverterConfig();
    this.state = State.STATE_0_CLOSED;
    this.traitDefs = config.getTraitDefs();
    this.convertletTable = config.getConvertletTable();
    this.executor = config.getExecutor();
    this.context = config.getContext();
    this.connectionConfig = connConfig(context, parserConfig);
    this.typeSystem = config.getTypeSystem();
    this.planner = new VolcanoPlanner(costFactory, context);
    //add default traits
    traitDefs.forEach(planner::addRelTraitDef);

    this.typeFactory = PlannerFactory.getTypeFactory();
    final RexBuilder rexBuilder = createRexBuilder();
    cluster = RelOptCluster.create(
        requireNonNull(planner, "planner"),
        rexBuilder);
    final SqlToRelConverter.Config sqlToRelConfig =
        sqlToRelConverterConfig.withTrimUnusedFields(false);
    cluster.setHintStrategies(sqlToRelConfig.getHintStrategyTable());
    reset();
  }

  /** Gets a user-defined config and appends default connection values. */
  protected static CalciteConnectionConfig connConfig(Context context,
      SqlParser.Config parserConfig) {
    CalciteConnectionConfigImpl config =
        context.unwrap(CalciteConnectionConfigImpl.class);
    if (config == null) {
      config = CalciteConnectionConfig.DEFAULT;
    }
    if (!config.isSet(CalciteConnectionProperty.CASE_SENSITIVE)) {
      config = config.set(CalciteConnectionProperty.CASE_SENSITIVE,
          String.valueOf(parserConfig.caseSensitive()));
    }
    if (!config.isSet(CalciteConnectionProperty.CONFORMANCE)) {
      config = config.set(CalciteConnectionProperty.CONFORMANCE,
          String.valueOf(parserConfig.conformance()));
    }
    return config;
  }

  /** Makes sure that the state is at least the given state. */
  protected void ensure(State state) {
    if (state == this.state) {
      return;
    }
    if (state.ordinal() < this.state.ordinal()) {
      throw new IllegalArgumentException("cannot move to " + state + " from "
          + this.state);
    }
    state.from(this);
  }

  @Override public RelTraitSet getEmptyTraitSet() {
    return requireNonNull(planner, "planner").emptyTraitSet();
  }

  @Override public void close() {
    open = false;
    state = State.STATE_0_CLOSED;
  }

  @Override public void reset() {
    ensure(State.STATE_0_CLOSED);
    open = true;
    state = State.STATE_1_RESET;
  }

  public void ready() {
    switch (state) {
    case STATE_0_CLOSED:
      reset();
      break;
    default:
      break;
    }
    ensure(State.STATE_1_RESET);

    RelOptPlanner planner = this.planner;
    RelOptUtil.registerDefaultRules(planner,
        connectionConfig.materializationsEnabled(),
        Hook.ENABLE_BINDABLE.get(false));
    planner.addRule(EnumerableNestedLoopJoinRule.INSTANCE);
    planner.setExecutor(executor);

    state = State.STATE_2_READY;

    // If user specify own traitDef, instead of default default trait,
    // register the trait def specified in traitDefs.
    if (this.traitDefs == null) {
      planner.addRelTraitDef(ConventionTraitDef.INSTANCE);
      if (CalciteSystemProperty.ENABLE_COLLATION_TRAIT.value()) {
        planner.addRelTraitDef(RelCollationTraitDef.INSTANCE);
      }
    } else {
      for (RelTraitDef def : this.traitDefs) {
        planner.addRelTraitDef(def);
      }
    }
  }

  @Override public SqlNode parse(final Reader reader) throws SqlParseException {
    switch (state) {
    case STATE_0_CLOSED:
    case STATE_1_RESET:
      ready();
      break;
    default:
      break;
    }
    ensure(State.STATE_2_READY);
    SqlParser parser = SqlParser.create(reader, parserConfig);
    SqlNode sqlNode = parser.parseStmt();
    state = State.STATE_3_PARSED;
    return sqlNode;
  }

  @EnsuresNonNull("validator")
  @Override public SqlNode validate(SqlNode sqlNode) throws ValidationException {
//    ensure(State.STATE_3_PARSED);
    this.validator = createSqlValidator(createCatalogReader());
    try {
      validatedSqlNode = validator.validate(sqlNode);
    } catch (RuntimeException e) {
      throw new ValidationException(e);
    }
    state = State.STATE_4_VALIDATED;
    return validatedSqlNode;
  }

  @Override public Pair<SqlNode, RelDataType> validateAndGetType(SqlNode sqlNode)
      throws ValidationException {
    final SqlNode validatedNode = this.validate(sqlNode);
    final RelDataType type =
        this.validator.getValidatedNodeType(validatedNode);
    return Pair.of(validatedNode, type);
  }

  @SuppressWarnings("deprecation")
  @Override public final RelNode convert(SqlNode sql) {
    return rel(sql).rel;
  }

  @Override public RelRoot rel(SqlNode sql) {
    ensure(State.STATE_4_VALIDATED);
    SqlNode validatedSqlNode = requireNonNull(this.validatedSqlNode,
        "validatedSqlNode is null. Need to call #validate() first");
    final SqlToRelConverter.Config config =
        sqlToRelConverterConfig.withTrimUnusedFields(false);
    final SqlToRelConverter sqlToRelConverter =
        new SqlToRelConverter(this, validator,
            createCatalogReader(), cluster, convertletTable, config);
    RelRoot root =
        sqlToRelConverter.convertQuery(validatedSqlNode, false, true);
//    root = root.withRel(sqlToRelConverter.flattenTypes(root.rel, true));
    final RelBuilder relBuilder =
        config.getRelBuilderFactory().create(cluster, null);
    root = root.withRel(
        RelDecorrelator.decorrelateQuery(root.rel, relBuilder));
    state = State.STATE_5_CONVERTED;
    return root;
  }

  // CHECKSTYLE: IGNORE 2
  /** @deprecated Now {@link PlannerImpl} implements {@link ViewExpander}
   * directly. */
  @Deprecated // to be removed before 2.0
  public class ViewExpanderImpl implements ViewExpander {
    ViewExpanderImpl() {
    }

    @Override public RelRoot expandView(RelDataType rowType, String queryString,
        List<String> schemaPath, @Nullable List<String> viewPath) {
      return PlannerImpl.this.expandView(rowType, queryString, schemaPath,
          viewPath);
    }
  }

  @Override public RelRoot expandView(RelDataType rowType, String queryString,
      List<String> schemaPath, @Nullable List<String> viewPath) {
    RelOptPlanner planner = this.planner;
    if (planner == null) {
      ready();
      planner = requireNonNull(this.planner, "planner");
    }
    SqlParser parser = SqlParser.create(queryString, parserConfig);
    SqlNode sqlNode;
    try {
      sqlNode = parser.parseQuery();
    } catch (SqlParseException e) {
      throw new RuntimeException("parse failed", e);
    }

    final CalciteCatalogReader catalogReader =
        createCatalogReader().withSchemaPath(schemaPath);
    final SqlValidator validator = createSqlValidator(catalogReader);

    final SqlToRelConverter.Config config =
        sqlToRelConverterConfig.withTrimUnusedFields(false);
    final SqlToRelConverter sqlToRelConverter =
        new SqlToRelConverter(this, validator,
            catalogReader, cluster, convertletTable, config);

    final RelRoot root =
        sqlToRelConverter.convertQuery(sqlNode, true, false);
    final RelRoot root2 =
        root.withRel(sqlToRelConverter.flattenTypes(root.rel, true));
    final RelBuilder relBuilder =
        config.getRelBuilderFactory().create(cluster, null);
    return root2.withRel(
        RelDecorrelator.decorrelateQuery(root.rel, relBuilder));
  }

  // CalciteCatalogReader is stateless; no need to store one
  public CalciteCatalogReader createCatalogReader() {
    SchemaPlus defaultSchema = requireNonNull(this.defaultSchema, "defaultSchema");
    final SchemaPlus rootSchema = rootSchema(defaultSchema);

    return new SqrlCalciteCatalogReader(
        defaultSchema.unwrap(SqrlCalciteSchema.class),
        CalciteSchema.from(defaultSchema).path(null),
        getTypeFactory(), connectionConfig);
  }

  protected SqlValidator createSqlValidator(CalciteCatalogReader catalogReader) {
    return SqlValidatorUtil.newValidator(
        PlannerFactory.getOperatorTable(),
        catalogReader,
        getTypeFactory(),
        sqlValidatorConfig
    );
  }

  protected static SchemaPlus rootSchema(SchemaPlus schema) {
    for (;;) {
      SchemaPlus parentSchema = schema.getParentSchema();
      if (parentSchema == null) {
        return schema;
      }
      schema = parentSchema;
    }
  }

  // RexBuilder is stateless; no need to store one
  protected RexBuilder createRexBuilder() {
    return new RexBuilder(getTypeFactory());
  }

  @Override public RelDataTypeFactory getTypeFactory() {
    return requireNonNull(typeFactory, "typeFactory");
  }

  @SuppressWarnings("deprecation")
  @Override public RelNode transform(int ruleSetIndex, RelTraitSet requiredOutputTraits,
      RelNode rel) {
//    ensure(State.STATE_5_CONVERTED);
    rel.getCluster().setMetadataProvider(
        new org.apache.calcite.rel.metadata.CachingRelMetadataProvider(
            new SqrlRelMetadataProvider(),
            rel.getCluster().getPlanner()));
    rel.getCluster().setMetadataQuerySupplier(SqrlRelMetadataQuery::new);
    Program program = programs.get(ruleSetIndex);
    return program.run(requireNonNull(planner, "planner"),
        rel, requiredOutputTraits, ImmutableList.of(),
        ImmutableList.of());
  }

  /** Stage of a statement in the query-preparation lifecycle. */
  protected enum State {
    STATE_0_CLOSED {
      @Override void from(PlannerImpl planner) {
        planner.close();
      }
    },
    STATE_1_RESET {
      @Override void from(PlannerImpl planner) {
        planner.ensure(STATE_0_CLOSED);
        planner.reset();
      }
    },
    STATE_2_READY {
      @Override void from(PlannerImpl planner) {
        STATE_1_RESET.from(planner);
        planner.ready();
      }
    },
    STATE_3_PARSED,
    STATE_4_VALIDATED,
    STATE_5_CONVERTED;

    /** Moves planner's state to this state. This must be a higher state. */
    void from(PlannerImpl planner) {
      throw new IllegalArgumentException("cannot move from " + planner.state
          + " to " + this);
    }
  }
}