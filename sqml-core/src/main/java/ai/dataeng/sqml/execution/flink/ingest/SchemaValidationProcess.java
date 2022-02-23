package ai.dataeng.sqml.execution.flink.ingest;

import ai.dataeng.sqml.io.sources.SourceRecord;
import ai.dataeng.sqml.io.sources.dataset.SourceDataset;
import ai.dataeng.sqml.type.basic.ProcessMessage.ProcessBundle;
import ai.dataeng.sqml.type.schema.FlexibleDatasetSchema;
import ai.dataeng.sqml.type.schema.SchemaAdjustmentSettings;
import ai.dataeng.sqml.type.schema.SchemaConversionError;
import ai.dataeng.sqml.type.schema.SchemaValidator;
import lombok.Value;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class SchemaValidationProcess extends ProcessFunction<SourceRecord.Raw, SourceRecord.Named> {

    private final OutputTag<Error> errorTag;
    private final SchemaValidator validator;

    public SchemaValidationProcess(OutputTag<Error> error, FlexibleDatasetSchema.TableField schema,
                                   SchemaAdjustmentSettings settings, SourceDataset.Digest dataset) {
        this.errorTag = error;
        this.validator = new SchemaValidator(schema, settings, dataset);
    }

    @Override
    public void processElement(SourceRecord.Raw sourceRecord, Context context, Collector<SourceRecord.Named> out) {
        ProcessBundle<SchemaConversionError> errors = new ProcessBundle<>();
        SourceRecord.Named result = validator.verifyAndAdjust(sourceRecord, errors);
        if (errors.isFatal()) {
            context.output(errorTag, SchemaValidationProcess.Error.of(errors,sourceRecord));
        } else {
            out.collect(result);
        }
    }

    @Value
    public static class Error implements Serializable {

        private List<String> errors;
        private SourceRecord<String> sourceRecord;

        public static Error of(ProcessBundle<SchemaConversionError> errors, SourceRecord<String> sourceRecord) {
            List<String> errorMsgs = new ArrayList<>();
            errors.forEach(e -> errorMsgs.add(e.toString()));
            return new Error(errorMsgs, sourceRecord);
        }

    }
}