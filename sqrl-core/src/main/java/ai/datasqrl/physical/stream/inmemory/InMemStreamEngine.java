package ai.datasqrl.physical.stream.inmemory;

import ai.datasqrl.config.error.ErrorCollector;
import ai.datasqrl.config.provider.TableStatisticsStoreProvider;
import ai.datasqrl.io.formats.TextLineFormat;
import ai.datasqrl.io.impl.file.DirectorySourceImplementation;
import ai.datasqrl.io.impl.file.FilePath;
import ai.datasqrl.io.sources.*;
import ai.datasqrl.io.sources.dataset.SourceDataset;
import ai.datasqrl.io.sources.dataset.SourceTable;
import ai.datasqrl.io.sources.dataset.TableStatisticsStore;
import ai.datasqrl.io.sources.stats.SourceTableStatistics;
import ai.datasqrl.io.sources.util.TimeAnnotatedRecord;
import ai.datasqrl.parse.tree.name.Name;
import ai.datasqrl.physical.stream.FunctionWithError;
import ai.datasqrl.physical.stream.StreamEngine;
import ai.datasqrl.physical.stream.StreamHolder;
import ai.datasqrl.physical.stream.flink.FlinkStreamBuilder;
import ai.datasqrl.physical.stream.inmemory.io.FileStreamUtil;
import ai.datasqrl.schema.converters.SourceRecord2RowMapper;
import ai.datasqrl.schema.input.FlexibleDatasetSchema;
import com.google.common.base.Preconditions;
import lombok.Getter;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class InMemStreamEngine implements StreamEngine {

    private final AtomicInteger jobIdCounter = new AtomicInteger(0);
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();

    @Override
    public Builder createJob() {
        return new Builder();
    }

    @Override
    public Optional<? extends Job> getJob(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    @Override
    public void close() throws IOException {
        jobs.clear();
    }

    public class Builder implements StreamEngine.Builder {

        private final List<Stream> mainStreams = new ArrayList<>();
        private final List<Stream> sideStreams = new ArrayList<>();
        private final ErrorHolder errorHolder = new ErrorHolder();
        private final RecordHolder recordHolder = new RecordHolder();

        @Override
        public StreamHolder<TimeAnnotatedRecord<String>> fromTextSource(SourceTable table) {
            SourceTableConfiguration tblConfig = table.getConfiguration();
            Preconditions.checkArgument(tblConfig.getFormatParser() instanceof TextLineFormat.Parser, "This method only supports text sources");
            DataSource source = table.getDataset().getSource();
            DataSourceImplementation sourceImpl = source.getImplementation();

            if (sourceImpl instanceof DirectorySourceImplementation) {
                DirectorySourceImplementation filesource = (DirectorySourceImplementation)sourceImpl;
                try {
                    Stream<Path> paths = FileStreamUtil.matchingFiles(FilePath.toJavaPath(filesource.getPath()),
                            filesource, source.getCanonicalizer(), tblConfig);
                    return new Holder<>(FileStreamUtil.filesByline(paths).map(s -> new TimeAnnotatedRecord<>(s)));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else throw new UnsupportedOperationException();
        }

        @Override
        public StreamHolder<SourceRecord.Raw> monitor(StreamHolder<SourceRecord.Raw> stream, SourceTable sourceTable, TableStatisticsStoreProvider.Encapsulated statisticsStoreProvider) {
            final SourceTableStatistics statistics = new SourceTableStatistics();
            final Name dataset = sourceTable.getDataset().getName();
            final Name tableName = sourceTable.getName();
            final SourceDataset.Digest datasetReg = sourceTable.getDataset().getDigest();
            StreamHolder<SourceRecord.Raw> result = stream.mapWithError((r, c) -> {
                ai.datasqrl.config.error.ErrorCollector errors = statistics.validate(r, datasetReg);
                if (errors.hasErrors()) c.accept(errors);
                if (!errors.isFatal()) {
                    statistics.add(r, datasetReg);
                    return Optional.of(r);
                } else {
                    return Optional.empty();
                }
            },"stats",SourceRecord.Raw.class);
            sideStreams.add(Stream.of(statistics).map(s -> {
                try (TableStatisticsStore store = statisticsStoreProvider.openStore()) {
                    store.putTableStatistics(dataset,tableName,s);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return s;
            }));
            return result;
        }

        @Override
        public void addAsTable(StreamHolder<SourceRecord.Named> stream, FlexibleDatasetSchema.TableField schema, Name tableName) {
            final Consumer<Object[]> records = recordHolder.getCollector(tableName);
            SourceRecord2RowMapper<Object[],Object[]> mapper = new SourceRecord2RowMapper(schema, RowConstructor.INSTANCE);
            ((Holder<SourceRecord.Named>)stream).mapWithError((r,c) -> {
                try {
                    records.accept(mapper.apply(r));
                    return Optional.of(Boolean.TRUE);
                } catch (Exception e) {
                    ErrorCollector errors = ErrorCollector.root();
                    errors.fatal(e.toString());
                    c.accept(errors);
                    return Optional.of(Boolean.FALSE);
                }
            },"mapper", Boolean.class).sink();
        }

        @Override
        public Job build() {
            return new Job(mainStreams,sideStreams,errorHolder,recordHolder);
        }

        private class Holder<T> implements StreamHolder<T> {

            private boolean isClosed = false;
            private final Stream<T> stream;

            private Holder(Stream<T> stream) {
                this.stream = stream;
            }

            private void checkClosed() {
                Preconditions.checkArgument(!isClosed, "Only support single pipeline stream");
            }

            public void close() {
                isClosed = true;
            }

            private<R> Holder<R> wrap(Stream<R> newStream) {
                close();
                return new Holder<>(newStream);
            }

            @Override
            public <R> Holder<R> mapWithError(FunctionWithError<T, R> function, String errorName, Class<R> clazz) {
                checkClosed();
                return wrap(stream.flatMap(t -> {
                    Optional<R> result = function.apply(t, errorHolder.getCollector(errorName));
                    if (result.isPresent()) return Stream.of(result.get());
                    else return Stream.empty();
                }));
            }

            @Override
            public void printSink() {
                checkClosed();
                wrap(stream.map(r -> {
                    System.out.println(r);
                    return r;
                })).sink();
            }

            private void sink() {
                checkClosed();
                close();
                mainStreams.add(stream);
            }
        }
    }

    public static class ErrorHolder extends OutputCollector<String, ErrorCollector> {
    }

    public static class RecordHolder extends OutputCollector<Name, Object[]> {
    }

    public class Job implements StreamEngine.Job {

        private final String id;
        private Status status;

        private final List<Stream> mainStreams;
        private final List<Stream> sideStreams;
        @Getter
        private final ErrorHolder errorHolder;
        @Getter
        private final RecordHolder recordHolder;

        private Job(List<Stream> mainStreams, List<Stream> sideStreams, ErrorHolder errorHolder, RecordHolder recordHolder) {
            this.mainStreams = mainStreams;
            this.sideStreams = sideStreams;
            this.errorHolder = errorHolder;
            this.recordHolder = recordHolder;
            id = Integer.toString(jobIdCounter.incrementAndGet());
            jobs.put(id,this);
            status = Status.PREPARING;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public void execute(String name) {
            Preconditions.checkArgument(status == Status.PREPARING, "Job has already been executed");
            try {
                //Execute main streams first and side streams after
                for (Stream stream : mainStreams) {
                    stream.forEach(s -> {});
                }
                for (Stream stream : sideStreams) {
                    stream.forEach(s -> {});
                }
                status = Status.COMPLETED;
            } catch (Throwable e) {
                System.err.println(e);
                status = Status.FAILED;
            }
        }

        @Override
        public void cancel() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Status getStatus() {
            return status;
        }
    }

    private static class RowConstructor implements SourceRecord2RowMapper.RowConstructor<Object[],Object[]> {

        private static final RowConstructor INSTANCE = new RowConstructor();

        @Override
        public Object[] createRoot(Object[] columns) {
            return columns;
        }

        @Override
        public Object[] createNested(Object[] columns) {
            return columns;
        }

        @Override
        public Object[][] createRowArray(int size) {
            return new Object[size][];
        }
    }

}