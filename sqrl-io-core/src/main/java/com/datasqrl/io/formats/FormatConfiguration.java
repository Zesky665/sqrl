package com.datasqrl.io.formats;

import com.datasqrl.config.error.ErrorCollector;
import com.datasqrl.io.impl.InputPreview;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.NonNull;

import java.io.Serializable;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "formatType")
@JsonSubTypes({@JsonSubTypes.Type(value = CSVFormat.Configuration.class, name = CSVFormat.NAME),
    @JsonSubTypes.Type(value = JsonLineFormat.Configuration.class, name = JsonLineFormat.NAME),})
public interface FormatConfiguration extends Serializable {

  boolean initialize(InputPreview preview, @NonNull ErrorCollector errors);

  default boolean initialize(@NonNull ErrorCollector errors) {
    return initialize(null, errors);
  }

  @JsonIgnore
  FileFormat getFileFormat();

  @JsonIgnore
  Format getImplementation();

  @JsonIgnore
  String getName();

}