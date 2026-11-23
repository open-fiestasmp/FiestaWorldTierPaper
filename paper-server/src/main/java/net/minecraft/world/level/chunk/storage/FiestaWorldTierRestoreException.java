package net.minecraft.world.level.chunk.storage;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Marks an I/O failure that must not be converted into an empty Minecraft chunk. */
public final class FiestaWorldTierRestoreException extends IOException {
    private final boolean retryable;

    public FiestaWorldTierRestoreException(final String message, final Throwable cause) {
        this(message, cause, false);
    }

    public FiestaWorldTierRestoreException(final String message, final Throwable cause, final boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return this.retryable;
    }

    /** Finds a tier failure even when Paper or Moonrise wrapped it in another exception. */
    public static FiestaWorldTierRestoreException find(final Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        while (current != null && seen.add(current)) {
            if (current instanceof FiestaWorldTierRestoreException tierFailure) {
                return tierFailure;
            }
            current = current.getCause();
        }
        return null;
    }
}
