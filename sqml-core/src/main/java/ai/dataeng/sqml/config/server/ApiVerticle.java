package ai.dataeng.sqml.config.server;

import ai.dataeng.sqml.Environment;
import ai.dataeng.sqml.io.sources.DataSource;
import ai.dataeng.sqml.io.sources.DataSourceConfiguration;
import ai.dataeng.sqml.io.sources.dataset.SourceDataset;
import ai.dataeng.sqml.io.sources.impl.file.FileSourceConfiguration;
import ai.dataeng.sqml.type.basic.ProcessMessage;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import io.vertx.ext.web.validation.RequestParameters;
import io.vertx.ext.web.validation.ValidationHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class ApiVerticle extends AbstractVerticle {

    private static final Map<Integer,String> ERROR2MESSAGE = ImmutableMap.of(
            400, "Processing Error",
            404, "Not Found",
            405, "Validation Error"
    );


    private HttpServer server;

    private final Environment environment;
    private final int port = 8080;

    public ApiVerticle(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        RouterBuilder.create(this.vertx, "datasqrl-openapi.yml")
                .onSuccess(routerBuilder -> {
                    // Add routes handlers
                    List<JsonObject> sources = environment.getDatasetRegistry().getDatasets().stream()
                            .map(SourceDataset::getSource)
                            .map(ApiVerticle::source2Json).collect(Collectors.toList());
                    routerBuilder.operation("getSources").handler(routingContext ->
                            routingContext
                                    .response()
                                    .setStatusCode(200)
                                    .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                                    .end(new JsonArray(sources).encode())
                    );
                    routerBuilder.operation("addOrUpdateFileSource").handler(new SourceAddOrUpdateHandler<>(
                            FileSourceConfiguration.class));
                    routerBuilder.operation("getSourceByName").handler(routingContext -> {
                        RequestParameters params = routingContext.get("parsedParameters");
                        String sourceName = params.pathParameter("sourceName").getString();
                        SourceDataset ds = environment.getDatasetRegistry().getDataset(sourceName);
                        if (ds!=null) {
                            JsonObject result = source2Json(ds.getSource());
                            routingContext
                                    .response()
                                    .setStatusCode(200)
                                    .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                                    .end(result.encode());
                        } else {
                            routingContext.fail(404, new Exception("Source not found"));
                        }
                    });
                    routerBuilder.operation("deleteSource").handler(routingContext -> {
                        RequestParameters params = routingContext.get("parsedParameters");
                        String sourceName = params.pathParameter("sourceName").getString();
//                        if (source.isPresent())
//                            routingContext
//                                    .response()
//                                    .setStatusCode(200)
//                                    .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
//                                    .end();
//                        else
                            routingContext.fail(404, new Exception("Not yet implemented")); // <5>
                    });

                    Router router = routerBuilder.createRouter(); // <1>
                    //Generate error handlers
                    for (Map.Entry<Integer,String> failure : ERROR2MESSAGE.entrySet()) {
                        int errorCode = failure.getKey();
                        Preconditions.checkArgument(errorCode>=400 && errorCode<410);
                        String defaultMessage = failure.getValue();
                        Preconditions.checkArgument(StringUtils.isNotEmpty(defaultMessage));

                        router.errorHandler(errorCode, routingContext -> {
                            Throwable exception = routingContext.failure();
                            JsonObject errorObject = new JsonObject()
                                    .put("code", errorCode)
                                    .put("message", exception!=null?exception.getMessage():defaultMessage
                                    );
                            routingContext
                                    .response()
                                    .setStatusCode(errorCode)
                                    .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                                    .end(errorObject.encode());
                        });
                    }
                    server = vertx.createHttpServer(new HttpServerOptions().setPort(port).setHost("localhost"));
                    server.requestHandler(router).listen();
                    startPromise.complete();
                })
                .onFailure(startPromise::fail);
    }

    private static JsonObject source2Json(DataSource source) {
        DataSourceConfiguration sourceConfig = source.getConfiguration();
        JsonObject base = JsonObject.mapFrom(sourceConfig);
        base.put("sourceName", source.getDatasetName().getDisplay());
        if (sourceConfig instanceof FileSourceConfiguration) {
            base.put("objectType", "FileSourceConfig");
        } else throw new UnsupportedOperationException("Unexpected source config: " + sourceConfig.getClass());
        return base;
    }

    @AllArgsConstructor
    private class SourceAddOrUpdateHandler<S extends DataSourceConfiguration> implements Handler<RoutingContext> {

        private final Class<S> clazz;

        @Override
        public void handle(RoutingContext routingContext) {
            RequestParameters params = routingContext.get(ValidationHandler.REQUEST_CONTEXT_KEY);
            JsonObject source = params.body().getJsonObject();
            S sourceConfig = source.mapTo(clazz);
            ProcessMessage.ProcessBundle errors = new ProcessMessage.ProcessBundle<>();
            DataSource result = environment.getDatasetRegistry().addOrUpdateSource(sourceConfig, errors);
            if (errors.isFatal() || result==null) {
                routingContext.fail(405, new Exception(errors.combineMessages(ProcessMessage.Severity.FATAL,
                        "Provided configuration has the following validation errors:\n","\n" )));
            } else {
                JsonObject jsonResult = source2Json(result);
                routingContext
                        .response()
                        .setStatusCode(200)
                        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                        .end(jsonResult.encode());
            }
        }
    }



    @Override
    public void stop(){
        this.server.close();
    }

}