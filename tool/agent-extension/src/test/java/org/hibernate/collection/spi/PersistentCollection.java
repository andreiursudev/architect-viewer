package org.hibernate.collection.spi;

/** Test stand-in for Hibernate's type (the extension matches it by name and never links against Hibernate). */
public interface PersistentCollection {
    boolean wasInitialized();
}
