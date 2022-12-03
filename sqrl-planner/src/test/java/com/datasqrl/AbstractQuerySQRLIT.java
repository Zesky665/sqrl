package com.datasqrl;

import com.datasqrl.config.provider.JDBCConnectionProvider;
import com.datasqrl.graphql.GraphQLServer;
import com.datasqrl.graphql.inference.AbstractSchemaInferenceModelTest;
import com.datasqrl.graphql.server.Model.RootGraphqlModel;
import com.datasqrl.graphql.util.ReplaceGraphqlQueries;
import com.datasqrl.physical.PhysicalPlan;
import com.datasqrl.physical.PhysicalPlanExecutor;
import com.datasqrl.physical.database.relational.JDBCPhysicalPlan;
import com.datasqrl.plan.global.DAGPlanner;
import com.datasqrl.plan.global.OptimizedDAG;
import com.datasqrl.plan.local.generate.Resolve;
import com.datasqrl.plan.queries.APIQuery;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.PoolOptions;
import lombok.SneakyThrows;
import org.apache.calcite.sql.ScriptNode;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(VertxExtension.class)
public class AbstractQuerySQRLIT extends AbstractPhysicalSQRLIT {

    protected Vertx vertx;
    protected VertxTestContext vertxContext;

    @SneakyThrows
    protected void validateSchemaAndQueries(String script, String schema, Map<String,String> queries) {
        ScriptNode node = parse(script);
        Resolve.Env resolvedDag = resolve.planDag(session, node);
        DAGPlanner dagPlanner = new DAGPlanner(planner, session.getPipeline());

        Pair<RootGraphqlModel, List<APIQuery>> modelAndQueries = AbstractSchemaInferenceModelTest.getModelAndQueries(resolvedDag,schema);

        OptimizedDAG dag = dagPlanner.plan(resolvedDag.getRelSchema(), modelAndQueries.getRight(),
                resolvedDag.getExports());

        PhysicalPlan physicalPlan = physicalPlanner.plan(dag);

        RootGraphqlModel model = modelAndQueries.getKey();
        ReplaceGraphqlQueries replaceGraphqlQueries = new ReplaceGraphqlQueries(physicalPlan.getDatabaseQueries());
        model.accept(replaceGraphqlQueries, null);
        snapshot.addContent(physicalPlan.getPlans(JDBCPhysicalPlan.class).findFirst().get().getDdlStatements().stream().map(ddl -> ddl.toSql())
                .sorted().collect(Collectors.joining(System.lineSeparator())),"database");

        PhysicalPlanExecutor executor = new PhysicalPlanExecutor();
        executor.execute(physicalPlan);

        Checkpoint serverStarted = vertxContext.checkpoint();
        Checkpoint queryResponse = vertxContext.checkpoint(queries.size());
        Map<String, String> queryResults = new ConcurrentHashMap<>();
        vertx.deployVerticle(new GraphQLServer(model, toPgOptions(jdbc), 8888, new PoolOptions()), vertxContext.succeeding(server -> {
            serverStarted.flag();
            WebClient client = getGraphQLClient();
            for (Map.Entry<String,String> query : queries.entrySet()) {
                processGraphQLQuery(client, query.getValue(), result -> {
                    queryResults.put(query.getKey(),result);
                    queryResponse.flag();
                });
            }
        }));

        vertxContext.awaitCompletion(5, TimeUnit.SECONDS);
        //We process the results after completion to make sure the order of the queries is preserved for comparability
        for (Map.Entry<String,String> query : queries.entrySet()) {
            snapshot.addContent(queryResults.get(query.getKey()), "query-" + query.getKey());
        }
        snapshot.createOrValidate();
    }

    private PgConnectOptions toPgOptions(JDBCConnectionProvider jdbcConf) {
        PgConnectOptions options = new PgConnectOptions();
        options.setDatabase(jdbcConf.getDatabaseName());
        options.setHost(jdbcConf.getHost());
        options.setPort(jdbcConf.getPort());
        options.setUser(jdbcConf.getUser());
        options.setPassword(jdbcConf.getPassword());
        options.setCachePreparedStatements(true);
        options.setPipeliningLimit(100_000);
        return options;
    }

    protected WebClient getGraphQLClient() {
        WebClientOptions options = new WebClientOptions()
                .setUserAgent(WebClientOptions.loadUserAgent())
                .setDefaultHost("localhost")
                .setDefaultPort(8888);
        return WebClient.create(vertx,options);
    }

    protected void processGraphQLQuery(WebClient client, String query, Consumer<String> resultHandler) {
        client.post("/graphql")
                        .sendJson(Map.of("query", query))
                .onComplete(vertxContext.succeeding(data -> vertxContext.verify(() -> {
                    assertNotNull(data);
                    resultHandler.accept(data.bodyAsString());
                })));
    }
}