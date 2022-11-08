package ai.datasqrl.compile.loaders;

import ai.datasqrl.parse.tree.name.Name;
import ai.datasqrl.parse.tree.name.NamePath;
import ai.datasqrl.plan.local.generate.Resolve;
import lombok.AllArgsConstructor;
import lombok.Value;

import java.nio.file.Path;
import java.util.*;

@Value
@AllArgsConstructor
public class CompositeLoader extends AbstractLoader implements Loader {

    List<Loader> loaders;

    public CompositeLoader(Loader... loaders) {
        this(List.of(loaders));
    }

    @Override
    public Optional<String> handles(Path file) {
        for (Loader loader : loaders) {
            Optional<String> result = loader.handles(file);
            if (result.isPresent()) return result;
        }
        return Optional.empty();
    }

    @Override
    public boolean load(Resolve.Env env, NamePath fullPath, Optional<Name> alias) {
        for (Loader loader : loaders) {
            if (loader.load(env,fullPath,alias)) return true;
        }
        return false;
    }

}