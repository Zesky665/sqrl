package ai.dataeng.sqml;

import ai.dataeng.sqml.db.keyvalue.HierarchyKeyValueStore;
import ai.dataeng.sqml.db.keyvalue.LocalFileHierarchyKeyValueStore;
import ai.dataeng.sqml.execution.SQMLBundle;
import ai.dataeng.sqml.flink.EnvironmentProvider;
import ai.dataeng.sqml.flink.DefaultEnvironmentProvider;
import ai.dataeng.sqml.flink.SaveToKeyValueStoreSink;
import ai.dataeng.sqml.flink.util.BufferedLatestSelector;
import ai.dataeng.sqml.flink.util.FlinkUtilities;
import ai.dataeng.sqml.ingest.*;
import ai.dataeng.sqml.source.SourceDataset;
import ai.dataeng.sqml.source.SourceRecord;
import ai.dataeng.sqml.source.SourceTable;
import ai.dataeng.sqml.source.simplefile.DirectoryDataset;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.accumulators.LongCounter;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.functions.ReduceFunction;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.streaming.api.functions.sink.PrintSinkFunction;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.apache.flink.types.RowKind;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class Main2 {

    public static final Path RETAIL_DIR = Path.of(System.getProperty("user.dir")).resolve("sqml-examples").resolve("retail");
    public static final String RETAIL_DATA_DIR_NAME = "ecommerce-data";
    public static final Path RETAIL_DATA_DIR = RETAIL_DIR.resolve(RETAIL_DATA_DIR_NAME);
    public static final String RETAIL_SCRIPT_NAME = "c360";
    public static final Path RETAIL_SCRIPT_DIR = RETAIL_DIR.resolve(RETAIL_SCRIPT_NAME);
    public static final String SQML_SCRIPT_EXTENSION = ".sqml";

    public static final String[] RETAIL_TABLE_NAMES = { "Customer", "Order", "Product"};

    public static final Path outputBase = Path.of("tmp","datasource");

    private static final EnvironmentProvider envProvider = new DefaultEnvironmentProvider();

    public static void main(String[] args) throws Exception {
        HierarchyKeyValueStore.Factory kvStoreFactory = new LocalFileHierarchyKeyValueStore.Factory(outputBase.toString());
        DataSourceRegistry ddRegistry = new DataSourceRegistry(kvStoreFactory);
        DirectoryDataset dd = new DirectoryDataset(RETAIL_DATA_DIR);
        ddRegistry.addDataset(dd);

//        collectStats(ddRegistry);
        simpleDBPipeline(ddRegistry);

//        simpleTest();
    }

    public static void simpleTest() throws Exception {
        StreamExecutionEnvironment flinkEnv = StreamExecutionEnvironment.getExecutionEnvironment();
        flinkEnv.setRuntimeMode(RuntimeExecutionMode.STREAMING);

        DataStream<Integer> integers = flinkEnv.fromElements(12, 5);

//        DataStream<Row> rows = integers.map(i -> Row.of("Name"+i, i));

//  This alternative way of constructing this data stream produces the expected table schema
        Row row1 = Row.of("Mary",5);
        TypeInformation[] typeArray = new TypeInformation[2];
        typeArray[0] = BasicTypeInfo.STRING_TYPE_INFO;
        typeArray[1] = BasicTypeInfo.INT_TYPE_INFO;
        RowTypeInfo typeinfo = new RowTypeInfo(typeArray,new String[]{"name","number"});
        DataStream<Row> rows = flinkEnv.fromCollection(
              List.of(row1), typeinfo
        );

        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(flinkEnv);
        Table table = tableEnv.fromDataStream(rows);
        table.printSchema();

        rows.addSink(new PrintSinkFunction<>());

        flinkEnv.execute();
    }

    public static void simpleDBPipeline(DataSourceRegistry ddRegistry) throws Exception {
        StreamExecutionEnvironment flinkEnv = envProvider.get();

        SourceDataset dd = ddRegistry.getDataset(RETAIL_DATA_DIR_NAME);
        SourceTable stable = dd.getTable(RETAIL_TABLE_NAMES[0]);

        SourceTableStatistics tableStats = ddRegistry.getTableStatistics(stable);
        SourceTableSchema tableSchema = tableStats.getSchema();

        DataStream<SourceRecord> stream = stable.getDataStream(flinkEnv);
        final OutputTag<SchemaValidationError> schemaErrorTag = new OutputTag<>("schema-error"){};
        SingleOutputStreamOperator<SourceRecord> validate = stream.process(new SchemaValidationProcess(schemaErrorTag, tableSchema, SchemaAdjustmentSettings.DEFAULT));
        RecordShredder shredder = new RecordShredder(NamePath.ROOT, tableSchema);
        SingleOutputStreamOperator<Row> process = validate.flatMap(shredder,shredder.getTypeInfo());


        validate.getSideOutput(schemaErrorTag).addSink(new PrintSinkFunction<>()); //TODO: handle errors

        process.addSink(new PrintSinkFunction<>());

        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(flinkEnv);
        Table table = tableEnv.fromDataStream(process/*, Schema.newBuilder()
                .watermark("__timestamp", "SOURCE_WATERMARK()")
                .build()*/);
        table.printSchema();

        

        flinkEnv.execute();
    }

    public static void collectStats(DataSourceRegistry ddRegistry) throws Exception {
        ddRegistry.monitorDatasets(envProvider);


        Thread.sleep(1000);

        String content = Files.readString(RETAIL_SCRIPT_DIR.resolve(RETAIL_SCRIPT_NAME + SQML_SCRIPT_EXTENSION));
        SQMLBundle sqml = new SQMLBundle.Builder().setMainScript(RETAIL_SCRIPT_NAME, content).build();
        SourceDataset dd = ddRegistry.getDataset(RETAIL_DATA_DIR_NAME);

        //Retrieve the collected statistics
        for (String table : RETAIL_TABLE_NAMES) {
            SourceTableStatistics tableStats = ddRegistry.getTableStatistics(dd.getTable(table));
            SourceTableSchema schema = tableStats.getSchema();
            System.out.println(schema);
        }
    }


}