package ru.stepan1411.sablenautics.trains;

/**
 * Implemented by {@code Train} through {@code TrainMixin}. Persisting the flag lets the mod
 * recognise Sable trains after a reload even if none of their sub-levels are available.
 */
public interface SableTrainAccess {

    boolean sablenautics$isSableTrain();

    void sablenautics$setSableTrain(boolean sable);

    /** Clears Create's pending dimension migrations so a detached train cannot re-attach itself. */
    void sablenautics$clearMigrations();
}
