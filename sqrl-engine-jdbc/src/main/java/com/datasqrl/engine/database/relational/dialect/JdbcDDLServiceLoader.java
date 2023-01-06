/*
 * Copyright (c) 2021, DataSQRL. All rights reserved. Use is subject to license terms.
 */
package com.datasqrl.engine.database.relational.dialect;

import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Loads via ServiceLoader
 */
public class JdbcDDLServiceLoader {

  public Optional<JdbcDDLFactory> load(String dialect) {
    ServiceLoader<JdbcDDLFactory> factories = ServiceLoader.load(JdbcDDLFactory.class);
    for (JdbcDDLFactory factory : factories) {
      if (factory.getDialect().equalsIgnoreCase(dialect)) {
        return Optional.of(factory);
      }
    }

    return Optional.empty();
  }

}