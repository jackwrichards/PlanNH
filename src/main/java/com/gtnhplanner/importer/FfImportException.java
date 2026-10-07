package com.gtnhplanner.importer;

import javax.annotation.Nullable;

/** The pasted text is not a Factory Flow plan GTNH Planner can read. The message is meant for the player. */
public class FfImportException extends RuntimeException {

    public FfImportException(final String message) {
        super(message);
    }

    public FfImportException(final String message, @Nullable final Throwable cause) {
        super(message, cause);
    }
}
