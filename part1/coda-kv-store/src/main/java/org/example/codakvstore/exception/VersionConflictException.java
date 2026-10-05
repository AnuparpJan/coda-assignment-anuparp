package org.example.codakvstore.exception;

public class VersionConflictException extends RuntimeException {
    public VersionConflictException(String key, Integer expected, Integer actual) {
        super("Version conflict on key " + key + ": expected " + expected + ", actual " + actual);
    }
}
