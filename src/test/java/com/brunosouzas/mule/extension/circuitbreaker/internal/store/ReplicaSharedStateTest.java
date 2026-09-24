package com.brunosouzas.mule.extension.circuitbreaker.internal.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.brunosouzas.mule.extension.circuitbreaker.api.store.CircuitSnapshot;
import com.brunosouzas.mule.extension.circuitbreaker.api.store.CircuitState;
import com.brunosouzas.mule.extension.circuitbreaker.api.store.SlidingWindow;
import java.io.File;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.config.MuleConfiguration;
import org.mule.runtime.core.internal.serialization.JavaObjectSerializer;
import org.mule.runtime.core.internal.store.PartitionedPersistentObjectStore;

/**
 * BRU-54's acceptance criterion, proved directly against the real Mule runtime mechanism rather than a
 * hand-rolled stand-in: {@code ObjectStoreCircuitStateStore} now configures its Object Store as
 * {@code persistent(true)} (see that class). Locally, "persistent" means
 * {@code org.mule.runtime.core.internal.store.PartitionedPersistentObjectStore}, which persists each
 * partition to {@code <workingDirectory>/objectstore/<random-UUID>/}, discovering existing partitions by
 * scanning that directory for {@code partition-descriptor} files on {@code open()}. On CloudHub 2.0 the
 * same {@code persistent(true)} setting is backed by the runtime's own managed, replica-shared
 * implementation instead — this test cannot reach that (no CloudHub 2.0 access for this personal
 * project), but it proves the same client-facing contract this adapter relies on: two independent
 * instances pointed at the same backing location see each other's writes.
 *
 * <p><b>Why this touches an internal Mule Runtime class</b>: {@code PartitionedPersistentObjectStore} is
 * under {@code org.mule.runtime.core.internal}, not a published SDK contract — it can change or move
 * across runtime versions without notice. Using it here, in a test only, is a deliberate trade-off: it
 * proves the actual mechanism this adapter depends on, rather than a reimplementation of it that could
 * pass even if the real thing behaved differently. If a runtime upgrade breaks this test, the fix is to
 * re-check this class's current shape, not to weaken the assertion.
 *
 * <p><b>Ordering matters</b>: replica A fully stores before replica B opens the same partition name.
 * {@code PartitionedPersistentObjectStore} assigns a random UUID folder to a partition the first time it
 * is created, with no cross-instance coordination — if two replicas raced to create the same
 * never-before-persisted partition at once, each could get its own folder and silently diverge. That is a
 * real, documented limit (see the README), not something this test works around; running sequentially is
 * what "a circuit opened on one replica stays open for the ones that come after" actually means.
 */
// MuleContext itself is @Deprecated in this runtime version (superseded internally), on top of already
// being an internal type this test deliberately couples to — see the class javadoc above.
@SuppressWarnings("deprecation")
class ReplicaSharedStateTest {

  private static final String PARTITION = "circuit-breaker-state";

  @Test
  void circuitOpenedByOneReplicaIsVisibleToAnotherSharingTheSameBackingLocation(@TempDir File sharedWorkingDirectory)
      throws Exception {
    MuleContext muleContext = fakeMuleContext(sharedWorkingDirectory);
    CircuitSnapshot open = new CircuitSnapshot(CircuitState.OPEN, SlidingWindow.empty(4),
        System.currentTimeMillis(), 0, 0);

    // Replica A: opens the shared partition and persists an OPEN circuit for "service-x".
    PartitionedPersistentObjectStore<CircuitSnapshot> replicaA = new PartitionedPersistentObjectStore<>(muleContext);
    replicaA.open(PARTITION);
    replicaA.store("service-x", open, PARTITION);

    // Replica B: a separate instance, started afterward, pointed at the same working directory — this is
    // the "another replica" from the acceptance criterion, not the same object reused.
    PartitionedPersistentObjectStore<CircuitSnapshot> replicaB = new PartitionedPersistentObjectStore<>(muleContext);
    replicaB.open(PARTITION);

    assertTrue(replicaB.contains("service-x", PARTITION));
    assertEquals(CircuitState.OPEN, replicaB.retrieve("service-x", PARTITION).getState());
  }

  /**
   * The store touches {@code getConfiguration().getWorkingDirectory()} to construct/load a partition, and
   * the {@link JavaObjectSerializer} it gets back from {@code getObjectSerializer()} calls back into
   * {@code getExecutionClassLoader()} on every deserialize — found by running this test, not by reading
   * the javadoc: without both stubs, restoring a partition someone else already wrote (replica B's case)
   * fails with a wrapped {@code NullPointerException}, not a documented one. A full Mule application
   * context is still not needed to exercise this mechanism.
   */
  private static MuleContext fakeMuleContext(File workingDirectory) {
    MuleConfiguration configuration = mock(MuleConfiguration.class);
    when(configuration.getWorkingDirectory()).thenReturn(workingDirectory.getAbsolutePath());

    MuleContext muleContext = mock(MuleContext.class);
    when(muleContext.getConfiguration()).thenReturn(configuration);
    when(muleContext.getExecutionClassLoader()).thenReturn(ReplicaSharedStateTest.class.getClassLoader());

    JavaObjectSerializer serializer = new JavaObjectSerializer();
    serializer.setMuleContext(muleContext);
    when(muleContext.getObjectSerializer()).thenReturn(serializer);
    return muleContext;
  }
}
