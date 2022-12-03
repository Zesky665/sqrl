package com.datasqrl.io.sources;

import com.datasqrl.config.error.ErrorCollector;
import com.datasqrl.io.sources.dataset.TableConfig;
import com.datasqrl.parse.tree.name.Name;
import lombok.NonNull;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;

public interface DataSystemDiscovery {

    /**
     * The name of the dataset produced by this data source if discoverable from the configuration
     *
     * @return name of dataset
     */
    @NonNull Optional<String> getDefaultName();

    boolean requiresFormat(ExternalDataType type);

    default Collection<TableConfig> discoverSources(@NonNull DataSystemConfig config,
                                            @NonNull ErrorCollector errors) {
        return Collections.EMPTY_LIST;
    }

    default Optional<TableConfig> discoverSink(@NonNull Name sinkName, @NonNull DataSystemConfig config,
                                               @NonNull ErrorCollector errors) {
        return Optional.empty();
    }

}