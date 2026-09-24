package com.brunosouzas.mule.extension.circuitbreaker.internal.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.brunosouzas.mule.extension.circuitbreaker.api.store.CircuitSnapshot;
import com.brunosouzas.mule.extension.circuitbreaker.api.store.CircuitState;
import com.brunosouzas.mule.extension.circuitbreaker.api.store.CircuitStateStore;
import com.brunosouzas.mule.extension.circuitbreaker.api.store.SlidingWindow;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.mule.runtime.api.lock.LockFactory;
import org.mule.runtime.api.store.ObjectAlreadyExistsException;
import org.mule.runtime.api.store.ObjectDoesNotExistException;
import org.mule.runtime.api.store.ObjectStore;
import org.mule.runtime.api.store.ObjectStoreException;
import org.mule.runtime.api.store.ObjectStoreManager;
import org.mule.runtime.api.store.ObjectStoreSettings;

class ObjectStoreCircuitStateStoreTest {

  @Test
  void updateOnAMissingKeyPassesNullAsTheCurrentSnapshot() {
    ObjectStoreCircuitStateStore store = newStore();

    CircuitSnapshot seen = store.update("circuit-a", current -> {
      CircuitSnapshot next = current != null ? current : CircuitSnapshot.initial(4, 0L);
      return new CircuitStateStore.Update<>(next, current);
    });

    assertNull(seen);
  }

  @Test
  void updateRoundTripsTheStoredSnapshot() {
    ObjectStoreCircuitStateStore store = newStore();

    store.<Void>update("circuit-a", current -> new CircuitStateStore.Update<>(
        new CircuitSnapshot(CircuitState.OPEN, SlidingWindow.empty(4), 42L, 0, 0), null));

    CircuitSnapshot roundTripped = store.update("circuit-a", current -> new CircuitStateStore.Update<>(current, current));

    assertEquals(CircuitState.OPEN, roundTripped.getState());
    assertEquals(42L, roundTripped.getStateChangedAtEpochMs());
  }

  @Test
  void differentCircuitKeysNeverSeeEachOthersState() {
    ObjectStoreCircuitStateStore store = newStore();

    store.<Void>update("circuit-a", current -> new CircuitStateStore.Update<>(
        new CircuitSnapshot(CircuitState.OPEN, SlidingWindow.empty(4), 1L, 0, 0), null));
    store.<Void>update("circuit-b", current -> new CircuitStateStore.Update<>(
        CircuitSnapshot.initial(4, 2L), null));

    CircuitSnapshot a = store.update("circuit-a", current -> new CircuitStateStore.Update<>(current, current));
    CircuitSnapshot b = store.update("circuit-b", current -> new CircuitStateStore.Update<>(current, current));

    assertEquals(CircuitState.OPEN, a.getState());
    assertEquals(CircuitState.CLOSED, b.getState());
  }

  @Test
  void concurrentUpdatesOnTheSameKeySerializeCorrectly() throws InterruptedException {
    ObjectStoreCircuitStateStore store = newStore();
    store.<Void>update("shared-circuit", current -> new CircuitStateStore.Update<>(CircuitSnapshot.initial(1000, 0L), null));

    int threads = 20;
    int updatesPerThread = 50;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      for (int t = 0; t < threads; t++) {
        pool.submit(() -> {
          for (int i = 0; i < updatesPerThread; i++) {
            store.<Void>update("shared-circuit", current -> {
              SlidingWindow window = current.getWindow().record(true);
              return new CircuitStateStore.Update<>(current.withWindow(window), null);
            });
          }
        });
      }
      pool.shutdown();
      assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }

    CircuitSnapshot finalSnapshot = store.update("shared-circuit", current -> new CircuitStateStore.Update<>(current, current));
    assertEquals(threads * updatesPerThread, finalSnapshot.getWindow().getFailureCount());
  }

  private static ObjectStoreCircuitStateStore newStore() {
    ObjectStoreCircuitStateStore store = new ObjectStoreCircuitStateStore();
    store.bind(new FakeObjectStoreManager(), new FakeLockFactory());
    return store;
  }

  /** In-memory {@link ObjectStore}, faithful to the real contract: {@code store()} on an existing key throws. */
  private static final class FakeObjectStore<T extends Serializable> implements ObjectStore<T> {

    private final Map<String, T> data = new ConcurrentHashMap<>();

    @Override
    public boolean contains(String key) {
      return data.containsKey(key);
    }

    @Override
    public void store(String key, T value) throws ObjectStoreException {
      if (data.containsKey(key)) {
        throw new ObjectAlreadyExistsException();
      }
      data.put(key, value);
    }

    @Override
    public T retrieve(String key) throws ObjectStoreException {
      if (!data.containsKey(key)) {
        throw new ObjectDoesNotExistException();
      }
      return data.get(key);
    }

    @Override
    public T remove(String key) throws ObjectStoreException {
      if (!data.containsKey(key)) {
        throw new ObjectDoesNotExistException();
      }
      return data.remove(key);
    }

    @Override
    public boolean isPersistent() {
      return false;
    }

    @Override
    public void clear() {
      data.clear();
    }

    @Override
    public void open() {
      // no-op
    }

    @Override
    public void close() {
      // no-op
    }

    @Override
    public List<String> allKeys() {
      return List.copyOf(data.keySet());
    }

    @Override
    public Map<String, T> retrieveAll() {
      return Map.copyOf(data);
    }
  }

  private static final class FakeObjectStoreManager implements ObjectStoreManager {

    private final Map<String, ObjectStore<? extends Serializable>> stores = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T extends ObjectStore<? extends Serializable>> T getObjectStore(String name) {
      return (T) stores.get(name);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends ObjectStore<? extends Serializable>> T createObjectStore(String name, ObjectStoreSettings settings) {
      return (T) stores.computeIfAbsent(name, key -> new FakeObjectStore<>());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends ObjectStore<? extends Serializable>> T getOrCreateObjectStore(String name, ObjectStoreSettings settings) {
      return (T) stores.computeIfAbsent(name, key -> new FakeObjectStore<>());
    }

    @Override
    public void disposeStore(String name) {
      stores.remove(name);
    }
  }

  private static final class FakeLockFactory implements LockFactory {

    private final Map<String, Lock> locks = new ConcurrentHashMap<>();

    @Override
    public Lock createLock(String lockId) {
      return locks.computeIfAbsent(lockId, key -> new ReentrantLock());
    }
  }
}
