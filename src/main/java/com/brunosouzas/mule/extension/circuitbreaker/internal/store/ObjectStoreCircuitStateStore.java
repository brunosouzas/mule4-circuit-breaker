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
 * Default {@link CircuitStateStore} adapter, backed by the Mule runtime's own Object Store. Covers the
 * single-node case this plugin ships for (ADR-001); a shared/distributed adapter for multi-replica
 * deployments is a separate, later module that implements the same port.
 *
 * <p>Every circuit's {@link CircuitSnapshot} is kept as a single value under one key in one shared,
 * non-persistent Object Store partition, so an atomic update is always "one lock, one key, one read, one
 * write" — never a multi-key operation that could leave a torn combination of state visible.
 *
 * <p>Concurrency: a {@link LockFactory} lock, keyed the same as the Object Store entry, serializes the
 * whole read-decide-write cycle per circuit key. This lock is node-local: on a multi-replica deployment,
 * each replica currently holds its own lock and its own store, so each replica's circuit is independently
 * consistent rather than shared. That gap is exactly what a future distributed adapter closes; it is not
 * addressed here.
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
        ObjectStoreSettings.builder().persistent(false).build());
  }
}
