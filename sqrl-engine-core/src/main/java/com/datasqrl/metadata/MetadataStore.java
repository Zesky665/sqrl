package com.datasqrl.metadata;

import com.datasqrl.parse.tree.name.Name;
import lombok.NonNull;

import java.io.Closeable;
import java.util.Set;

public interface MetadataStore extends Closeable {

  void close();

  <T> void put(@NonNull T value, @NonNull String firstKey, String... moreKeys);

  <T> T get(@NonNull Class<T> clazz, @NonNull String firstKey, String... moreKeys);

  boolean remove(@NonNull String firstKey, String... moreKeys);

  Set<String> getSubKeys(String... keys);

  default String name2Key(Name name) {
    return name.getCanonical();
  }

}