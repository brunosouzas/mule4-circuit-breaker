package com.brunosouzas.mule.extension.circuitbreaker.internal.store;

import com.brunosouzas.mule.extension.circuitbreaker.api.store.CircuitSnapshot;
import com.brunosouzas.mule.extension.circuitbreaker.api.store.CircuitStateStore;
import java.util.concurrent.locks.Lock;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.i18n.I18nMessageFactory;
import org.mule.runtime.api.lock.LockFactory;
import org.mule.runtime.api.store.ObjectStore;
import org.mule.runtime.api.store.ObjectStoreException;
import org.mule.runtime.api.store.ObjectStoreManager;
import org.mule.runtime.api.store.ObjectStoreSettings;
import org.mule.runtime.extension.api.annotation.Alias;

/**
 * Default {@link CircuitStateStore} adapter, backed by the Mule runtime's own Object Store, configured as
 * <b>persistent</b> (BRU-54). This is the same mechanism the Mule runtime uses to share data across
 * CloudHub 2.0 replicas of one deployment: locally/standalone it persists to disk under the working
 * directory; on CloudHub 2.0 the runtime backs it with its own managed, replica-shared implementation
 * instead. No separate adapter class is needed for that — the port stays the same, only this one setting
 * changes what backs it.
 *
 * <p>Every circuit's {@link CircuitSnapshot} is kept as a single value under one key in one shared,
 * persistent Object Store partition, so an atomic update is always "one lock, one key, one read, one
 * write" — never a multi-key operation that could leave a torn combination of state visible.
 *
 * <p>Concurrency and consistency limits (see the README for the full write-up):
 * <ul>
 *   <li>The {@link LockFactory} lock below serializes reads/writes to a given circuit key <i>within one
 *   replica</i>. It is not a distributed lock: two replicas writing to the same circuit key at the same
 *   time race at the file/storage level, with no cross-replica coordination.</li>
 *   <li>The very first time the {@code circuit-breaker-state} partition is created, if two replicas both
 *   race to create it before either has persisted anything, the Mule runtime's persistent Object Store
 *   implementation can let each replica create its own separate backing location — an unmitigated
 *   runtime-internal behaviour, not something this adapter can coordinate around.</li>
 * </ul>
 */
@Alias("object-store")
public class ObjectStoreCircuitStateStore implements CircuitStateStore {

  private static final String OBJECT_STORE_NAME = "circuit-breaker-state";
  private static final String LOCK_PREFIX = "circuit-breaker:state:";

  private ObjectStoreManager objectStoreManager;
  private LockFactory lockFactory;

  /** Wires in the runtime services this adapter needs. Called once, by the owning configuration. */
  public void bind(ObjectStoreManager objectStoreManager, LockFactory lockFactory) {
    this.objectStoreManager = objectStoreManager;
    this.lockFactory = lockFactory;
  }

  @Override
  public <T> T update(String circuitKey, StateTransition<T> transition) {
    Lock lock = lockFactory.createLock(LOCK_PREFIX + circuitKey);
    lock.lock();
    try {
      ObjectStore<CircuitSnapshot> objectStore = objectStore();
      CircuitSnapshot current = objectStore.contains(circuitKey) ? objectStore.retrieve(circuitKey) : null;

      Update<T> update = transition.apply(current);

      if (objectStore.contains(circuitKey)) {
        objectStore.remove(circuitKey);
      }
      objectStore.store(circuitKey, update.getNewSnapshot());

      return update.getResult();
    } catch (ObjectStoreException e) {
      throw new MuleRuntimeException(
          I18nMessageFactory.createStaticMessage("Failed to update circuit breaker state for key '" + circuitKey + "'"),
          e);
    } finally {
      lock.unlock();
    }
  }

  @SuppressWarnings("unchecked")
  private ObjectStore<CircuitSnapshot> objectStore() {
    return objectStoreManager.getOrCreateObjectStore(OBJECT_STORE_NAME,
        ObjectStoreSettings.builder().persistent(true).build());
  }
}
