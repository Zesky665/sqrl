package ai.datasqrl.io.sinks;

import ai.datasqrl.config.error.ErrorCollector;
import ai.datasqrl.config.util.ConfigurationUtil;
import ai.datasqrl.parse.tree.name.Name;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.ToString;

@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
@ToString
public class DataSinkRegistration {

  @NonNull @NotNull @Size(min = 3)
  String name;

  @NonNull @NotNull @Valid
  DataSinkImplementation sink;

  @NonNull @NotNull @Valid
  @Builder.Default
  DataSinkConfiguration config = new DataSinkConfiguration();

  public boolean initialize(ErrorCollector errors) {
    if (!ConfigurationUtil.javaxValidate(this, errors)) {
      return false;
    }
    if (!Name.validName(name)) {
      errors.fatal("Sink needs to have valid name: %s", name);
      return false;
    }
    errors = errors.resolve(name);
    if (!sink.initialize(errors)) {
      return false;
    }
    return config.initialize(errors);
  }

}